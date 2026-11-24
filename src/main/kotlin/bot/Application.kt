package bot

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import kotlin.system.exitProcess

@SpringBootApplication
class Application

fun main(args: Array<String>) {
    val logger = LoggerFactory.getLogger(Application::class.java)
    if (System.getenv("BOT_MODE") == "admin") {
        runApplication<Application>(*args) { setAdditionalProfiles("admin") }
        return
    }
    val status = try {
        runApplication<Application>(*args).use { context ->
            context.getBean(ConfiguredPollJob::class.java).run()
        }
        0
    } catch (failure: Exception) {
        val description = if (failure is PollFailure) failure.message else failure.javaClass.simpleName
        logger.error("Mail poll failed ({})", description)
        1
    }
    exitProcess(status)
}

private class PollFailure(stage: String, failure: Exception) :
    RuntimeException(
        "$stage: " + if (failure is DiscordSendException) "Discord HTTP ${failure.status}"
        else failure.javaClass.simpleName,
        failure,
    )

@Service
class ConfiguredPollJob(
    private val settings: BotSettingsStore,
    private val mapper: ObjectMapper,
    private val jdbc: JdbcTemplate,
    private val transactions: TransactionTemplate,
) {
    fun run() {
        val config = settings.load()
        if (!config.enabled) {
            LoggerFactory.getLogger(javaClass).info("Bot polling is disabled")
            return
        }
        check(config.hasCredentials()) { "Configure Gmail and Discord credentials" }
        val gmail = GmailClient(mapper, config.gmailClientId, config.gmailClientSecret, config.gmailRefreshToken)
        val discord = DiscordClient(mapper, config.discordBotToken, config.discordChannelId)
        PollJob(gmail, discord, jdbc, transactions).run(config.gmailAddress)
    }
}

class PollJob(
    private val gmail: GmailClient,
    private val discord: DiscordClient,
    private val jdbc: JdbcTemplate,
    private val transactions: TransactionTemplate,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun run(expectedMailbox: String = "") {
        // Keep a dedicated connection for the lock while other connections write checkpoints.
        jdbc.execute(ConnectionCallback { connection ->
            connection.prepareStatement("SELECT pg_try_advisory_lock(173186401)").use { statement ->
                statement.executeQuery().use { result ->
                    check(result.next() && result.getBoolean(1)) { "Mail poll already running" }
                }
            }
            try {
                val startedAt = Instant.now().epochSecond
                val profile = try {
                    gmail.profile()
                } catch (failure: Exception) {
                    throw PollFailure("Gmail profile lookup", failure)
                }
                if (expectedMailbox.isNotBlank() && !profile.email.equals(expectedMailbox, ignoreCase = true)) {
                    throw PollFailure("Gmail address does not match the authorized account", IllegalStateException())
                }
                try {
                    synchronize(profile.email, profile.historyId, startedAt)
                } catch (failure: Exception) {
                    // Older queued mail can still be delivered when Gmail synchronization fails.
                    try {
                        sendPending(profile.email)
                    } catch (deliveryFailure: Exception) {
                        logger.warn("Pending delivery also failed ({})", deliveryFailure.javaClass.simpleName)
                    }
                    throw PollFailure("Gmail synchronization", failure)
                }
                try {
                    sendPending(profile.email)
                } catch (failure: Exception) {
                    throw PollFailure("Discord delivery", failure)
                }
            } finally {
                connection.prepareStatement("SELECT pg_advisory_unlock(173186401)").use { it.execute() }
            }
        })
    }

    private fun synchronize(mailbox: String, currentHistory: String, startedAt: Long) {
        val checkpoint = jdbc.query(
            "SELECT history_id, checked_at FROM gmail_checkpoint WHERE mailbox = ?",
            { row, _ -> Checkpoint(row.getString("history_id"), row.getLong("checked_at")) },
            mailbox,
        ).singleOrNull()
        if (checkpoint == null) {
            jdbc.update(
                "INSERT INTO gmail_checkpoint (mailbox, history_id, checked_at) VALUES (?, ?, ?)",
                mailbox, currentHistory, startedAt,
            )
            logger.info("Initialized Gmail checkpoint")
            return
        }

        val changes = gmail.changes(checkpoint.historyId)
        if (changes == null) logger.warn("Gmail history expired. Rescanning the inbox.")
        val ids = changes?.ids ?: gmail.inboxSince(checkpoint.checkedAt - 1)
        val mails = ids.distinct().mapNotNull(gmail::message)

        transactions.executeWithoutResult {
            for (mail in mails) {
                jdbc.update(
                    """
                    INSERT INTO discord_outbox (mailbox, message_id, subject, snippet, link)
                    VALUES (?, ?, ?, ?, ?) ON CONFLICT (mailbox, message_id) DO NOTHING
                    """.trimIndent(),
                    mailbox, mail.id, mail.subject, mail.snippet, mail.link,
                )
            }
            jdbc.update(
                "UPDATE gmail_checkpoint SET history_id = ?, checked_at = ? WHERE mailbox = ?",
                changes?.cursor ?: currentHistory, startedAt, mailbox,
            )
        }
        logger.info("Gmail sync complete ({} available messages)", mails.size)
    }

    private fun sendPending(mailbox: String) {
        val pending = jdbc.query(
            "SELECT message_id, subject, snippet, link FROM discord_outbox WHERE mailbox = ? AND NOT sent ORDER BY message_id",
            { row, _ -> Mail(row.getString(1), row.getString(2), row.getString(3), row.getString(4)) },
            mailbox,
        )
        for (mail in pending) {
            discord.send(mail)
            jdbc.update(
                "UPDATE discord_outbox SET sent = TRUE, subject = '', snippet = '', link = '' WHERE mailbox = ? AND message_id = ?",
                mailbox, mail.id,
            )
        }
        logger.info("Discord delivery complete ({} messages)", pending.size)
    }

    private data class Checkpoint(val historyId: String, val checkedAt: Long)
}

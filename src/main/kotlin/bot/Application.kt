package bot

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.core.env.Environment
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Clock
import java.util.Base64

@SpringBootApplication
class Application {
    @Bean
    fun gmail(mapper: ObjectMapper, env: Environment) = GmailClient(
        mapper, env.getRequiredProperty("GMAIL_CLIENT_ID"),
        env.getRequiredProperty("GMAIL_CLIENT_SECRET"), env.getRequiredProperty("GMAIL_REFRESH_TOKEN"),
    )

    @Bean
    fun discord(mapper: ObjectMapper, env: Environment) = DiscordClient(
        mapper, env.getRequiredProperty("DISCORD_BOT_TOKEN"), env.getRequiredProperty("DISCORD_CHANNEL_ID"),
    )

    @Bean
    fun clock(): Clock = Clock.systemUTC()
}

fun main(args: Array<String>) {
    runApplication<Application>(*args)
}

@RestController
class GmailPushController(private val job: GmailPushJob, private val mapper: ObjectMapper) {
    @PostMapping("/pubsub/gmail")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun push(@RequestBody envelope: JsonNode) {
        val notification = try {
            val data = envelope.path("message").path("data").asText()
            val bytes = try {
                Base64.getDecoder().decode(data)
            } catch (_: IllegalArgumentException) {
                Base64.getUrlDecoder().decode(data)
            }
            requireNotNull(mapper.readTree(bytes))
        } catch (_: Exception) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Pub/Sub notification")
        }
        val email = notification.path("emailAddress").asText()
        val historyId = notification.path("historyId").asText().toBigIntegerOrNull()
        if (email.isBlank() || historyId == null || historyId.signum() <= 0) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Gmail notification")
        }
        job.onPush(email, historyId.toString())
    }

    @PostMapping("/jobs/renew")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun renew() = job.renew()
}

@Service
class GmailPushJob(
    private val gmail: GmailClient,
    private val discord: DiscordClient,
    private val checkpoints: CheckpointStore,
    private val clock: Clock,
    environment: Environment,
) {
    private val mailbox = environment.getRequiredProperty("GMAIL_ADDRESS").trim()
    private val topic = environment.getRequiredProperty("GMAIL_TOPIC")
    private val logger = LoggerFactory.getLogger(javaClass)

    @Synchronized
    fun onPush(email: String, historyId: String) {
        if (!email.equals(mailbox, ignoreCase = true)) return
        val checkpoint = load() ?: throw ResponseStatusException(
            HttpStatus.SERVICE_UNAVAILABLE, "Gmail watch has not been initialized",
        )
        if (historyId.toBigInteger() > checkpoint.historyId.toBigInteger()) {
            val startedAt = clock.instant().epochSecond
            synchronize(checkpoint, startedAt, verifiedProfile().historyId, historyId)
        }
    }

    @Synchronized
    fun renew() {
        val startedAt = clock.instant().epochSecond
        val profile = verifiedProfile()
        val checkpoint = load()
        val watchHistory = gmail.watch(topic)
        if (checkpoint == null) {
            checkpoints.save(Checkpoint(mailbox, watchHistory, startedAt), null)
            logger.info("Gmail watch initialized")
        } else {
            synchronize(checkpoint, startedAt, maxHistory(profile.historyId, watchHistory), watchHistory)
        }
    }

    private fun load(): Checkpoint? = checkpoints.load()?.also {
        check(it.mailbox.equals(mailbox, ignoreCase = true)) {
            "Checkpoint belongs to a different mailbox"
        }
    }

    private fun verifiedProfile(): Profile = gmail.profile().also {
        check(it.email.equals(mailbox, ignoreCase = true)) {
            "Configured Gmail address does not match the authorized account"
        }
    }

    private fun maxHistory(first: String, second: String): String =
        if (first.toBigInteger() >= second.toBigInteger()) first else second

    private fun synchronize(checkpoint: Checkpoint, startedAt: Long, recoveryCursor: String, minimumCursor: String) {
        val changes = gmail.changes(checkpoint.historyId)
        val cursor: String
        val ids: List<String>
        if (changes == null) {
            logger.warn("Gmail history expired. Recovering from the inbox.")
            cursor = recoveryCursor
            ids = gmail.inboxSince(checkpoint.checkedAt - 1)
        } else {
            cursor = changes.cursor
            ids = changes.ids
        }

        check(cursor == maxHistory(cursor, maxHistory(checkpoint.historyId, minimumCursor))) {
            "Gmail history has not caught up to the notification"
        }

        var sent = 0
        for (id in ids.distinct()) {
            val mail = gmail.message(id) ?: continue
            discord.send(mail)
            sent++
        }
        checkpoints.save(Checkpoint(mailbox, cursor, startedAt), checkpoint.generation)
        logger.info("Gmail synchronization complete ({} notifications sent)", sent)
    }
}

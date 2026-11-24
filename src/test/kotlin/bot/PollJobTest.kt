package bot

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.util.UUID

@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class PollJobTest {
    @Test
    fun `Spring assembles the non-web poll job`() {
        SpringApplicationBuilder(Application::class.java).properties(
            "DATABASE_URL=${System.getenv("TEST_DATABASE_URL")}",
            "DATABASE_USERNAME=${System.getenv("TEST_DATABASE_USERNAME").orEmpty()}",
            "DATABASE_PASSWORD=${System.getenv("TEST_DATABASE_PASSWORD").orEmpty()}",
            "GMAIL_CLIENT_ID=test",
            "GMAIL_CLIENT_SECRET=test",
            "GMAIL_REFRESH_TOKEN=test",
            "DISCORD_BOT_TOKEN=test",
            "DISCORD_CHANNEL_ID=test",
        ).run().use { context ->
            assertEquals(1, context.getBeanNamesForType(ConfiguredPollJob::class.java).size)
        }
    }

    @Test
    fun `expired history recovers messages and failed delivery survives the next run`() {
        val source = DriverManagerDataSource(
            System.getenv("TEST_DATABASE_URL"),
            System.getenv("TEST_DATABASE_USERNAME") ?: "",
            System.getenv("TEST_DATABASE_PASSWORD") ?: "",
        )
        ResourceDatabasePopulator(ClassPathResource("schema.sql")).execute(source)
        val jdbc = JdbcTemplate(source)
        val gmail = mock(GmailClient::class.java)
        val discord = mock(DiscordClient::class.java)
        val mailbox = "${UUID.randomUUID()}@example.test"
        val job = PollJob(gmail, discord, jdbc, TransactionTemplate(DataSourceTransactionManager(source)))
        val mail = Mail("mail-1", "subject", "preview", "https://mail.google.com/")
        `when`(gmail.profile()).thenReturn(Profile(mailbox, "100"))

        try {
            job.run()
            verifyNoInteractions(discord)
            val start = jdbc.queryForObject(
                "SELECT checked_at FROM gmail_checkpoint WHERE mailbox = ?",
                Long::class.javaObjectType, mailbox,
            )!!
            `when`(gmail.profile()).thenReturn(Profile(mailbox, "200"))
            `when`(gmail.inboxSince(start - 1)).thenReturn(listOf(mail.id, mail.id))
            `when`(gmail.message(mail.id)).thenReturn(mail)
            doThrow(DiscordSendException(429, Duration.ofSeconds(1))).doNothing().`when`(discord).send(mail)
            assertThrows(RuntimeException::class.java) { job.run() }

            `when`(gmail.changes("200")).thenReturn(Changes("300", listOf(mail.id)))
            job.run()
            verify(discord, times(2)).send(mail)
            assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM discord_outbox WHERE mailbox = ? AND sent",
                Int::class.javaObjectType, mailbox,
            )!!)
        } finally {
            jdbc.update("DELETE FROM discord_outbox WHERE mailbox = ?", mailbox)
            jdbc.update("DELETE FROM gmail_checkpoint WHERE mailbox = ?", mailbox)
        }
    }
}

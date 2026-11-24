package bot

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.mock.env.MockEnvironment
import org.springframework.web.server.ResponseStatusException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class GmailPushJobTest {
    private val address = "mailbox@example.com"
    private val topic = "projects/test/topics/gmail"
    private val gmail = mock(GmailClient::class.java)
    private val discord = mock(DiscordClient::class.java)
    private val checkpoint = MemoryCheckpointStore()
    private val job = GmailPushJob(
        gmail, discord, checkpoint, Clock.fixed(Instant.ofEpochSecond(1234), ZoneOffset.UTC),
        MockEnvironment().withProperty("GMAIL_ADDRESS", address).withProperty("GMAIL_TOPIC", topic),
    )

    @Test
    fun `first renewal saves the watch baseline without sending past mail`() {
        `when`(gmail.profile()).thenReturn(Profile(address, "9"))
        `when`(gmail.watch(topic)).thenReturn("10")
        job.renew()
        verifyNoInteractions(discord)
        assertEquals(Checkpoint(address, "10", 1234, "1"), checkpoint.load())
    }

    @Test
    fun `push sends new mail and ignores stale or unrelated events`() {
        checkpoint.save(Checkpoint(address, "10", 1200), null)
        val mail = Mail("a", "subject", "preview", "https://mail.google.com/")
        `when`(gmail.profile()).thenReturn(Profile(address, "20"))
        `when`(gmail.changes("10")).thenReturn(Changes("20", listOf("a", "a")))
        `when`(gmail.message("a")).thenReturn(mail)
        job.onPush("other@example.com", "20")
        job.onPush(address, "20")
        job.onPush(address, "19")
        verify(discord).send(mail)
        assertEquals("20", checkpoint.load()?.historyId)
    }

    @Test
    fun `failed delivery leaves the previous position for PubSub retry`() {
        checkpoint.save(Checkpoint(address, "10", 1200), null)
        val mail = Mail("a", "subject", "preview", "https://mail.google.com/")
        `when`(gmail.profile()).thenReturn(Profile(address, "20"))
        `when`(gmail.changes("10")).thenReturn(Changes("20", listOf("a")))
        `when`(gmail.message("a")).thenReturn(mail)
        doThrow(DiscordSendException(500, null)).doNothing().`when`(discord).send(mail)
        assertThrows(DiscordSendException::class.java) { job.onPush(address, "20") }
        assertEquals("10", checkpoint.load()?.historyId)
        job.onPush(address, "20")
        assertEquals("20", checkpoint.load()?.historyId)
    }

    @Test
    fun `expiration recovers inbox mail using the previous timestamp`() {
        checkpoint.save(Checkpoint(address, "10", 1200), null)
        `when`(gmail.profile()).thenReturn(Profile(address, "20"))
        `when`(gmail.inboxSince(1199)).thenReturn(emptyList())
        job.onPush(address, "20")
        verify(gmail).inboxSince(1199)
        assertEquals("20", checkpoint.load()?.historyId)
    }

    @Test
    fun `push before initialization requests redelivery`() {
        assertEquals(503, assertThrows(ResponseStatusException::class.java) {
            job.onPush(address, "20")
        }.statusCode.value())
        verifyNoInteractions(gmail, discord)
    }

    private class MemoryCheckpointStore : CheckpointStore {
        private var value: Checkpoint? = null
        override fun load(): Checkpoint? = value
        override fun save(checkpoint: Checkpoint, previousGeneration: String?): Checkpoint {
            check(previousGeneration == value?.generation)
            return checkpoint.copy(generation = ((previousGeneration?.toInt() ?: 0) + 1).toString())
                .also { value = it }
        }
    }
}

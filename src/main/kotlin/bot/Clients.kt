package bot

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.web.util.HtmlUtils
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Base64

data class Profile(val email: String, val historyId: String)
data class Changes(val cursor: String, val ids: List<String>)
data class Mail(val id: String, val subject: String, val snippet: String, val link: String)

class DiscordSendException(val status: Int, val retryAfter: Duration?) :
    RuntimeException("Discord message creation failed (HTTP $status)")

class GmailClient(
    private val mapper: ObjectMapper,
    private val clientId: String,
    private val clientSecret: String,
    private val refreshToken: String,
) {
    private var accessToken: String? = null
    private var expiresAt: Instant = Instant.EPOCH
    private var mailbox: String? = null

    fun profile(): Profile {
        val result = checkNotNull(get("/profile"))
        return Profile(result.requiredText("emailAddress"), result.requiredText("historyId")).also {
            mailbox = it.email
        }
    }

    fun watch(topic: String): String {
        val body = mapper.writeValueAsString(
            mapOf("topicName" to topic, "labelIds" to listOf("INBOX"), "labelFilterBehavior" to "include"),
        )
        val response = http.send(
            request("https://gmail.googleapis.com/gmail/v1/users/me/watch")
                .header("Authorization", "Bearer ${token()}")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        check(response.statusCode() in 200..299) { "Gmail watch failed (HTTP ${response.statusCode()})" }
        return mapper.readTree(response.body()).requiredText("historyId")
    }

    fun changes(cursor: String): Changes? {
        val added = linkedSetOf<String>()
        val inInbox = mutableSetOf<String>()
        var nextPage: String? = null
        do {
            val page = get(
                "/history",
                "startHistoryId" to cursor,
                "historyTypes" to "messageAdded",
                "historyTypes" to "labelAdded",
                "historyTypes" to "labelRemoved",
                "pageToken" to nextPage,
                "maxResults" to "500",
            ) ?: return null

            for (record in page.path("history")) {
                for (addition in record.path("messagesAdded")) {
                    val message = addition.required("message")
                    val id = message.requiredText("id")
                    added.add(id)
                    if (message.path("labelIds").any { it.asText() == "INBOX" }) {
                        inInbox.add(id)
                    }
                }
                val inboxLabelChanges = record.path("labelsAdded").asSequence() +
                    record.path("labelsRemoved").asSequence()
                for (change in inboxLabelChanges) {
                    if (change.path("labelIds").any { it.asText() == "INBOX" }) {
                        inInbox.add(change.required("message").requiredText("id"))
                    }
                }
            }

            nextPage = page.path("nextPageToken").asText().ifBlank { null }
            if (nextPage == null) return Changes(page.requiredText("historyId"), added.filter(inInbox::contains))
        } while (true)
    }

    fun inboxSince(epochSeconds: Long): List<String> {
        val ids = linkedSetOf<String>()
        var nextPage: String? = null
        do {
            val page = checkNotNull(
                get(
                    "/messages",
                    "labelIds" to "INBOX",
                    "q" to "after:$epochSeconds",
                    "pageToken" to nextPage,
                    "maxResults" to "500",
                ),
            )
            page.path("messages").forEach { ids.add(it.requiredText("id")) }
            nextPage = page.path("nextPageToken").asText().ifBlank { null }
        } while (nextPage != null)
        return ids.toList()
    }

    fun message(id: String): Mail? {
        val account = mailbox ?: profile().email
        val result = get(
            "/messages/${encode(id)}",
            "format" to "full",
            "fields" to "snippet,payload(headers(name,value))",
        ) ?: return null
        val subject = result.path("payload").path("headers")
            .firstOrNull { it.path("name").asText().equals("Subject", ignoreCase = true) }
            ?.path("value")?.asText().orEmpty()
        val snippet = HtmlUtils.htmlUnescape(result.path("snippet").asText())
        val link = "https://mail.google.com/mail/u/${encode(account)}/#all/${encode(id)}"
        return Mail(id, subject, snippet, link)
    }

    private fun get(path: String, vararg parameters: Pair<String, String?>): JsonNode? {
        val query = parameters.filter { it.second != null }
            .joinToString("&") { (key, value) -> "${encode(key)}=${encode(checkNotNull(value))}" }
        val url = "https://gmail.googleapis.com/gmail/v1/users/me$path" +
            if (query.isEmpty()) "" else "?$query"
        val response = http.send(
            request(url).header("Authorization", "Bearer ${token()}").GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        if (response.statusCode() == 404) return null
        check(response.statusCode() in 200..299) { "Gmail request failed (HTTP ${response.statusCode()})" }
        return mapper.readTree(response.body())
    }

    private fun token(): String {
        accessToken?.takeIf { Instant.now().isBefore(expiresAt) }?.let { return it }
        val form = mapOf(
            "client_id" to clientId,
            "client_secret" to clientSecret,
            "refresh_token" to refreshToken,
            "grant_type" to "refresh_token",
        ).entries.joinToString("&") { (key, value) -> "$key=${encode(value)}" }
        val response = http.send(
            request("https://oauth2.googleapis.com/token")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        check(response.statusCode() in 200..299) { "Gmail OAuth refresh failed (HTTP ${response.statusCode()})" }
        val result = mapper.readTree(response.body())
        expiresAt = Instant.now().plusSeconds((result.required("expires_in").asLong() - 30).coerceAtLeast(0))
        return result.requiredText("access_token").also { accessToken = it }
    }
}

class DiscordClient(
    private val mapper: ObjectMapper,
    private val botToken: String,
    private val channelId: String,
) {
    fun send(mail: Mail) {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$channelId:${mail.link}".toByteArray())
        val nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(digest).take(25)
        val subject = discordText(mail.subject, 300).ifBlank { "(제목 없음)" }
        val snippet = discordText(mail.snippet, 1200).ifBlank { "(미리보기 없음)" }
        val body = mapper.writeValueAsString(
            mapOf(
                "content" to "$subject\n$snippet\n[Gmail에서 열기](${mail.link})",
                "nonce" to nonce,
                "enforce_nonce" to true,
                "allowed_mentions" to mapOf("parse" to emptyList<String>()),
                "flags" to 4,
            ),
        )
        val response = http.send(
            request("https://discord.com/api/v10/channels/${encode(channelId)}/messages")
                .header("Authorization", "Bot $botToken")
                .header("Content-Type", "application/json")
                .header("User-Agent", "DiscordBot (https://discord.com, 1.0)")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        val status = response.statusCode()
        if (status !in 200..299) {
            val retryAfter = response.headers().firstValue("Retry-After").orElse("")
                .toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
                ?.let { Duration.ofMillis(kotlin.math.ceil(it * 1000).toLong()) }
            throw DiscordSendException(status, retryAfter)
        }
    }

    private fun discordText(text: String, limit: Int): String {
        val normalized = text.replace(Regex("(?U)\\s+"), " ").trim()
        val escaped = buildString {
            for (character in normalized) {
                if (character in "\\`*_~|<>#[]()") append('\\')
                append(character)
            }
        }
        if (escaped.length <= limit) return escaped
        val end = if (Character.isHighSurrogate(escaped[limit - 1])) limit - 1 else limit
        return escaped.substring(0, end).trimEnd('\\') + "…"
    }
}

private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

private fun request(url: String): HttpRequest.Builder =
    HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))

private fun encode(text: String): String = URLEncoder.encode(text, Charsets.UTF_8).replace("+", "%20")

private fun JsonNode.requiredText(field: String): String =
    required(field).asText("").also { check(it.isNotBlank()) { "API response missing $field" } }

package bot

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.context.annotation.Profile
import org.springframework.core.env.Environment
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class BotSettings(
    val gmailAddress: String = "",
    val discordChannelId: String = "",
    val enabled: Boolean = false,
    val gmailClientId: String = "",
    val gmailClientSecret: String = "",
    val gmailRefreshToken: String = "",
    val discordBotToken: String = "",
) {
    fun hasCredentials() = listOf(
        discordChannelId, gmailClientId, gmailClientSecret, gmailRefreshToken, discordBotToken,
    ).all(String::isNotBlank)

    fun view() = BotSettingsView(
        gmailAddress, discordChannelId, enabled, gmailClientId,
        gmailClientSecret.isNotBlank(), gmailRefreshToken.isNotBlank(), discordBotToken.isNotBlank(),
    )
}

data class BotSettingsView(
    val gmailAddress: String,
    val discordChannelId: String,
    val enabled: Boolean,
    val gmailClientId: String,
    val gmailClientSecretConfigured: Boolean,
    val gmailRefreshTokenConfigured: Boolean,
    val discordBotTokenConfigured: Boolean,
)

@Service
class BotSettingsStore(
    private val jdbc: JdbcTemplate,
    private val mapper: ObjectMapper,
    private val environment: Environment,
) {
    private val random = SecureRandom()

    fun load(): BotSettings {
        val encrypted = jdbc.query("SELECT encrypted_config FROM bot_settings WHERE id = 1") { row, _ ->
            row.getString(1)
        }.singleOrNull()
        if (encrypted != null) {
            val bytes = Base64.getDecoder().decode(encrypted)
            val cipher = cipher(Cipher.DECRYPT_MODE, bytes.copyOfRange(0, 12))
            return mapper.readValue(cipher.doFinal(bytes, 12, bytes.size - 12), BotSettings::class.java)
        }
        val fallback = BotSettings(
            discordChannelId = environment.getProperty("DISCORD_CHANNEL_ID").orEmpty(),
            gmailClientId = environment.getProperty("GMAIL_CLIENT_ID").orEmpty(),
            gmailClientSecret = environment.getProperty("GMAIL_CLIENT_SECRET").orEmpty(),
            gmailRefreshToken = environment.getProperty("GMAIL_REFRESH_TOKEN").orEmpty(),
            discordBotToken = environment.getProperty("DISCORD_BOT_TOKEN").orEmpty(),
        )
        return fallback.copy(enabled = fallback.hasCredentials())
    }

    @Transactional
    fun save(update: BotSettings): BotSettingsView {
        jdbc.execute("SELECT pg_advisory_xact_lock(173186402)")
        val previous = load()
        val settings = update.copy(
            gmailAddress = update.gmailAddress.trim(),
            discordChannelId = update.discordChannelId.trim(),
            gmailClientId = update.gmailClientId.trim(),
            gmailClientSecret = update.gmailClientSecret.trim().ifBlank { previous.gmailClientSecret },
            gmailRefreshToken = update.gmailRefreshToken.trim().ifBlank { previous.gmailRefreshToken },
            discordBotToken = update.discordBotToken.trim().ifBlank { previous.discordBotToken },
        )
        if (settings.gmailAddress.isNotEmpty() &&
            !Regex("[^@\\s]+@[^@\\s]+\\.[^@\\s]+").matches(settings.gmailAddress)
        ) invalid("유효한 Gmail 주소를 입력하세요.")
        if (settings.discordChannelId.isNotEmpty() &&
            !Regex("[0-9]{17,20}").matches(settings.discordChannelId)
        ) invalid("디스코드 채널 ID는 17~20자리 숫자여야 합니다.")
        if (settings.enabled && (settings.gmailAddress.isBlank() || !settings.hasCredentials())) {
            invalid("봇을 켜려면 Gmail 주소와 모든 연결 정보를 등록하세요.")
        }
        val iv = ByteArray(12).also(random::nextBytes)
        val encrypted = cipher(Cipher.ENCRYPT_MODE, iv).doFinal(mapper.writeValueAsBytes(settings))
        jdbc.update(
            """
            INSERT INTO bot_settings (id, encrypted_config) VALUES (1, ?)
            ON CONFLICT (id) DO UPDATE SET encrypted_config = EXCLUDED.encrypted_config
            """.trimIndent(),
            Base64.getEncoder().encodeToString(iv + encrypted),
        )
        return settings.view()
    }

    fun validateKey() {
        key()
    }

    private fun cipher(mode: Int, iv: ByteArray): Cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(mode, key(), GCMParameterSpec(128, iv))
    }

    private fun key(): SecretKeySpec {
        val value = environment.getRequiredProperty("BOT_CONFIG_KEY")
        val bytes = Base64.getDecoder().decode(value)
        require(bytes.size == 32) { "BOT_CONFIG_KEY must encode 32 bytes" }
        return SecretKeySpec(bytes, "AES")
    }

    private fun invalid(message: String): Nothing = throw ResponseStatusException(HttpStatus.BAD_REQUEST, message)
}

@Profile("admin")
@RestController
@RequestMapping("/api/bot-config")
class BotSettingsController(
    private val store: BotSettingsStore,
    environment: Environment,
) {
    private val password = environment.getRequiredProperty("BOT_ADMIN_PASSWORD").also {
        require(it.isNotBlank()) { "BOT_ADMIN_PASSWORD is required" }
    }
    private val credentials = "admin:$password".toByteArray(Charsets.UTF_8)

    init {
        store.validateKey()
    }

    @GetMapping
    fun get(@RequestHeader("Authorization", required = false) header: String?): ResponseEntity<BotSettingsView> {
        authorize(header)
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(store.load().view())
    }

    @PutMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun put(
        @RequestHeader("Authorization", required = false) header: String?,
        @RequestBody body: BotSettings,
    ): ResponseEntity<*> {
        authorize(header)
        return try {
            ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(store.save(body))
        } catch (failure: ResponseStatusException) {
            ResponseEntity.status(failure.statusCode).body(mapOf("message" to failure.reason))
        }
    }

    private fun authorize(header: String?) {
        val supplied = try {
            header?.takeIf { it.startsWith("Basic ", ignoreCase = true) }
                ?.substring(6)?.let { Base64.getDecoder().decode(it) }
        } catch (_: IllegalArgumentException) {
            null
        }
        if (supplied == null || !MessageDigest.isEqual(credentials, supplied)) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        }
    }
}

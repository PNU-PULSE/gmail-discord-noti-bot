package bot

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

data class Checkpoint(
    val mailbox: String,
    val historyId: String,
    val checkedAt: Long,
    @get:JsonIgnore val generation: String? = null,
)

interface CheckpointStore {
    fun load(): Checkpoint?
    fun save(checkpoint: Checkpoint, previousGeneration: String?): Checkpoint
}

@Service
class CloudStorageCheckpointStore(
    private val mapper: ObjectMapper,
    @Value("\${CHECKPOINT_BUCKET}") bucket: String,
    @Value("\${CHECKPOINT_OBJECT:gmail-checkpoint.json}") objectName: String,
) : CheckpointStore {
    private val objectUrl = "https://storage.googleapis.com/storage/v1/b/${encode(bucket)}/o/${encode(objectName)}"
    private val uploadUrl = "https://storage.googleapis.com/upload/storage/v1/b/${encode(bucket)}/o" +
        "?uploadType=media&name=${encode(objectName)}"
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private var accessToken: String? = null
    private var refreshAt = Instant.EPOCH

    init {
        require(bucket.isNotBlank() && objectName.isNotBlank()) { "Checkpoint bucket and object must be configured" }
    }

    override fun load(): Checkpoint? {
        val metadata = send(authorizedRequest("$objectUrl?fields=generation").GET())
        if (metadata.statusCode() == 404) return null
        val generation = readJson("read metadata", metadata).generation()

        // Match the metadata's generation so a concurrent replacement cannot pair old metadata with new bytes.
        val media = send(authorizedRequest("$objectUrl?alt=media&ifGenerationMatch=$generation").GET())
        requireSuccess("read data", media)
        return mapper.readValue(media.body(), Checkpoint::class.java).copy(generation = generation)
    }

    override fun save(checkpoint: Checkpoint, previousGeneration: String?): Checkpoint {
        require(previousGeneration == null || generationPattern.matches(previousGeneration))
        val response = send(
            authorizedRequest("$uploadUrl&ifGenerationMatch=${previousGeneration ?: "0"}")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(checkpoint))),
        )
        return checkpoint.copy(generation = readJson("write", response).generation())
    }

    private fun authorizedRequest(url: String): HttpRequest.Builder =
        request(url).header("Authorization", "Bearer ${token()}")

    @Synchronized
    private fun token(): String {
        accessToken?.takeIf { Instant.now().isBefore(refreshAt) }?.let { return it }

        val response = send(
            request("http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/token")
                .header("Metadata-Flavor", "Google")
                .GET(),
        )
        val result = readJson("authenticate", response)
        val expiresIn = result.path("expires_in").asLong()
        require(expiresIn > 0) { "Cloud Run service identity token has invalid expiration" }

        refreshAt = Instant.now().plusSeconds((expiresIn - 30).coerceAtLeast(0))
        return result.path("access_token").asText()
            .also { require(it.isNotBlank()) { "Cloud Run service identity token is missing" } }
            .also { accessToken = it }
    }

    private fun request(url: String): HttpRequest.Builder =
        HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))

    private fun send(builder: HttpRequest.Builder): HttpResponse<ByteArray> =
        client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())

    private fun readJson(operation: String, response: HttpResponse<ByteArray>): JsonNode {
        requireSuccess(operation, response)
        return mapper.readTree(response.body())
    }

    private fun requireSuccess(operation: String, response: HttpResponse<ByteArray>) {
        check(response.statusCode() in 200..299) {
            "Checkpoint $operation failed (HTTP ${response.statusCode()})"
        }
    }

    private fun JsonNode.generation(): String = path("generation").asText()
        .also { check(generationPattern.matches(it)) { "Checkpoint object generation is invalid" } }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")

    private companion object {
        val generationPattern = Regex("[1-9][0-9]*")
    }
}

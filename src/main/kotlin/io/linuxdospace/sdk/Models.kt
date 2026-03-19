package io.linuxdospace.sdk

import java.time.Instant

/**
 * MailMessage is one parsed mail event from the token stream.
 */
data class MailMessage(
    val address: String,
    val sender: String,
    val recipients: List<String>,
    val receivedAt: Instant,
    val subject: String,
    val messageId: String?,
    val date: Instant?,
    val fromHeader: String,
    val toHeader: String,
    val ccHeader: String,
    val replyToHeader: String,
    val fromAddresses: List<String>,
    val toAddresses: List<String>,
    val ccAddresses: List<String>,
    val replyToAddresses: List<String>,
    val text: String,
    val html: String,
    val headers: Map<String, String>,
    val raw: String,
    val rawBytes: ByteArray
)

/**
 * ClientOptions stores runtime options used by Client.
 */
data class ClientOptions(
    val baseUrl: String = "https://api.linuxdo.space",
    val connectTimeoutMillis: Long = 10_000,
    val streamReadTimeoutMillis: Long = 30_000,
    val reconnectDelayMillis: Long = 300
) {
    init {
        require(baseUrl.isNotBlank()) { "baseUrl must not be blank" }
        require(connectTimeoutMillis > 0) { "connectTimeoutMillis must be > 0" }
        require(streamReadTimeoutMillis > 0) { "streamReadTimeoutMillis must be > 0" }
        require(reconnectDelayMillis > 0) { "reconnectDelayMillis must be > 0" }

        val normalized = baseUrl.trim().lowercase()
        require(normalized.startsWith("https://") || normalized.startsWith("http://")) {
            "baseUrl must use http or https"
        }
        if (normalized.startsWith("http://")) {
            val hostPortPath = normalized.removePrefix("http://")
            val hostPort = hostPortPath.substringBefore("/")
            val host = hostPort.substringBefore(":")
            val local = host == "localhost"
                || host == "127.0.0.1"
                || host == "::1"
                || host.endsWith(".localhost")
            require(local) { "non-local baseUrl must use https" }
        }
    }
}

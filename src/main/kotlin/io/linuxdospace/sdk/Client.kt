package io.linuxdospace.sdk

import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern

/**
 * Client maintains one upstream NDJSON stream and performs local routing.
 */
class Client(
    token: String,
    private val options: ClientOptions = ClientOptions()
) : AutoCloseable {
    private val token: String = token.trim()
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(options.connectTimeoutMillis))
        .build()

    private val closed = AtomicBoolean(false)
    private val connected = AtomicBoolean(false)
    private val lock = Any()
    private val fullListeners = CopyOnWriteArrayList<ClientSubscription>()
    private val bindingsBySuffix = linkedMapOf<String, MutableList<Binding>>()
    private val initialReady = CountDownLatch(1)
    private var initialError: LinuxDoSpaceException? = null
    private var fatalError: LinuxDoSpaceException? = null
    @Volatile
    private var activeStream: InputStream? = null

    private val readerThread = Thread({ runLoop() }, "LinuxDoSpaceKotlinClient").apply {
        isDaemon = true
        start()
    }

    init {
        require(this.token.isNotBlank()) { "token must not be empty" }
        awaitInitialConnection()
    }

    fun isConnected(): Boolean = connected.get() && !closed.get() && fatalError == null

    /**
     * listen registers one full-stream queue listener.
     */
    fun listen(): ClientSubscription {
        checkFatalError()
        lateinit var subscription: ClientSubscription
        subscription = ClientSubscription {
            fullListeners.remove(subscription)
        }
        fullListeners.add(subscription)
        return subscription
    }

    /**
     * bindExact registers one exact mailbox binding.
     */
    fun bindExact(prefix: String, suffix: Suffix, allowOverlap: Boolean = false): MailBox {
        require(prefix.isNotBlank()) { "prefix must not be blank" }
        checkFatalError()
        return registerBinding(
            mode = "exact",
            suffix = suffix.value,
            prefix = prefix.trim().lowercase(Locale.ROOT),
            pattern = null,
            allowOverlap = allowOverlap
        )
    }

    /**
     * bindPattern registers one regex mailbox binding.
     */
    fun bindPattern(pattern: String, suffix: Suffix, allowOverlap: Boolean = false): MailBox {
        require(pattern.isNotBlank()) { "pattern must not be blank" }
        checkFatalError()
        return registerBinding(
            mode = "pattern",
            suffix = suffix.value,
            prefix = null,
            pattern = Pattern.compile(pattern.trim()),
            allowOverlap = allowOverlap
        )
    }

    /**
     * route resolves current local mailbox matches for message.address.
     */
    fun route(message: MailMessage): List<MailBox> {
        val parts = splitAddress(message.address.lowercase(Locale.ROOT)) ?: return emptyList()
        val matches = mutableListOf<MailBox>()
        synchronized(lock) {
            val chain = bindingsBySuffix[parts.suffix] ?: emptyList()
            for (binding in chain) {
                if (!binding.mailBox.matches(parts.localPart)) {
                    continue
                }
                matches += binding.mailBox
                if (!binding.mailBox.allowOverlap) {
                    break
                }
            }
        }
        return matches.toList()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        connected.set(false)
        activeStream?.let { stream ->
            activeStream = null
            try {
                stream.close()
            } catch (_: Exception) {
                // Best effort to unblock the reader thread.
            }
        }
        fullListeners.forEach { it.offer(QueueSignals.CLOSE) }
        synchronized(lock) {
            bindingsBySuffix.values.forEach { chain ->
                chain.forEach { binding ->
                    binding.mailBox.offerControl(QueueSignals.CLOSE)
                }
            }
            bindingsBySuffix.clear()
        }
        readerThread.join(options.connectTimeoutMillis + 1_000)
    }

    private fun awaitInitialConnection() {
        val ready = initialReady.await(options.connectTimeoutMillis + 1_000, TimeUnit.MILLISECONDS)
        if (!ready) {
            close()
            throw StreamException("timed out while opening LinuxDoSpace stream")
        }
        initialError?.let { error ->
            close()
            throw error
        }
    }

    private fun runLoop() {
        var connectedOnce = false
        while (!closed.get()) {
            try {
                consumeOnce()
                connected.set(false)
                connectedOnce = true
            } catch (error: AuthenticationException) {
                connected.set(false)
                fatalError = error
                if (!connectedOnce) {
                    initialError = error
                    initialReady.countDown()
                }
                broadcastControl(error)
                return
            } catch (error: LinuxDoSpaceException) {
                connected.set(false)
                if (!connectedOnce) {
                    initialError = error
                    initialReady.countDown()
                    return
                }
                sleepQuietly(options.reconnectDelayMillis)
            } catch (error: Exception) {
                connected.set(false)
                val wrapped = StreamException("unexpected stream failure", error)
                if (!connectedOnce) {
                    initialError = wrapped
                    initialReady.countDown()
                    return
                }
                sleepQuietly(options.reconnectDelayMillis)
            }
        }
    }

    private fun consumeOnce() {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("${options.baseUrl.trimEnd('/')}/v1/token/email/stream"))
            .timeout(Duration.ofMillis(options.streamReadTimeoutMillis))
            .header("Accept", "application/x-ndjson")
            .header("Authorization", "Bearer $token")
            .GET()
            .build()

        try {
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
            when (response.statusCode()) {
                401, 403 -> throw AuthenticationException("api token was rejected by LinuxDoSpace backend")
                200 -> Unit
                else -> throw StreamException("unexpected stream status code: ${response.statusCode()}")
            }

            connected.set(true)
            initialReady.countDown()

            response.body().use { stream ->
                activeStream = stream
                BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { reader ->
                    while (!closed.get()) {
                        val line = reader.readLine() ?: break
                        if (line.isBlank()) {
                            continue
                        }
                        handleEventLine(line)
                    }
                }
                activeStream = null
            }
        } catch (error: LinuxDoSpaceException) {
            throw error
        } catch (error: Exception) {
            throw StreamException("failed to consume stream", error)
        }
    }

    private fun handleEventLine(line: String) {
        val event = parseFlatJson(line)
        val type = event["type"] as? String ?: ""
        if (type == "ready" || type == "heartbeat") {
            return
        }
        if (type != "mail") {
            return
        }
        val envelope = parseEnvelope(event)
        dispatchEnvelope(envelope)
    }

    private fun parseEnvelope(event: Map<String, Any>): MailEnvelope {
        val sender = (event["original_envelope_from"] as? String).orEmpty()
        val recipients = (event["original_recipients"] as? List<*>)
            ?.map { (it as? String).orEmpty().trim().lowercase(Locale.ROOT) }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
        val receivedAt = parseIsoInstant((event["received_at"] as? String).orEmpty())
        val rawBase64 = (event["raw_message_base64"] as? String).orEmpty()
        if (rawBase64.isBlank()) {
            throw StreamException("mail event missing raw_message_base64")
        }
        val rawBytes = Base64.getDecoder().decode(rawBase64)
        val mime = parseMime(rawBytes)
        return MailEnvelope(
            sender = sender,
            recipients = recipients,
            receivedAt = receivedAt,
            subject = mime.subject,
            messageId = mime.messageId,
            date = mime.date,
            fromHeader = mime.fromHeader,
            toHeader = mime.toHeader,
            ccHeader = mime.ccHeader,
            replyToHeader = mime.replyToHeader,
            fromAddresses = mime.fromAddresses,
            toAddresses = mime.toAddresses,
            ccAddresses = mime.ccAddresses,
            replyToAddresses = mime.replyToAddresses,
            text = mime.text,
            html = mime.html,
            headers = mime.headers,
            raw = mime.raw,
            rawBytes = rawBytes
        )
    }

    private fun parseMime(rawBytes: ByteArray): ParsedMime {
        val raw = rawBytes.toString(StandardCharsets.UTF_8)
        val headers = linkedMapOf<String, String>()
        BufferedReader(InputStreamReader(ByteArrayInputStream(rawBytes), StandardCharsets.UTF_8)).use { reader ->
            var current: String? = null
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) {
                    break
                }
                if ((line.startsWith(" ") || line.startsWith("\t")) && current != null) {
                    headers[current] = headers.getValue(current) + " " + line.trim()
                    continue
                }
                val index = line.indexOf(':')
                if (index <= 0) {
                    continue
                }
                val key = line.substring(0, index).trim()
                val value = line.substring(index + 1).trim()
                headers[key] = value
                current = key
            }
        }
        val body = if (raw.contains("\r\n\r\n")) raw.substring(raw.indexOf("\r\n\r\n") + 4) else ""
        val contentType = headers["Content-Type"].orEmpty().lowercase(Locale.ROOT)
        val text = if (contentType.contains("text/html")) "" else body
        val html = if (contentType.contains("text/html")) body else ""

        return ParsedMime(
            subject = headers["Subject"].orEmpty(),
            messageId = headers["Message-ID"],
            date = parseMailDate(headers["Date"]),
            fromHeader = headers["From"].orEmpty(),
            toHeader = headers["To"].orEmpty(),
            ccHeader = headers["Cc"].orEmpty(),
            replyToHeader = headers["Reply-To"].orEmpty(),
            fromAddresses = extractAddresses(headers["From"]),
            toAddresses = extractAddresses(headers["To"]),
            ccAddresses = extractAddresses(headers["Cc"]),
            replyToAddresses = extractAddresses(headers["Reply-To"]),
            text = text,
            html = html,
            headers = headers.toMap(),
            raw = raw
        )
    }

    private fun dispatchEnvelope(envelope: MailEnvelope) {
        val primary = envelope.recipients.firstOrNull().orEmpty()
        broadcastToFull(envelope.toMessage(primary))

        envelope.recipients.toSet().forEach { recipient ->
            dispatchToBindings(recipient, envelope.toMessage(recipient))
        }
    }

    private fun dispatchToBindings(address: String, message: MailMessage) {
        val parts = splitAddress(address) ?: return
        synchronized(lock) {
            val chain = bindingsBySuffix[parts.suffix] ?: emptyList()
            for (binding in chain) {
                if (!binding.mailBox.matches(parts.localPart)) {
                    continue
                }
                binding.mailBox.offerMessage(message)
                if (!binding.mailBox.allowOverlap) {
                    break
                }
            }
        }
    }

    private fun broadcastToFull(message: MailMessage) {
        fullListeners.forEach { it.offer(message) }
    }

    private fun broadcastControl(error: LinuxDoSpaceException) {
        fullListeners.forEach { it.offer(error) }
        synchronized(lock) {
            bindingsBySuffix.values.forEach { chain ->
                chain.forEach { binding ->
                    binding.mailBox.offerControl(error)
                }
            }
        }
    }

    private fun registerBinding(
        mode: String,
        suffix: String,
        prefix: String?,
        pattern: Pattern?,
        allowOverlap: Boolean
    ): MailBox {
        lateinit var mailBox: MailBox
        val unregister: () -> Unit = {
            synchronized(lock) {
                val chain = bindingsBySuffix[suffix] ?: return@synchronized
                chain.removeIf { it.mailBox === mailBox }
                if (chain.isEmpty()) {
                    bindingsBySuffix.remove(suffix)
                }
            }
        }
        mailBox = MailBox(mode, suffix, prefix, pattern, allowOverlap, unregister)
        synchronized(lock) {
            bindingsBySuffix.computeIfAbsent(suffix) { mutableListOf() }
                .add(Binding(mailBox))
        }
        return mailBox
    }

    private fun checkFatalError() {
        fatalError?.let { throw it }
    }

    private fun sleepQuietly(millis: Long) {
        if (closed.get()) {
            return
        }
        try {
            Thread.sleep(millis)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun splitAddress(address: String): AddressParts? {
        val at = address.indexOf('@')
        if (at <= 0 || at >= address.lastIndex) {
            return null
        }
        return AddressParts(
            localPart = address.substring(0, at),
            suffix = address.substring(at + 1)
        )
    }

    private fun parseIsoInstant(value: String): Instant {
        if (value.isBlank()) {
            return Instant.now()
        }
        return Instant.parse(value)
    }

    private fun parseMailDate(value: String?): Instant? {
        if (value.isNullOrBlank()) {
            return null
        }
        return try {
            ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
        } catch (_: Exception) {
            null
        }
    }

    private fun extractAddresses(rawHeader: String?): List<String> {
        if (rawHeader.isNullOrBlank()) {
            return emptyList()
        }
        return rawHeader.split(",")
            .map { item ->
                val trimmed = item.trim()
                val open = trimmed.indexOf('<')
                val close = trimmed.indexOf('>')
                val address = if (open >= 0 && close > open) {
                    trimmed.substring(open + 1, close).trim()
                } else {
                    trimmed
                }
                address.lowercase(Locale.ROOT)
            }
            .filter { it.isNotBlank() }
    }

    /**
     * parseFlatJson parses current event shapes:
     * - simple string fields
     * - string array fields
     */
    private fun parseFlatJson(line: String): Map<String, Any> {
        val result = linkedMapOf<String, Any>()
        var index = 0
        while (index < line.length) {
            val keyStart = line.indexOf('"', index)
            if (keyStart < 0) {
                break
            }
            val keyEnd = findStringEnd(line, keyStart + 1)
            if (keyEnd < 0) {
                break
            }
            val key = unescape(line.substring(keyStart + 1, keyEnd))
            val colon = line.indexOf(':', keyEnd + 1)
            if (colon < 0) {
                break
            }
            val valueStart = skipSpaces(line, colon + 1)
            if (valueStart >= line.length) {
                break
            }
            when (line[valueStart]) {
                '"' -> {
                    val valueEnd = findStringEnd(line, valueStart + 1)
                    if (valueEnd < 0) break
                    result[key] = unescape(line.substring(valueStart + 1, valueEnd))
                    index = valueEnd + 1
                }
                '[' -> {
                    val arrayEnd = findArrayEnd(line, valueStart + 1)
                    if (arrayEnd < 0) break
                    result[key] = parseStringArray(line.substring(valueStart + 1, arrayEnd))
                    index = arrayEnd + 1
                }
                else -> {
                    val scalarEnd = findScalarEnd(line, valueStart)
                    result[key] = line.substring(valueStart, scalarEnd).trim()
                    index = scalarEnd
                }
            }
        }
        return result
    }

    private fun parseStringArray(rawArray: String): List<String> {
        val result = mutableListOf<String>()
        var index = 0
        while (index < rawArray.length) {
            val start = rawArray.indexOf('"', index)
            if (start < 0) break
            val end = findStringEnd(rawArray, start + 1)
            if (end < 0) break
            result += unescape(rawArray.substring(start + 1, end))
            index = end + 1
        }
        return result
    }

    private fun findStringEnd(value: String, fromIndex: Int): Int {
        var i = fromIndex
        while (i < value.length) {
            if (value[i] == '"' && value.getOrNull(i - 1) != '\\') {
                return i
            }
            i++
        }
        return -1
    }

    private fun findArrayEnd(value: String, fromIndex: Int): Int {
        var i = fromIndex
        while (i < value.length) {
            if (value[i] == ']') {
                return i
            }
            i++
        }
        return -1
    }

    private fun findScalarEnd(value: String, fromIndex: Int): Int {
        var i = fromIndex
        while (i < value.length) {
            if (value[i] == ',' || value[i] == '}') {
                return i
            }
            i++
        }
        return value.length
    }

    private fun skipSpaces(value: String, fromIndex: Int): Int {
        var i = fromIndex
        while (i < value.length && value[i].isWhitespace()) {
            i++
        }
        return i
    }

    private fun unescape(value: String): String {
        return value
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .replace("\\/", "/")
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
    }

    private data class Binding(val mailBox: MailBox)
    private data class AddressParts(val localPart: String, val suffix: String)

    private data class ParsedMime(
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
        val raw: String
    )

    private data class MailEnvelope(
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
    ) {
        fun toMessage(address: String): MailMessage {
            return MailMessage(
                address = address,
                sender = sender,
                recipients = recipients,
                receivedAt = receivedAt,
                subject = subject,
                messageId = messageId,
                date = date,
                fromHeader = fromHeader,
                toHeader = toHeader,
                ccHeader = ccHeader,
                replyToHeader = replyToHeader,
                fromAddresses = fromAddresses,
                toAddresses = toAddresses,
                ccAddresses = ccAddresses,
                replyToAddresses = replyToAddresses,
                text = text,
                html = html,
                headers = headers,
                raw = raw,
                rawBytes = rawBytes
            )
        }
    }
}

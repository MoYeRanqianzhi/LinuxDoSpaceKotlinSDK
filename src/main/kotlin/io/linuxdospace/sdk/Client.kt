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
 * Client maintains one upstream NDJSON stream, performs local routing, and
 * keeps the backend-side dynamic `-mail<suffix>` filter list aligned with the
 * semantic mailbox bindings currently registered in this process.
 */
class Client(
    token: String,
    private val options: ClientOptions = ClientOptions()
) : AutoCloseable {
    private companion object {
        const val STREAM_FILTERS_PATH = "/v1/token/email/filters"
    }

    private val token: String = token.trim()
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(options.connectTimeoutMillis))
        .build()

    private val closed = AtomicBoolean(false)
    private val connected = AtomicBoolean(false)
    private val lock = Any()
    private val filterSyncLock = Any()
    private val fullListeners = CopyOnWriteArrayList<ClientSubscription>()
    private val bindingsBySuffix = linkedMapOf<String, MutableList<Binding>>()
    private val initialReady = CountDownLatch(1)
    private var initialError: LinuxDoSpaceException? = null
    private var fatalError: LinuxDoSpaceException? = null
    private var syncedMailboxSuffixFragments: List<String>? = null
    @Volatile
    private var ownerUsername: String? = null
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
        ensureUsable()
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
    fun bindExact(prefix: String, suffix: String, allowOverlap: Boolean = false): MailBox {
        require(prefix.isNotBlank()) { "prefix must not be blank" }
        ensureUsable()
        return registerBinding(
            mode = "exact",
            suffix = normalizeLiteralSuffix(suffix),
            prefix = prefix.trim().lowercase(Locale.ROOT),
            pattern = null,
            allowOverlap = allowOverlap
        )
    }

    /**
     * bindExact registers one exact mailbox binding using semantic suffix
     * constants such as `Suffix.LINUXDO_SPACE`, whose current canonical
     * concrete suffix is `<owner_username>-mail.linuxdo.space`.
     */
    fun bindExact(prefix: String, suffix: Suffix, allowOverlap: Boolean = false): MailBox {
        require(prefix.isNotBlank()) { "prefix must not be blank" }
        ensureUsable()
        return registerBinding(
            mode = "exact",
            suffix = resolveBindingSuffix(suffix),
            prefix = prefix.trim().lowercase(Locale.ROOT),
            pattern = null,
            allowOverlap = allowOverlap
        )
    }

    /**
     * bindExact registers one exact mailbox binding using one semantic suffix
     * plus one optional dynamic `-mail<suffix>` fragment.
     */
    fun bindExact(prefix: String, suffix: SemanticSuffix, allowOverlap: Boolean = false): MailBox {
        require(prefix.isNotBlank()) { "prefix must not be blank" }
        ensureUsable()
        return registerBinding(
            mode = "exact",
            suffix = resolveBindingSuffix(suffix),
            prefix = prefix.trim().lowercase(Locale.ROOT),
            pattern = null,
            allowOverlap = allowOverlap
        )
    }

    /**
     * bindPattern registers one regex mailbox binding.
     */
    fun bindPattern(pattern: String, suffix: String, allowOverlap: Boolean = false): MailBox {
        require(pattern.isNotBlank()) { "pattern must not be blank" }
        ensureUsable()
        return registerBinding(
            mode = "pattern",
            suffix = normalizeLiteralSuffix(suffix),
            prefix = null,
            pattern = Pattern.compile(pattern.trim()),
            allowOverlap = allowOverlap
        )
    }

    /**
     * bindPattern registers one regex mailbox binding using semantic suffix
     * constants such as `Suffix.LINUXDO_SPACE`, whose current canonical
     * concrete suffix is `<owner_username>-mail.linuxdo.space`.
     */
    fun bindPattern(pattern: String, suffix: Suffix, allowOverlap: Boolean = false): MailBox {
        require(pattern.isNotBlank()) { "pattern must not be blank" }
        ensureUsable()
        return registerBinding(
            mode = "pattern",
            suffix = resolveBindingSuffix(suffix),
            prefix = null,
            pattern = Pattern.compile(pattern.trim()),
            allowOverlap = allowOverlap
        )
    }

    /**
     * bindPattern registers one regex mailbox binding using one semantic
     * suffix plus one optional dynamic `-mail<suffix>` fragment.
     */
    fun bindPattern(pattern: String, suffix: SemanticSuffix, allowOverlap: Boolean = false): MailBox {
        require(pattern.isNotBlank()) { "pattern must not be blank" }
        ensureUsable()
        return registerBinding(
            mode = "pattern",
            suffix = resolveBindingSuffix(suffix),
            prefix = null,
            pattern = Pattern.compile(pattern.trim()),
            allowOverlap = allowOverlap
        )
    }

    /**
     * route resolves current local mailbox matches for message.address.
     */
    fun route(message: MailMessage): List<MailBox> {
        ensureUsable()
        val matches = mutableListOf<MailBox>()
        synchronized(lock) {
            val parts = splitAddress(message.address.lowercase(Locale.ROOT)) ?: return emptyList()
            val chain = resolveBindingChain(parts)
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
        val listenerSnapshot = fullListeners.toList()
        fullListeners.clear()
        val mailboxSnapshot = mutableListOf<MailBox>()
        synchronized(lock) {
            bindingsBySuffix.values.forEach { chain ->
                chain.forEach { binding ->
                    mailboxSnapshot += binding.mailBox
                }
            }
            bindingsBySuffix.clear()
        }
        syncRemoteMailboxFilters(strict = false)
        activeStream?.let { stream ->
            activeStream = null
            try {
                stream.close()
            } catch (_: Exception) {
                // Best effort to unblock the reader thread.
            }
        }
        readerThread.interrupt()
        listenerSnapshot.forEach { it.close() }
        mailboxSnapshot.forEach { it.close() }
        try {
            readerThread.join(options.connectTimeoutMillis + 1_000)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
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
                if (!closed.get() && initialReady.count > 0L) {
                    throw StreamException("mail stream ended before ready event")
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
        if (type == "ready") {
            handleReadyEvent(event)
            return
        }
        if (type == "heartbeat") {
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
        synchronized(lock) {
            val parts = splitAddress(address) ?: return
            val chain = resolveBindingChain(parts)
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

    /**
     * resolveBindingChain maps actual recipient suffixes back onto semantic
     * `Suffix.LINUXDO_SPACE` registrations when required.
     *
     * This keeps live dispatch and route(message) behavior identical so the
     * caller never needs to know whether the backend projected the legacy
     * owner-root alias or the current canonical `-mail` namespace.
     */
    private fun resolveBindingChain(parts: AddressParts): List<Binding> {
        val direct = bindingsBySuffix[parts.suffix]
        if (!direct.isNullOrEmpty()) {
            return direct
        }

        val normalizedOwnerUsername = ownerUsername.orEmpty().trim().lowercase(Locale.ROOT)
        if (normalizedOwnerUsername.isEmpty()) {
            return emptyList()
        }

        val rootSuffix = Suffix.LINUXDO_SPACE.value
        val semanticLegacySuffix = "$normalizedOwnerUsername.$rootSuffix"
        val semanticMailSuffix = "$normalizedOwnerUsername-mail.$rootSuffix"
        if (parts.suffix != semanticLegacySuffix) {
            return emptyList()
        }
        return bindingsBySuffix[semanticMailSuffix] ?: emptyList()
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
            syncRemoteMailboxFilters(strict = false)
        }
        mailBox = MailBox(mode, suffix, prefix, pattern, allowOverlap, unregister)
        synchronized(lock) {
            bindingsBySuffix.computeIfAbsent(suffix) { mutableListOf() }
                .add(Binding(mailBox))
        }
        try {
            syncRemoteMailboxFilters(strict = true)
        } catch (error: RuntimeException) {
            synchronized(lock) {
                val chain = bindingsBySuffix[suffix]
                chain?.removeIf { it.mailBox === mailBox }
                if (chain != null && chain.isEmpty()) {
                    bindingsBySuffix.remove(suffix)
                }
            }
            syncRemoteMailboxFilters(strict = false)
            throw error
        }
        return mailBox
    }

    private fun handleReadyEvent(event: Map<String, Any>) {
        val normalizedOwnerUsername = (event["owner_username"] as? String).orEmpty().trim().lowercase(Locale.ROOT)
        if (normalizedOwnerUsername.isEmpty()) {
            throw StreamException("ready event did not include owner_username")
        }
        ownerUsername = normalizedOwnerUsername
        initialReady.countDown()
    }

    private fun resolveBindingSuffix(suffix: Suffix): String {
        if (suffix != Suffix.LINUXDO_SPACE) {
            return normalizeLiteralSuffix(suffix.value)
        }
        val normalizedOwnerUsername = ownerUsername.orEmpty().trim().lowercase(Locale.ROOT)
        if (normalizedOwnerUsername.isEmpty()) {
            throw StreamException("stream bootstrap did not provide owner_username required to resolve Suffix.LINUXDO_SPACE")
        }
        return "$normalizedOwnerUsername-mail.${Suffix.LINUXDO_SPACE.value}"
    }

    private fun resolveBindingSuffix(suffix: SemanticSuffix): String {
        if (suffix.base != Suffix.LINUXDO_SPACE) {
            return normalizeLiteralSuffix(suffix.base.value)
        }
        val normalizedOwnerUsername = ownerUsername.orEmpty().trim().lowercase(Locale.ROOT)
        if (normalizedOwnerUsername.isEmpty()) {
            throw StreamException("stream bootstrap did not provide owner_username required to resolve semantic Suffix.LINUXDO_SPACE")
        }
        return "$normalizedOwnerUsername-mail${suffix.mailSuffixFragment}.${suffix.base.value}"
    }

    private fun normalizeLiteralSuffix(suffix: String): String {
        require(suffix.isNotBlank()) { "suffix must not be blank" }
        return suffix.trim().lowercase(Locale.ROOT)
    }

    /**
     * syncRemoteMailboxFilters keeps the backend-side dynamic mail suffix
     * filter list aligned with the owner-specific mailbox suffixes currently
     * registered in this client process.
     */
    private fun syncRemoteMailboxFilters(strict: Boolean) {
        synchronized(filterSyncLock) {
            val normalizedOwnerUsername = ownerUsername.orEmpty().trim().lowercase(Locale.ROOT)
            if (normalizedOwnerUsername.isEmpty()) {
                return
            }

            val fragments = collectRemoteMailboxSuffixFragments(normalizedOwnerUsername)
            if (fragments.isEmpty() && syncedMailboxSuffixFragments == null) {
                return
            }
            if (fragments == syncedMailboxSuffixFragments) {
                return
            }

            val payload = buildFiltersPayload(fragments)
            val request = HttpRequest.newBuilder()
                .uri(URI.create("${options.baseUrl.trimEnd('/')}$STREAM_FILTERS_PATH"))
                .timeout(Duration.ofMillis(options.connectTimeoutMillis))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer $token")
                .PUT(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build()

            try {
                val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                if (response.statusCode() != 200) {
                    val body = response.body().trim()
                    throw StreamException(
                        "unexpected mailbox filter sync status code: ${response.statusCode()}" +
                            if (body.isEmpty()) "" else " body=$body"
                    )
                }
                syncedMailboxSuffixFragments = fragments
            } catch (error: LinuxDoSpaceException) {
                if (strict) {
                    throw error
                }
            } catch (error: Exception) {
                if (strict) {
                    throw StreamException("failed to synchronize remote mailbox filters", error)
                }
            }
        }
    }

    private fun collectRemoteMailboxSuffixFragments(normalizedOwnerUsername: String): List<String> {
        val suffixSnapshot = synchronized(lock) {
            bindingsBySuffix.keys.toList()
        }

        val canonicalPrefix = "$normalizedOwnerUsername-mail"
        val rootMarker = ".${Suffix.LINUXDO_SPACE.value}"
        val fragments = sortedSetOf<String>()
        suffixSnapshot.forEach { suffix ->
            val normalizedSuffix = suffix.trim().lowercase(Locale.ROOT)
            if (!normalizedSuffix.endsWith(rootMarker)) {
                return@forEach
            }
            val label = normalizedSuffix.removeSuffix(rootMarker)
            if ('.' in label || !label.startsWith(canonicalPrefix)) {
                return@forEach
            }
            fragments += label.removePrefix(canonicalPrefix)
        }
        return fragments.toList()
    }

    private fun buildFiltersPayload(fragments: List<String>): String {
        return buildString {
            append("{\"suffixes\":[")
            fragments.forEachIndexed { index, fragment ->
                if (index > 0) {
                    append(',')
                }
                append('"')
                append(fragment)
                append('"')
            }
            append("]}")
        }
    }

    private fun ensureUsable() {
        if (closed.get()) {
            throw LinuxDoSpaceException("client is already closed")
        }
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

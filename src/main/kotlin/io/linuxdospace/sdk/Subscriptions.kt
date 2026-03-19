package io.linuxdospace.sdk

import java.time.Duration
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern

internal object QueueSignals {
    val CLOSE: Any = Any()
}

/**
 * ClientSubscription is one full-stream local queue consumer.
 */
class ClientSubscription internal constructor(
    private val unregister: () -> Unit
) : AutoCloseable {
    private val queue = LinkedBlockingQueue<Any>()
    private val closed = AtomicBoolean(false)
    private val active = AtomicBoolean(false)

    internal fun offer(item: Any) {
        queue.offer(item)
    }

    fun next(timeout: Duration): MailMessage? {
        require(!timeout.isNegative) { "timeout must be zero or positive" }
        if (closed.get()) {
            return null
        }
        check(active.compareAndSet(false, true)) { "subscription already has an active consumer" }
        try {
            val item = if (timeout.isZero) {
                queue.poll()
            } else {
                queue.poll(timeout.toMillis(), TimeUnit.MILLISECONDS)
            } ?: return null
            if (item === QueueSignals.CLOSE) {
                close()
                return null
            }
            if (item is LinuxDoSpaceException) {
                throw item
            }
            return item as MailMessage
        } finally {
            active.set(false)
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            unregister()
            queue.offer(QueueSignals.CLOSE)
        }
    }
}

/**
 * MailBox is one explicit mailbox binding over the shared client stream.
 */
class MailBox internal constructor(
    val mode: String,
    val suffix: String,
    val prefix: String?,
    private val pattern: Pattern?,
    val allowOverlap: Boolean,
    private val unregister: () -> Unit
) : AutoCloseable {
    private val queue = LinkedBlockingQueue<Any>()
    private val closed = AtomicBoolean(false)
    private val active = AtomicBoolean(false)

    val address: String?
        get() = if (mode == "exact" && prefix != null) "$prefix@$suffix" else null

    fun patternText(): String? = pattern?.pattern()

    fun isClosed(): Boolean = closed.get()

    /**
     * next activates mailbox queue delivery and reads one message.
     */
    fun next(timeout: Duration): MailMessage? {
        require(!timeout.isNegative) { "timeout must be zero or positive" }
        if (closed.get()) {
            return null
        }
        check(active.compareAndSet(false, true)) { "mailbox already has an active listener" }
        try {
            val item = if (timeout.isZero) {
                queue.poll()
            } else {
                queue.poll(timeout.toMillis(), TimeUnit.MILLISECONDS)
            } ?: return null
            if (item === QueueSignals.CLOSE) {
                close()
                return null
            }
            if (item is LinuxDoSpaceException) {
                throw item
            }
            return item as MailMessage
        } finally {
            active.set(false)
        }
    }

    internal fun matches(localPart: String): Boolean {
        return if (mode == "exact") {
            prefix == localPart
        } else {
            pattern?.matcher(localPart)?.matches() == true
        }
    }

    internal fun offerMessage(message: MailMessage) {
        if (closed.get()) {
            return
        }
        if (!active.get()) {
            // No pre-listen backlog by protocol contract.
            return
        }
        queue.offer(message)
    }

    internal fun offerControl(item: Any) {
        queue.offer(item)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            unregister()
            queue.offer(QueueSignals.CLOSE)
        }
    }
}

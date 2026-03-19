package io.linuxdospace.sdk

/**
 * LinuxDoSpaceException is the base runtime error for the Kotlin SDK.
 */
open class LinuxDoSpaceException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)

/**
 * AuthenticationException means backend token authentication failed.
 */
class AuthenticationException(
    message: String,
    cause: Throwable? = null
) : LinuxDoSpaceException(message, cause)

/**
 * StreamException means stream transport or stream decoding failed.
 */
class StreamException(
    message: String,
    cause: Throwable? = null
) : LinuxDoSpaceException(message, cause)

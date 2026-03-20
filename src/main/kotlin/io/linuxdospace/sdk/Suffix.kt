package io.linuxdospace.sdk

/**
 * Suffix defines supported mailbox namespace suffix constants.
 *
 * `LINUXDO_SPACE` is semantic rather than literal: SDK bindings resolve it to
 * `<owner_username>.linuxdo.space` after the stream `ready` event provides
 * `owner_username`.
 */
enum class Suffix(val value: String) {
    LINUXDO_SPACE("linuxdo.space")
}

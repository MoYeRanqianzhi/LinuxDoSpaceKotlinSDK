package io.linuxdospace.sdk

/**
 * Suffix defines supported mailbox namespace suffix constants.
 *
 * `LINUXDO_SPACE` is semantic rather than literal: SDK bindings internally
 * resolve to the current token owner's canonical `-mail` namespace under
 * `linuxdo.space`, while still accepting the legacy owner-root alias when the
 * backend projects it.
 */
enum class Suffix(val value: String) {
    LINUXDO_SPACE("linuxdo.space");

    /**
     * withSuffix derives one semantic dynamic mail namespace rooted under this
     * semantic suffix.
     */
    fun withSuffix(fragment: String): SemanticSuffix {
        return SemanticSuffix(this, normalizeMailSuffixFragment(fragment))
    }
}

package io.linuxdospace.sdk

/**
 * SemanticSuffix represents one semantic mailbox root plus one optional
 * dynamic fragment appended after the fixed `-mail` label.
 *
 * Examples under `Suffix.LINUXDO_SPACE`:
 *
 * - empty fragment -> `<owner_username>-mail.linuxdo.space`
 * - `"foo"` -> `<owner_username>-mailfoo.linuxdo.space`
 */
class SemanticSuffix internal constructor(
    val base: Suffix,
    val mailSuffixFragment: String
) {
    fun withSuffix(fragment: String): SemanticSuffix {
        return SemanticSuffix(base, normalizeMailSuffixFragment(fragment))
    }

    override fun toString(): String = base.value

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is SemanticSuffix) {
            return false
        }
        return base == other.base && mailSuffixFragment == other.mailSuffixFragment
    }

    override fun hashCode(): Int {
        var result = base.hashCode()
        result = 31 * result + mailSuffixFragment.hashCode()
        return result
    }
}

internal fun normalizeMailSuffixFragment(rawFragment: String): String {
    val value = rawFragment.trim().lowercase()
    if (value.isEmpty()) {
        return ""
    }

    val normalized = StringBuilder()
    var lastWasDash = false
    for (character in value) {
        if (character in 'a'..'z' || character in '0'..'9') {
            normalized.append(character)
            lastWasDash = false
            continue
        }
        if (!lastWasDash) {
            normalized.append('-')
            lastWasDash = true
        }
    }

    val collapsed = normalized.toString().trim('-')
    require(collapsed.isNotEmpty()) { "fragment does not contain any valid dns characters" }
    require('.' !in collapsed) { "fragment must stay inside one dns label" }
    require(collapsed.length <= 48) { "fragment must be 48 characters or fewer" }
    return collapsed
}

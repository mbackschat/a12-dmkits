package io.github.mbackschat.a12.dm.bridge

/**
 * A construct the bridge does not map yet — distinct from a rule rulekit *rejects*.
 *
 * Keeping the two apart is the whole point of this class. A rejection is a finding: rulekit looked at the
 * rule and said no, and it arrives as the [IllegalArgumentException] a DSL builder throws. This is the
 * absence of an opinion, and it must never be counted as either a pass or a finding.
 */
internal open class UnmappedConstructException(message: String) : RuntimeException(message) {

    private companion object {
        private const val serialVersionUID: Long = 1L
    }
}

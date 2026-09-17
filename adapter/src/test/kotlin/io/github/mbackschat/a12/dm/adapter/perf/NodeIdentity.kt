package io.github.mbackschat.a12.dm.adapter.perf

/**
 * The Node runtime a checker timing may be recorded on — [JdkIdentity]'s counterpart for the JS host.
 *
 * **It exists because H5's per-number gate requires a pinned runtime and this project had no Node pin at all.**
 * [H5](../../../../../../../../../../../docs/PORTABLE-CONSISTENCY-PROPOSAL.md) takes every checker budget through
 * IF137's six points, of which (2) is "runner/runtime pinned". The estate's Node discovery selected the NEWEST
 * installed version — `.last()` over `~/.nvm/versions/node` — so a lane wired to it would have measured v26.5.0
 * today and something else after the next `nvm install`, with nothing in the record naming which. A JS engine
 * change moves absolute numbers for the same reason a JIT change does on the JVM, which is the measured basis
 * [JdkIdentity] was created on.
 *
 * **Major, not the patch version.** `24.16.0` and `24.18.0` differ by a security train, and pinning the patch
 * would redden this lane on every `nvm install` while separating nothing that moves a number. A major carries
 * V8's own version, which is the thing that does.
 *
 * **The required major is not a constant here, and that is load-bearing.** It arrives from `checkerNodeMajor` in
 * `gradle.properties`, the same value the owning Gradle task resolves its `node` binary by. A copy in this file
 * would make the gate unfalsifiably green in the one way that matters: the task would resolve the runtime it was
 * configured for and this class would approve the runtime it was compiled for, and nothing would notice when
 * those stopped being the same. One value cannot disagree with itself. (Why that value is 24 belongs beside it,
 * in the property's own comment, for the same single-statement reason.)
 *
 * **Refusing beats disarming, the same asymmetry [JdkIdentity] documents.** A second Node major means the lane
 * was handed a runtime its Gradle task did not ask for — a misconfiguration to fix, not a state to tolerate —
 * so the refusal names the version it got and the pin it wanted.
 */
internal data class NodeIdentity(val major: String) {

    val text: String get() = "Node $major"

    internal companion object {

        /**
         * Parse `v24.18.0` into its major, keeping an unrecognised string as itself.
         *
         * An unparseable version is NAMED rather than bucketed as unknown-and-accepted, because the one thing a
         * pin must never do is pass a runtime it could not identify.
         */
        fun of(reportedVersion: String): NodeIdentity =
            NodeIdentity(MAJOR.find(reportedVersion.trim())?.groupValues?.get(1) ?: reportedVersion.trim())

        /** The refusal, worded so the reader can act on it without finding this file. */
        fun refusalFor(node: NodeIdentity, requiredMajor: String): String? {
            if (node.major == requiredMajor) {
                return null
            }
            return "[perf] REFUSING to measure on ${node.text} — the checker's Node lanes run on Node " +
                "$requiredMajor and nothing else, because H5 takes every budget through IF137's per-number " +
                "gate and point (2) of it is a pinned runtime; a V8 change moves absolute numbers by more than " +
                "any tolerance absorbs and reads as a code regression. Fix the runtime rather than the lane: " +
                "the owning Gradle task resolves checkerNodeMajor through gradle/node-tools.gradle, so a " +
                "mismatch here means that resolution was bypassed. Install Node $requiredMajor " +
                "(nvm install $requiredMajor), or export its bin directory on PATH."
        }

        /**
         * The identity the gate JUDGES — normally the runtime the lane probed; a test may substitute one.
         *
         * Without this seam the gate is unfalsifiable in exactly the way a cold review named for [JdkIdentity]
         * on 2026-08-24: on the only runtime the task resolves, the check is provably a no-op and deleting it
         * fails nothing. A substituting test must restore it.
         */
        internal var judged: NodeIdentity? = null

        /** Called by every Node lane before it records, so no lane can measure around the pin. */
        fun requireSupported(reportedVersion: String, requiredMajor: String) {
            refusalFor(judged ?: of(reportedVersion), requiredMajor)?.let { throw IllegalStateException(it) }
        }

        private val MAJOR = Regex("""^v?(\d+)\.""")
    }
}

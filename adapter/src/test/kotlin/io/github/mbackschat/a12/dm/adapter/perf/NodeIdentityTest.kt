package io.github.mbackschat.a12.dm.adapter.perf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The falsifying control for the Node runtime pin — what makes [NodeIdentity] a gate rather than a comment.
 *
 * A pin that is never handed a wrong runtime is indistinguishable from a comment, which is the point a cold
 * review made about [JdkIdentity] on 2026-08-24. These cases hand `requireSupported` a wrong runtime directly —
 * both its inputs are parameters, so no substitution seam is needed and none exists — and the last two drive the
 * production entry point itself, so the pin goes red when it stops refusing.
 */
class NodeIdentityTest {

    @Test
    fun `the pinned major is accepted`() {
        assertNull(NodeIdentity.refusalFor(NodeIdentity.of("v24.18.0"), "24"))
    }

    @Test
    fun `a different major is refused and named`() {
        val refusal = NodeIdentity.refusalFor(NodeIdentity.of("v26.5.0"), "24")
        assertTrue(refusal != null && refusal.contains("Node 26"), "the refusal must name what it got: $refusal")
        assertTrue(refusal!!.contains("nvm install 24"), "the refusal must say how to fix it: $refusal")
    }

    /**
     * A version string the parse cannot read keeps ITSELF as the identity, so it can never equal the required
     * major and is therefore refused. The alternative — bucketing it as unknown — would pass a runtime nobody
     * identified, which is the one thing a pin must not do.
     */
    @Test
    fun `an unreadable version is kept verbatim and therefore refused`() {
        assertEquals("nightly-build", NodeIdentity.of(" nightly-build ").major)
        assertTrue(NodeIdentity.refusalFor(NodeIdentity.of("nightly-build"), "24") != null)
    }

    /**
     * The gate's own entry point, on the path a lane takes — not a seam standing in for it.
     *
     * Both of `requireSupported`'s inputs are parameters, so this drives exactly the call
     * `CheckerColdNodePerfTest` makes and needs nothing substituted. The version it passes is one this
     * machine can genuinely produce: the estate's unpinned Node discovery selects the newest install, which
     * was v26.5.0 when the pin was built.
     */
    @Test
    fun `requireSupported throws on the runtime a lane would actually be handed`() {
        val thrown = assertThrows<IllegalStateException> { NodeIdentity.requireSupported("v26.5.0", "24") }
        assertTrue(
            thrown.message!!.contains("Node 26"),
            "the refusal must name the runtime it was handed: ${thrown.message}",
        )
    }

    /** The other half of the gate, on the same path: the pinned runtime passes through without throwing. */
    @Test
    fun `requireSupported passes the pinned runtime`() {
        NodeIdentity.requireSupported("v24.18.0", "24")
    }
}

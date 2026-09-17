package io.github.mbackschat.a12.dm.adapter.perf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The falsifying control for the Node runtime pin — what makes [NodeIdentity] a gate rather than a comment.
 *
 * On the only runtime the owning Gradle task resolves, `requireSupported` is provably a no-op and deleting it
 * fails nothing, which is the point a cold review made about [JdkIdentity] on 2026-08-24. These cases drive the
 * refusal from the substitution seam instead, so the pin has a test that goes red when it stops refusing.
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

    @Test
    fun `requireSupported throws on the substituted identity`() {
        NodeIdentity.judged = NodeIdentity("22")
        try {
            val thrown = assertThrows<IllegalStateException> {
                // The reported version is the PINNED one, so only the substitution can make this throw — which
                // is what proves the seam is what the gate reads.
                NodeIdentity.requireSupported("v24.18.0", "24")
            }
            assertTrue(
                thrown.message!!.contains("Node 22"),
                "the refusal must name the judged runtime: ${thrown.message}",
            )
        } finally {
            NodeIdentity.judged = null
        }
    }
}

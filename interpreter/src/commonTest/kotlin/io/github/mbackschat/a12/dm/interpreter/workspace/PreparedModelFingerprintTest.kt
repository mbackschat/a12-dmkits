package io.github.mbackschat.a12.dm.interpreter.workspace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class PreparedModelFingerprintTest {

    @Test
    fun framedWorkspaceFingerprintHasAPortableGoldenValue() {
        val sources = listOf(
            source("ab", "c"),
            source("a", "bc"),
        )

        assertEquals(
            "e3e9eea412f7ff7d638d2a72526057871481d941d5e6ae85eace1870f52f023f",
            PreparedModelFingerprint.workspace("entry", sources),
        )
        assertEquals(
            PreparedModelFingerprint.workspace("entry", sources),
            PreparedModelFingerprint.workspace("entry", sources.reversed()),
        )
    }

    @Test
    fun framingSeparatesAProvenNaiveRecordBoundaryCollision() {
        val first = listOf(
            FingerprintSource("a", "b" + "1".repeat(63)),
            FingerprintSource("bc", "2".repeat(64)),
        )
        val second = listOf(
            FingerprintSource("ab", "1".repeat(63) + "b"),
            FingerprintSource("c", "2".repeat(64)),
        )

        assertEquals(
            first.joinToString("") { it.modelId + it.sha256 },
            second.joinToString("") { it.modelId + it.sha256 },
            "the negative control must collide when record boundaries are not framed",
        )
        assertNotEquals(
            PreparedModelFingerprint.workspace("entry", first),
            PreparedModelFingerprint.workspace("entry", second),
        )
    }

    @Test
    fun expandedAndWorkspaceInputModesAreDomainSeparated() {
        assertEquals(
            "acc3cf25e470abcc64ab5c237d338659bac97bd42cfcdad089158d601af2c5f8",
            PreparedModelFingerprint.expanded(Sha256.hex("abc")),
        )
        assertNotEquals(
            PreparedModelFingerprint.workspace(
                "entry",
                listOf(source("entry", "abc")),
            ),
            PreparedModelFingerprint.expanded(Sha256.hex("abc")),
        )
    }

    @Test
    fun workspaceFingerprintOrdersModelIdsByUnsignedUtf8Bytes() {
        val bmpPrivateUse = "\uE000"
        val supplementary = "\uD800\uDC00"
        assertEquals(
            listOf(supplementary, bmpPrivateUse),
            listOf(bmpPrivateUse, supplementary).sorted(),
            "the negative control proves Kotlin UTF-16 ordering differs from unsigned UTF-8 ordering",
        )

        assertEquals(
            "32f5c51a94efbb40b4036e649e64445e7b5a6284e6c3593bb03c03f485422944",
            PreparedModelFingerprint.workspace(
                "entry",
                listOf(source(supplementary, supplementary), source(bmpPrivateUse, bmpPrivateUse)),
            ),
        )
    }

    @Test
    fun entryAndPreparationContractAreFingerprintInputs() {
        val sources = listOf(source("source", "{}"))

        assertNotEquals(
            PreparedModelFingerprint.workspace("first", sources),
            PreparedModelFingerprint.workspace("second", sources),
        )
        assertNotEquals(
            PreparedModelFingerprint.workspace("first", sources, preparationContract = "v1"),
            PreparedModelFingerprint.workspace("first", sources, preparationContract = "v2"),
        )
    }

    private fun source(modelId: String, text: String): FingerprintSource =
        FingerprintSource(modelId, Sha256.hex(text))
}

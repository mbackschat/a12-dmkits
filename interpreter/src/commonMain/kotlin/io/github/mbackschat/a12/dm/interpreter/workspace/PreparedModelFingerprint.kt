package io.github.mbackschat.a12.dm.interpreter.workspace

/** Exact source identity used only while framing a prepared-model fingerprint. */
internal data class FingerprintSource(
    val modelId: String,
    val sha256: String,
)

/**
 * Domain-separated fingerprint over the source digests admission already computed from the exact input bytes.
 * Every variable-length field is length-framed, so source boundaries, entry identity, preparation contract, and
 * input mode remain unambiguous without copying the prepared closure into a second aggregate byte buffer.
 */
internal object PreparedModelFingerprint {
    private const val DOMAIN = "a12-interpreter-prepared-model-fingerprint"
    private const val PREPARATION_CONTRACT = "v1"

    fun workspace(
        entryModelId: String,
        sources: List<FingerprintSource>,
        preparationContract: String = PREPARATION_CONTRACT,
    ): String {
        require(sources.map(FingerprintSource::modelId).distinct().size == sources.size) {
            "prepared-model fingerprint sources must have unique model ids"
        }
        require(sources.all { SOURCE_DIGEST.matches(it.sha256) }) {
            "prepared-model fingerprint source digests must be lowercase SHA-256 values"
        }
        val ordered = sources
            .map {
                EncodedSource(
                    source = it,
                    modelIdBytes = it.modelId.encodeToByteArray(throwOnInvalidSequence = true),
                )
            }
            .sortedWith { left, right -> compareUnsignedBytes(left.modelIdBytes, right.modelIdBytes) }
        return fingerprint(preparationContract, "workspace") {
            frame(entryModelId)
            frame(ordered.size.toString())
            ordered.forEach { encoded ->
                frame(encoded.source.modelId)
                frame(encoded.source.sha256)
            }
        }
    }

    fun expanded(
        sourceSha256: String,
        preparationContract: String = PREPARATION_CONTRACT,
    ): String {
        require(SOURCE_DIGEST.matches(sourceSha256)) {
            "prepared-model fingerprint source digest must be a lowercase SHA-256 value"
        }
        return fingerprint(preparationContract, "expanded") {
            frame(sourceSha256)
        }
    }

    private fun fingerprint(
        preparationContract: String,
        mode: String,
        body: FramedText.() -> Unit,
    ): String {
        val framed = FramedText()
        with(framed) {
            frame(DOMAIN)
            frame(preparationContract)
            frame(mode)
            body()
        }
        return Sha256.hex(framed.toString())
    }

    private class FramedText {
        private val value = StringBuilder()

        fun frame(text: String) {
            val byteCount = checkNotNull(strictUtf8ByteCount(text)) {
                "fingerprint text must contain only Unicode scalar values"
            }
            value.append(byteCount).append(':').append(text)
        }

        override fun toString(): String = value.toString()
    }

    private data class EncodedSource(
        val source: FingerprintSource,
        val modelIdBytes: ByteArray,
    )

    private val SOURCE_DIGEST = Regex("[0-9a-f]{64}")
}

private fun compareUnsignedBytes(left: ByteArray, right: ByteArray): Int {
    val shared = minOf(left.size, right.size)
    for (index in 0 until shared) {
        val comparison = (left[index].toInt() and 0xff).compareTo(right[index].toInt() and 0xff)
        if (comparison != 0) {
            return comparison
        }
    }
    return left.size.compareTo(right.size)
}

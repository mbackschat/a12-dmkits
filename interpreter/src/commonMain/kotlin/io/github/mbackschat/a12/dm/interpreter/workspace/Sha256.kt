package io.github.mbackschat.a12.dm.interpreter.workspace

/**
 * Small commonMain SHA-256 implementation for exact source identity and prepared-model fingerprint framing.
 * JVM and JavaScript therefore compute the same values without a host crypto API or a new runtime dependency.
 */
internal object Sha256 {
    private val roundConstants = intArrayOf(
        0x428a2f98, 0x71374491, 0xb5c0fbcf.toInt(), 0xe9b5dba5.toInt(),
        0x3956c25b, 0x59f111f1, 0x923f82a4.toInt(), 0xab1c5ed5.toInt(),
        0xd807aa98.toInt(), 0x12835b01, 0x243185be, 0x550c7dc3,
        0x72be5d74, 0x80deb1fe.toInt(), 0x9bdc06a7.toInt(), 0xc19bf174.toInt(),
        0xe49b69c1.toInt(), 0xefbe4786.toInt(), 0x0fc19dc6, 0x240ca1cc,
        0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152.toInt(), 0xa831c66d.toInt(), 0xb00327c8.toInt(), 0xbf597fc7.toInt(),
        0xc6e00bf3.toInt(), 0xd5a79147.toInt(), 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
        0x650a7354, 0x766a0abb, 0x81c2c92e.toInt(), 0x92722c85.toInt(),
        0xa2bfe8a1.toInt(), 0xa81a664b.toInt(), 0xc24b8b70.toInt(), 0xc76c51a3.toInt(),
        0xd192e819.toInt(), 0xd6990624.toInt(), 0xf40e3585.toInt(), 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5,
        0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814.toInt(), 0x8cc70208.toInt(),
        0x90befffa.toInt(), 0xa4506ceb.toInt(), 0xbef9a3f7.toInt(), 0xc67178f2.toInt(),
    )

    fun hex(text: String): String = hex(text.encodeToByteArray(throwOnInvalidSequence = true))

    fun hex(bytes: ByteArray): String {
        val state = intArrayOf(
            0x6a09e667,
            0xbb67ae85.toInt(),
            0x3c6ef372,
            0xa54ff53a.toInt(),
            0x510e527f,
            0x9b05688c.toInt(),
            0x1f83d9ab,
            0x5be0cd19,
        )
        val padded = pad(bytes)
        val schedule = IntArray(64)
        var offset = 0
        while (offset < padded.size) {
            for (index in 0 until 16) {
                val cursor = offset + index * 4
                schedule[index] =
                    ((padded[cursor].toInt() and 0xff) shl 24) or
                    ((padded[cursor + 1].toInt() and 0xff) shl 16) or
                    ((padded[cursor + 2].toInt() and 0xff) shl 8) or
                    (padded[cursor + 3].toInt() and 0xff)
            }
            for (index in 16 until schedule.size) {
                val left = schedule[index - 15]
                val right = schedule[index - 2]
                val sigma0 = rotateRight(left, 7) xor rotateRight(left, 18) xor (left ushr 3)
                val sigma1 = rotateRight(right, 17) xor rotateRight(right, 19) xor (right ushr 10)
                schedule[index] = schedule[index - 16] + sigma0 + schedule[index - 7] + sigma1
            }
            compress(state, schedule)
            offset += 64
        }
        return buildString(64) {
            for (word in state) {
                for (shift in 28 downTo 0 step 4) {
                    append(HEX[(word ushr shift) and 0x0f])
                }
            }
        }
    }

    private fun pad(bytes: ByteArray): ByteArray {
        val paddedSize = ((bytes.size + 9 + 63) / 64) * 64
        val padded = ByteArray(paddedSize)
        bytes.copyInto(padded)
        padded[bytes.size] = 0x80.toByte()
        val bitLength = bytes.size.toLong() * 8L
        for (index in 0 until 8) {
            padded[padded.lastIndex - index] = (bitLength ushr (index * 8)).toByte()
        }
        return padded
    }

    private fun compress(state: IntArray, schedule: IntArray) {
        var a = state[0]
        var b = state[1]
        var c = state[2]
        var d = state[3]
        var e = state[4]
        var f = state[5]
        var g = state[6]
        var h = state[7]

        for (index in schedule.indices) {
            val sum1 = rotateRight(e, 6) xor rotateRight(e, 11) xor rotateRight(e, 25)
            val choose = (e and f) xor (e.inv() and g)
            val temporary1 = h + sum1 + choose + roundConstants[index] + schedule[index]
            val sum0 = rotateRight(a, 2) xor rotateRight(a, 13) xor rotateRight(a, 22)
            val majority = (a and b) xor (a and c) xor (b and c)
            val temporary2 = sum0 + majority

            h = g
            g = f
            f = e
            e = d + temporary1
            d = c
            c = b
            b = a
            a = temporary1 + temporary2
        }

        state[0] += a
        state[1] += b
        state[2] += c
        state[3] += d
        state[4] += e
        state[5] += f
        state[6] += g
        state[7] += h
    }

    private fun rotateRight(value: Int, distance: Int): Int =
        (value ushr distance) or (value shl (32 - distance))

    private const val HEX = "0123456789abcdef"
}

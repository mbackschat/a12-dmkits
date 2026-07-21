package io.github.mbackschat.a12.dm.adapter.perf

internal enum class SynthesizedProfileScenario(
    val leafGroup: String,
    val complexity: Complexity,
    val defaultSmallScale: Int,
) {
    COMPACT("/G1/G2/G3/G4/G5/G6", Complexity.PAIRWISE, 100),
    BREADTH_SHAPED("/G1/G17/G18/G19", Complexity.LINEAR, 100),
    DEPTH_SHAPED("/G1/G7/G8/G9/G10/G11", Complexity.LINEAR, 100);

    val id: String = name.lowercase().replace('_', '-')
    val fileName: String = "$id.json"

    init {
        require(leafGroup.startsWith('/')) { "$id: leaf group must be an absolute path" }
        require(defaultSmallScale > 0) { "$id: default small scale must be positive" }
    }

    companion object {
        val all: List<SynthesizedProfileScenario> = entries.toList()

        fun named(id: String): SynthesizedProfileScenario = entries.singleOrNull { it.id == id }
            ?: throw IllegalArgumentException(
                "unknown synthesized profile '$id'; expected one of ${entries.joinToString { it.id }}",
            )
    }
}

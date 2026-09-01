package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.mgmtp.a12.kernel.md.document.apiV2.immutable.GroupInstanceV2
import java.math.BigDecimal

/**
 * The normalized APPLIED EXTENT of a repeatable group chain — the projection the separate-destination topology
 * laws compare across dynamic Groovy, generated Java, and the standalone interpreter.
 *
 * **Why a shared projection rather than one per test.** A cell probe answers "what is at this address"; these
 * laws ask "which rows exist at all", and that question only has an answer once the WHOLE extent is read. The
 * family now spans one and two repeatable levels over Number and String targets (a12-kernel-lean
 * `EXP-2026-08-25-01`, `SPEC-2026-08-26-01/-02/-03`), so the walk is written once and each law supplies only its
 * chain and the cells it cares about.
 *
 * **The two walkers are deliberately NOT one.** [ofKernel] reads A12's own [GroupInstanceV2] domain type;
 * [ofJson] reads the canonical Document JSON the interpreter's public boundary encodes, where a repeating group
 * is an array and an empty row is `{}`. Collapsing them onto one representation would make the two engines agree
 * by construction — the differential's whole value is that two independent readings produce the same text.
 *
 * Rendering, for chain `[Order, Outer, Inner]` and cells `[Amount]`:
 * ```
 * Order=1 | Outer=3 | Outer[1] Inner=2 | Inner[1,1] Amount=- | Inner[1,2] Amount=() | Outer[2] Inner=0 | …
 * ```
 * A non-leaf row line carries its child count, a leaf row line carries its cells, and per cell `-` is absent,
 * `()` present-but-valueless, and anything else the stored value in plain form.
 */
internal object AppliedTopology {

    private val JSON = ObjectMapper()

    /** The projection off the kernel's applied document. [chain] starts at the root group name. */
    fun ofKernel(applied: GroupInstanceV2, chain: List<String>, cells: List<String>): String =
        project(applied, chain, cells, ::kernelRows, ::kernelCell)

    /** The same projection off the canonical Document JSON the interpreter's public boundary encodes. */
    fun ofJson(encoded: String, chain: List<String>, cells: List<String>): String =
        project(JSON.readTree(encoded), chain, cells, ::jsonRows, ::jsonCell)

    private fun <N> project(
        document: N,
        chain: List<String>,
        cells: List<String>,
        rows: (N, String) -> List<N>,
        cell: (N, String) -> String,
    ): String {
        require(chain.size >= 2) { "a topology projection needs a root group and at least one repeatable level" }
        val parts = mutableListOf<String>()
        val rootRows = rows(document, chain[0])
        parts += "${chain[0]}=${rootRows.size}"
        if (rootRows.isNotEmpty()) {
            parts += "${chain[1]}=${rows(rootRows[0], chain[1]).size}"
            descend(rootRows[0], 1, emptyList(), chain, cells, rows, cell, parts)
        }
        return parts.joinToString(" | ")
    }

    private fun <N> descend(
        parent: N,
        level: Int,
        address: List<Int>,
        chain: List<String>,
        cells: List<String>,
        rows: (N, String) -> List<N>,
        cell: (N, String) -> String,
        parts: MutableList<String>,
    ) {
        val name = chain[level]
        rows(parent, name).forEachIndexed { index, row ->
            val here = address + (index + 1)
            val label = "$name[${here.joinToString(",")}]"
            if (level == chain.lastIndex) {
                parts += "$label ${cells.joinToString(" ") { "$it=${cell(row, it)}" }}"
            } else {
                parts += "$label ${chain[level + 1]}=${rows(row, chain[level + 1]).size}"
                descend(row, level + 1, here, chain, cells, rows, cell, parts)
            }
        }
    }

    private fun kernelRows(node: GroupInstanceV2, name: String): List<GroupInstanceV2> {
        val repetitions = node.directSubgroup(name) ?: return emptyList()
        return (1..repetitions.size()).map { repetitions.get(it) }
    }

    private fun kernelCell(row: GroupInstanceV2, name: String): String {
        val field = row.directField(name) ?: return ABSENT
        val value = field.value() ?: return PRESENT_EMPTY
        return if (value is BigDecimal) value.toPlainString() else value.toString()
    }

    private fun jsonRows(node: JsonNode, name: String): List<JsonNode> {
        val child = node.path(name)
        return when {
            child.isArray -> child.toList()
            child.isMissingNode -> emptyList()
            else -> listOf(child)
        }
    }

    private fun jsonCell(row: JsonNode, name: String): String {
        if (!row.has(name)) return ABSENT
        val node = row.get(name)
        return if (node.isNull) PRESENT_EMPTY else node.asText()
    }

    private const val ABSENT = "-"
    private const val PRESENT_EMPTY = "()"
}

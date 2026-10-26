package io.github.mbackschat.a12.dm.consistency.syntax

/** Recognition outcomes distinguish a required terminal from an unresolved choice (KF493). */
enum class SyntaxFailureKind { MATCH, DECISION, LEXICAL }

internal data class Recognition(
    val failure: ShapeFailure?,
    val calls: List<CallShape>,
    val comparisons: List<ComparisonShape>,
    val operations: List<OperationShape>,
    val fieldReads: List<FieldValueRead>,
    val completedSteps: Map<Int, Int>,
    val unfinishedAt: Int?,
    val sites: List<SyntaxSite>,
    val entities: List<RecognizedEntity>,
    /** The condition's connective structure; null for an operation, and for a condition that failed. */
    val tree: ConditionNode?,
)

internal data class RecognizedEntity(
    val site: SyntaxSite.Completion,
    val path: List<Token>,
    val indexKey: Boolean,
    val inFilter: Boolean,
    val indexed: Boolean,
    val filtered: Boolean,
    val category: Token?,
) {
    /** Whether the `$` outer marker opens the path. */
    val outerMarked: Boolean get() = path.first().text == OUTER_MARKER

    /** The path as written, without its outer marker. */
    val writtenPath: String get() = normalizedPath(path.dropWhile { it.text == OUTER_MARKER })
}

internal interface ConditionSite

/** Source coordinates identify occurrences; recognition order is independent of editor spans. */
internal sealed interface SyntaxSite : ConditionSite {
    data class Completion(val kind: Kind, val start: Int, val end: Int) : SyntaxSite
    data class FilterEntry(val start: Int, val end: Int, val path: List<Token>,
        val indexed: Boolean, val indexKey: Boolean) : SyntaxSite
    data class FilterExit(val entry: FilterEntry, val end: Int) : SyntaxSite
    data class Operation(val shape: OperationShape) : SyntaxSite
    data class Comparison(val shape: ComparisonShape) : SyntaxSite
    data class FieldRead(val read: FieldValueRead) : SyntaxSite
    data class DateArgument(val operator: String, val tokens: List<Token>, val inFilter: Boolean) : SyntaxSite
    data class Connective(val first: Token, val current: Token, val end: Int) : SyntaxSite
    data class Failure(val verdict: ShapeFailure) : SyntaxSite

    enum class Kind {
        PREAMBLE, PATH_STEP, ENTITY, INDEX,
        CALL, FORM_NAME, CONSTANT,
    }
}

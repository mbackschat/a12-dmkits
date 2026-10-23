package io.github.mbackschat.a12.dm.consistency.syntax

/** Completed operations retain grouping and child identity for KF43/KF45's numeric gates. */
internal sealed interface OperationShape {
    val at: Int
    val end: Int

    data class Literal(override val at: Int, override val end: Int) : OperationShape

    data class Read(val read: FieldValueRead) : OperationShape {
        override val at get() = read.at
        override val end get() = read.end
    }

    data class Arithmetic(val left: OperationShape, val operator: Token, val right: OperationShape) : OperationShape {
        override val at get() = left.at
        override val end get() = right.end
    }

    data class Group(override val at: Int, override val end: Int, val child: OperationShape) : OperationShape

    data class Call(val call: CallShape, val operands: List<OperationShape?>) : OperationShape {
        override val at get() = call.at
        override val end get() = call.end
    }
}

/** The completed operation spanning exactly [tokens], comments aside; the last one completed where several do. */
internal fun List<OperationShape>.spanning(tokens: List<Token>): OperationShape? {
    val first = tokens.firstOrNull { it.kind != TokenKind.COMMENT }
    val last = tokens.lastOrNull { it.kind != TokenKind.COMMENT }
    return lastOrNull { it.at == first?.start && it.end == last?.end }
}

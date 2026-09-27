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

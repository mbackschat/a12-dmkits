package io.github.mbackschat.a12.dm.interpreter.load

internal class InvalidPreparedModelSourceException(
    val path: String,
    message: String,
) : IllegalArgumentException(message)

internal class UnpreparedModelResidueException(
    val path: String,
    val residue: String,
    message: String,
) : IllegalArgumentException(message)

internal class UnsupportedDatePrecisionException(
    val path: String,
    val precision: String,
) : IllegalArgumentException(
    "unsupported date precision '$precision' at '$path'; this interpreter release supports FULL and DAY_OPTIONAL",
)

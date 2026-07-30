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

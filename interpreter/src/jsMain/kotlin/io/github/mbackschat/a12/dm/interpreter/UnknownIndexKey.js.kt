package io.github.mbackschat.a12.dm.interpreter

// A12's JavaScript runtime publishes a number index's key as a JavaScript number (`FieldInstanceValue`).
internal actual fun publishedIndexValue(key: UnknownIndexKey.Decimal): Any = key.plain.toDouble()

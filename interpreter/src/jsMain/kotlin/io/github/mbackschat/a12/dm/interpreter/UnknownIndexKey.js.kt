package io.github.mbackschat.a12.dm.interpreter

import kotlin.js.Date

// A12's JavaScript runtime publishes a number index's key as a JavaScript number and a date index's as a `Date`
// (`FieldInstanceValue`). A `Date` is mutable, so every read gets its own.
internal actual fun publishedIndexValue(key: UnknownIndexKey.Decimal): Any = key.plain.toDouble()

internal actual fun publishedIndexValue(key: UnknownIndexKey.Instant): Any = Date(key.epochMillis.toDouble())

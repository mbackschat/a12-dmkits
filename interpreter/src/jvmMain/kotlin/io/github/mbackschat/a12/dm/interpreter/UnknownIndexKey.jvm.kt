package io.github.mbackschat.a12.dm.interpreter

import java.math.BigDecimal
import java.util.Date

internal actual fun publishedIndexValue(key: UnknownIndexKey.Decimal): Any = BigDecimal(key.plain)

// A `Date` is mutable, so every read gets its own.
internal actual fun publishedIndexValue(key: UnknownIndexKey.Instant): Any = Date(key.epochMillis)

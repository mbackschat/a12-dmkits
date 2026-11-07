package io.github.mbackschat.a12.dm.interpreter

import java.math.BigDecimal

internal actual fun publishedIndexValue(key: UnknownIndexKey.Decimal): Any = BigDecimal(key.plain)

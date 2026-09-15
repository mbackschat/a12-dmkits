package io.github.mbackschat.a12.dm.consistency

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * A member A12 binds to a `boolean`, absent when it is missing or is not a JSON boolean.
 *
 * Three states rather than two, deliberately: every caller distinguishes a flag written `false` from one the
 * model never wrote, because A12's own defaults differ per member and a reader collapsing them would have to
 * know each default to stay correct.
 */
internal fun JsonObject.flag(member: String): Boolean? = (this[member] as? JsonPrimitive)?.booleanOrNull

package io.github.mbackschat.a12.dm.consistency

import kotlinx.serialization.json.JsonObject

/**
 * A member A12 binds to a primitive `boolean`, read the way A12's reader binds it ([configFlag]); `false` when
 * the member is absent, which is every such member's declared default. **A null result is the answer A12 has no
 * binding for**, and a law asking this reader must then say nothing: the model is never built, so no coded
 * class exists ([unbindableFlag] reports that, through [fieldTypeBindingFindings]).
 *
 * Measured on every such member of a field declaration ([KERNEL-FINDINGS KF569],
 * `adapter.laws.CheckerDeclarationAdmissionAgreementTest`).
 */
internal fun JsonObject.flag(member: String): Boolean? = when (configFlag(this[member])) {
    ConfigFlag.ON -> true
    ConfigFlag.OFF -> false
    ConfigFlag.UNREADABLE -> null
}

/** Whether [member] holds a value A12's `boolean` reader refuses outright, so the model never loads. */
internal fun JsonObject.unbindableFlag(member: String): Boolean = configFlag(this[member]) == ConfigFlag.UNREADABLE

package io.github.mbackschat.a12.dm.consistency

/**
 * That a computation computes into a FIELD.
 *
 * A rule's error target and a computation's computed field are both a relative path to a declared element,
 * they are read by the same reader and they resolve the same way — so an implementation naturally judges them
 * alike, and A12 does not. A rule may report on a group, fixed or repeatable; a computation may not compute
 * into one ([KERNEL-FINDINGS KF329], `rulekit.validate.laws.ComputedFieldTargetKindLawsTest`). Both rows are
 * measured, because either alone licenses the wrong rule for the other slot.
 *
 * **It is reported as `CK_MODEL_NOT_BUILDABLE` rather than under A12's own key, and the measurement is what
 * decides that.** `MVK_GROUP_AS_CALCULATED_FIELD` exists in A12's vocabulary, but the Kernel's BUILDER refuses
 * a group-valued computed field before any consistency check runs, so that key is never what a consumer would
 * have seen — the same tier, and for the same reason, as a computed field that resolves to nothing. Reporting
 * the `MVK_*` key here would claim a verdict A12 never reaches.
 *
 * **The shape exists only in stored DM-JSON**, which is why the kits carry no such family: their computed
 * field slot is a typed field handle, so `MVK_GROUP_AS_CALCULATED_FIELD` is `erased` there. The member this
 * reads is an ordinary string, and a group path is one edit away.
 */
fun PreparedModel.computedTargetKindFindings(): List<Finding> = declaredReferences()
    .filter { it.role == ReferenceRole.COMPUTED_TARGET }
    .mapNotNull { declared ->
        val path = (declared.target as? Reference.Resolved)?.path ?: return@mapNotNull null
        val element = elements.firstOrNull { it.path == path } ?: return@mapNotNull null
        if (element.declaredKind != DeclaredKind.GROUP) {
            return@mapNotNull null
        }
        Finding(
            FindingCategory.A12_CONSTRAINT,
            FindingSeverity.ERROR,
            CK_MODEL_NOT_BUILDABLE,
            "in `computedFieldRelPath`: `$path` is a group, and a computation writes its result into a " +
                "FIELD — the Kernel cannot build a model whose computed field names a group",
            declared.declaredAt,
        )
    }

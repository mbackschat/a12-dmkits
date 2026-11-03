package io.github.mbackschat.a12.dm.consistency

/**
 * The sentences A12's validation-model converter states about names: a blank name, and a name a sibling already
 * carries ([KERNEL-FINDINGS KF652]).
 *
 * Kernel 31.1.1 states each without a class and leaves the declaration, with everything beneath it, out of the model
 * it goes on to check ([ConversionDrops]); 30.8.1 threw instead. So these findings justify rejection and withhold
 * nothing: every other family judges the declarations that are kept.
 *
 * **Blank is Java's `isBlank`, and a name A12 reads as absent counts.** The empty name, a space, a tab, a line
 * break and an em space are blank; a no-break space is not, so it reaches the ordinary name gates instead. An
 * absent or `null` name is read as no name.
 *
 * **A collision spans KINDS and stops at the group.** Two rules of one name in one group, a rule named after a
 * sibling field and one named after a sibling group all collide; the same name in two different groups does not
 * (`ElementNameUniquenessLawsTest`). It is case-sensitive, unlike the `vk_vk` prefix law on the same member, so
 * `Probe` beside `probe` collides with nothing. The first of the two is kept and judged; the sentence names the
 * later one, which is left out.
 *
 * **A12 states a sentence once per subject, not once per declaration** ([sentenceSubject]).
 */
internal fun TrackedFactBase.elementNameFindings(): List<Finding> = conversionDrops().causes.distinctBy(::sentenceSubject).map { cause ->
    val element = cause.element
    when (cause.reason) {
        ConversionReason.ROOT_GROUP_NAME_MISSING -> sentence(
            RK_ROOT_GROUP_NAME_MISSING,
            "this root group's name is blank, so A12 states \"Root group must have a name.\" and checks the model " +
                "without it or anything beneath it",
            element.path,
        )
        ConversionReason.ELEMENT_NAME_MISSING -> sentence(
            RK_ELEMENT_NAME_MISSING,
            "a ${element.kind} in this group has a blank name, so A12 states \"Element must have a name.\" and checks " +
                "the model without that ${element.kind} or anything beneath it",
            element.path.removeSuffix("/${element.name}"),
        )
        ConversionReason.CHILD_NAME_DUPLICATE -> sentence(
            RK_CHILD_NAME_DUPLICATE,
            "an earlier declaration in this group already carries the name `${element.name}`, so A12 states that two " +
                "children may not share it and checks the model without this ${element.kind} or anything beneath it",
            element.path,
        )
    }
}

/**
 * What makes two of the converter's sentences one. A12's code-generation consistency rule collects the sentences in
 * a hash set of notifications, which are equal when their text and source are, and the source is the declaration a
 * sentence is about: the holding group for a blank name, and for a shared name the converted later child, which is
 * equal to another of its kind at its path. So two later rules of a name are one sentence, a later group and a later
 * rule of it are two, and two blank names in one group are one (measured on kernel 31.1.1:
 * `adapter.laws.CheckerConverterSentenceAgreementTest`, KERNEL-FINDINGS KF652).
 */
private fun sentenceSubject(cause: ConversionCause): Any = when (cause.reason) {
    ConversionReason.ROOT_GROUP_NAME_MISSING -> cause.key
    ConversionReason.ELEMENT_NAME_MISSING -> cause.element.path.removeSuffix("/${cause.element.name}")
    ConversionReason.CHILD_NAME_DUPLICATE -> cause.element.path to cause.element.kind
}

private fun sentence(code: String, explain: String, at: String) =
    Finding(FindingCategory.A12_CONSTRAINT, FindingSeverity.ERROR, code, explain, at)

internal const val RK_ROOT_GROUP_NAME_MISSING = "RK_ROOT_GROUP_NAME_MISSING"
internal const val RK_ELEMENT_NAME_MISSING = "RK_ELEMENT_NAME_MISSING"
internal const val RK_CHILD_NAME_DUPLICATE = "RK_CHILD_NAME_DUPLICATE"
internal const val RK_COMPUTED_FIELD_NOT_SPECIFIED = "RK_COMPUTED_FIELD_NOT_SPECIFIED"
internal const val RK_COMPUTED_FIELD_NOT_FOUND = "RK_COMPUTED_FIELD_NOT_FOUND"
internal const val RK_TYPE_DEFINITION_NOT_FOUND = "RK_TYPE_DEFINITION_NOT_FOUND"

/**
 * The codes of A12's converter sentences. A12 states them and checks on, so none of them withholds a later stage
 * ([KERNEL-FINDINGS KF652]: a cycle beside a blank-named field is still reported).
 */
internal val CONVERTER_SENTENCE_CODES = setOf(
    RK_ROOT_GROUP_NAME_MISSING, RK_ELEMENT_NAME_MISSING, RK_CHILD_NAME_DUPLICATE,
    RK_COMPUTED_FIELD_NOT_SPECIFIED, RK_COMPUTED_FIELD_NOT_FOUND, RK_TYPE_DEFINITION_NOT_FOUND,
)

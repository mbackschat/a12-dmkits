package io.github.mbackschat.a12.dm.consistency

/**
 * Whether a declared GROUP or FIELD name is one A12 accepts — its alphabet, and the one name it reserves.
 *
 * **The carriers do not share their alphabet, measured row for row** ([KERNEL-FINDINGS KF315],
 * `rulekit.validate.laws.GroupNameAlphabetLawsTest` and `FieldNameAlphabetLawsTest`). A group below the root
 * refuses a leading underscore that a field ADMITS, and admits a non-ASCII letter that a field refuses; the
 * 60-character limit is the group's alone. Applying one carrier's law to the other would have refused a
 * field named `_Slots`, which is the direction this checker may not take — so the two are asked separately.
 *
 * **And "group" is two carriers, not one** ([KERNEL-FINDINGS KF322], `DeclaredNameShapeLawsTest`). The ROOT
 * group admits the leading underscore that a group below it refuses, and answers a digit-initial name with
 * `MVK_NAME_STARTS_WITH_DIGIT` where the nested one answers with the path class and no name class. This
 * checker asked every group the nested law until that was measured, and so refused a legal model — the
 * alphabet rows all transfer, which is why nothing here prompted the question.
 *
 * **A RULE's or a COMPUTATION's name is not asked at all**, and that is now measured rather than merely
 * unmeasured: a digit-initial or dotted rule name leaves A12 unable to BUILD the model, so no class exists
 * for this family to reproduce, and an `xml` prefix on a rule is simply admitted.
 *
 * **The character denominator is DERIVED and complete**: every printable ASCII character that is not a letter,
 * a digit or an underscore was put to the kernel on both carriers, and the code is written per outcome rather
 * than per character. A NON-ASCII character is never reported, on either carrier — legal on a group, and on a
 * field only one (`ö`) has been measured, so refusing the class would rest a rejection on one row.
 *
 * **The code depends on the character, three ways on a field and two on a group.** `/` is not a bad name at
 * all: it makes the element's own path disagree with the tree it sits in, which A12 reports as
 * `MVK_PATHS_INCONSISTENT`. On a field `.` and `:` answer to a validator of their own. Collapsing these into
 * one code would name the wrong fix for the character an author actually typed.
 *
 * **What it deliberately leaves to A12**, all of it under-reporting: a name longer than the group carrier's
 * 60 characters, which A12 refuses with no code at all and this checker has no shape for; a non-ASCII
 * character; and a name that breaks its alphabet AND a prefix gate at once, which is reported for the
 * character alone because the pair has not been measured.
 */
internal fun TrackedFactBase.elementNameAlphabetFindings(element: TrackedDeclaration): List<Finding> =
    when (element.declaredKind) {
        DeclaredKind.GROUP -> element.groupNameFindings()
        DeclaredKind.FIELD -> element.fieldNameFindings()
        else -> emptyList()
    }

/**
 * The one WHOLE name A12 reserves, and it is the root group's law alone.
 *
 * A root group called `Id` in any case draws `MVK_ROOT_GROUP_NAME_INVALID`; the match is the whole name, so
 * `Identity` and `BinId` are admitted, and a nested group or a field of that name is admitted too
 * ([KERNEL-FINDINGS KF367], `rulekit.validate.laws.GroupNameAlphabetLawsTest`). Every other name law in this
 * family is about an alphabet, which is why this one is asked first and separately: a reservation and a bad
 * character want different fixes.
 */
private fun TrackedDeclaration.reservedRootNameFinding(): Finding? =
    if (path.count { it == '/' } != 1 || !name.equals(RESERVED_ROOT_GROUP_NAME, ignoreCase = true)) {
        null
    } else {
        nameFinding(
            "MVK_ROOT_GROUP_NAME_INVALID",
            "A12 reserves `$RESERVED_ROOT_GROUP_NAME` as a root group's name, whatever its case — the whole " +
                "name is what it reserves, so a name merely beginning or ending with it is admitted",
        )
    }

/** The reserved name itself, matched case-insensitively and in full. */
private const val RESERVED_ROOT_GROUP_NAME = "Id"

/**
 * The prefix A12 reserves on every element carrier, case-insensitively.
 *
 * Measured on all three: a field, a group below the root and the root group each refuse `xml`, `XML`, `Xml`
 * and the bare name `xml`, and each admits `xm` and a name merely containing `xml`. A name whose alphabet is
 * already broken is reported for the character instead, which under-reports a name that breaks both.
 */
private fun TrackedDeclaration.reservedPrefixFinding(code: String, carrier: String): Finding? =
    if (!name.lowercase().startsWith("xml")) {
        null
    } else {
        nameFinding(
            code,
            "this $carrier's name begins with `xml`, which A12 reserves whatever its case — a name merely " +
                "containing `xml` is admitted",
        )
    }

private fun TrackedDeclaration.groupNameFindings(): List<Finding> {
    reservedRootNameFinding()?.let { return listOf(it) }
    illegalCharacter()?.let { (at, character) ->
        return listOf(
            nameFinding(
                if (character == '/') "MVK_PATHS_INCONSISTENT" else "MVK_PATH_INVALID",
                at,
                character,
            ),
        )
    }
    if (name.firstOrNull()?.isDigit() == true) {
        // Which class this draws is the ROOT group's business: below the root A12 reports the path one and
        // no name one, and on the root group the reverse.
        return listOf(
            nameFinding(
                if (isRootGroup) "MVK_NAME_STARTS_WITH_DIGIT" else "MVK_PATH_INVALID",
                "this group's name begins with a digit, and A12 requires a name to begin with a letter or " +
                    "an underscore",
            ),
        )
    }
    reservedPrefixFinding("MVK_NAME_STARTS_WITH_XML", "group")?.let { return listOf(it) }
    // The gate A12's own message does not mention: its path validator's sentence says a leading underscore is
    // legal, and a second gate then reserves that prefix for A12's own declarations. It is a law of groups
    // BELOW the root — the root group takes `_Orders` and `__other` — so asking it of every group refused a
    // legal model until the carrier was measured.
    if (!isRootGroup && name.startsWith("_") &&
        RESERVED_GROUP_NAMES.none { name == it || name.startsWith("${it}_") }
    ) {
        return listOf(
            nameFinding(
                "NAME_STARTS_WITH_UNDERSCORE_AND_WRONG_PATTERN",
                "this group's name begins with an underscore, which A12 reserves for " +
                    "${RESERVED_GROUP_NAMES.joinToString(" and ") { "`$it`" }} — the path validator's own " +
                    "message says a leading underscore is legal and a second gate refuses it",
            ),
        )
    }
    return emptyList()
}

/**
 * Whether this is a ROOT group of the model A12 builds, which its name laws part on.
 *
 * One path segment says it: a mounted provider's own root group DISAPPEARS into the mounting group, so an
 * expanded include contributes no root group of its own and depth is what A12 sees.
 */
private val TrackedDeclaration.isRootGroup: Boolean
    get() = path.count { it == '/' } == 1

private fun TrackedDeclaration.fieldNameFindings(): List<Finding> {
    illegalCharacter()?.let { (at, character) ->
        return listOf(
            nameFinding(
                when (character) {
                    '/' -> "MVK_PATHS_INCONSISTENT"
                    '.', ':' -> "MVK_NAME_INVALID_DOT_COLON_A12_DV"
                    else -> "MVK_PATH_INVALID"
                },
                at,
                character,
            ),
        )
    }
    if (name.firstOrNull()?.isDigit() == true) {
        // Two classes, because A12 reports two — the path one and the field carrier's own name one. Stating
        // only the first would name one of the two fixes an author's report from A12 will carry.
        return listOf(
            nameFinding(
                "MVK_PATH_INVALID",
                "this field's name begins with a digit, and A12 requires a name to begin with a letter or " +
                    "an underscore",
            ),
            nameFinding(
                "MVK_NAME_STARTS_WITH_DIGIT_A12_DV",
                "this field's name begins with a digit, which A12's field-name gate refuses beside the " +
                    "path one",
            ),
        )
    }
    reservedPrefixFinding("MVK_NAME_STARTS_WITH_XML_A12_DV", "field")?.let { return listOf(it) }
    // A leading underscore is deliberately not reported here: it is ADMITTED on a field and refused on a
    // group below the root, measured, and that row is what makes the carriers separate laws rather than one.
    return emptyList()
}

/**
 * The first character of this name A12's alphabet cannot take, with its offset.
 *
 * Only ASCII is judged — see the class note. The offset is carried because a long name gives an author
 * nothing else to look at.
 */
private fun TrackedDeclaration.illegalCharacter(): Pair<Int, Char>? = name
    .withIndex()
    .firstOrNull { (_, character) -> character.code < 128 && !character.isAdmittedAscii() }
    ?.let { it.index to it.value }

private fun Char.isAdmittedAscii(): Boolean =
    this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' || this == '_'

/** The two names A12 exempts from its leading-underscore gate, the second as a prefix. */
private val RESERVED_GROUP_NAMES = listOf("__meta", "__attachment_meta")

private fun TrackedDeclaration.nameFinding(code: String, at: Int, character: Char) = nameFinding(
    code,
    if (character == '/') {
        "this name carries the path separator `/` at offset $at, so the element's own path disagrees with " +
            "the tree it is declared in"
    } else {
        "this name carries `$character` at offset $at, and A12 admits only letters, digits and underscores"
    },
)

private fun TrackedDeclaration.nameFinding(code: String, explain: String) = Finding(
    FindingCategory.A12_CONSTRAINT,
    FindingSeverity.ERROR,
    code,
    explain,
    path,
)

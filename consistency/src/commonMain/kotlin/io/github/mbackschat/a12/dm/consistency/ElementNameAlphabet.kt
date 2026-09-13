package io.github.mbackschat.a12.dm.consistency

/**
 * Whether a declared GROUP or FIELD name is one A12 accepts — and it is two laws, not one.
 *
 * **The carriers do not share their alphabet, measured row for row** ([KERNEL-FINDINGS KF315],
 * `rulekit.validate.laws.GroupNameAlphabetLawsTest` and `FieldNameAlphabetLawsTest`). A group refuses a
 * leading underscore that a field ADMITS, and admits a non-ASCII letter that a field refuses; the 60-character
 * limit is the group's alone. Applying one carrier's law to the other would have refused a field named
 * `_Slots`, which is the direction this checker may not take — so the two are asked separately and a carrier
 * with no grid (a rule's or a computation's name) is not asked at all.
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
 * character; and the SECOND code a digit-initial field name draws beside the one reported here.
 */
fun PreparedModel.elementNameAlphabetFindings(): List<Finding> = elements.mapNotNull { element ->
    when (element.declaredKind) {
        DeclaredKind.GROUP -> element.groupNameFinding()
        DeclaredKind.FIELD -> element.fieldNameFinding()
        else -> null
    }
}

private fun DeclaredElement.groupNameFinding(): Finding? {
    illegalCharacter()?.let { (at, character) ->
        return nameFinding(
            if (character == '/') "MVK_PATHS_INCONSISTENT" else "MVK_PATH_INVALID",
            at,
            character,
        )
    }
    if (name.firstOrNull()?.isDigit() == true) {
        return nameFinding(
            "MVK_PATH_INVALID",
            "this group's name begins with a digit, and A12 requires a name to begin with a letter or an " +
                "underscore",
        )
    }
    // The gate A12's own message does not mention: its path validator's sentence says a leading underscore is
    // legal, and a second gate then reserves that prefix for A12's own declarations.
    if (name.startsWith("_") && RESERVED_GROUP_NAMES.none { name == it || name.startsWith("${it}_") }) {
        return nameFinding(
            "NAME_STARTS_WITH_UNDERSCORE_AND_WRONG_PATTERN",
            "this group's name begins with an underscore, which A12 reserves for " +
                "${RESERVED_GROUP_NAMES.joinToString(" and ") { "`$it`" }} — the path validator's own message " +
                "says a leading underscore is legal and a second gate refuses it",
        )
    }
    return null
}

private fun DeclaredElement.fieldNameFinding(): Finding? {
    illegalCharacter()?.let { (at, character) ->
        return nameFinding(
            when (character) {
                '/' -> "MVK_PATHS_INCONSISTENT"
                '.', ':' -> "MVK_NAME_INVALID_DOT_COLON_A12_DV"
                else -> "MVK_PATH_INVALID"
            },
            at,
            character,
        )
    }
    if (name.firstOrNull()?.isDigit() == true) {
        return nameFinding(
            "MVK_PATH_INVALID",
            "this field's name begins with a digit, and A12 requires a name to begin with a letter or an " +
                "underscore — it also reports `MVK_NAME_STARTS_WITH_DIGIT_A12_DV` for this shape, which this " +
                "finding does not repeat",
        )
    }
    // A leading underscore is deliberately not reported here: it is ADMITTED on a field and refused on a
    // group, measured, and this is the row that makes the two carriers separate laws rather than one.
    return null
}

/**
 * The first character of this name A12's alphabet cannot take, with its offset.
 *
 * Only ASCII is judged — see the class note. The offset is carried because a long name gives an author
 * nothing else to look at.
 */
private fun DeclaredElement.illegalCharacter(): Pair<Int, Char>? = name
    .withIndex()
    .firstOrNull { (_, character) -> character.code < 128 && !character.isAdmittedAscii() }
    ?.let { it.index to it.value }

private fun Char.isAdmittedAscii(): Boolean =
    this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' || this == '_'

/** The two names A12 exempts from its leading-underscore gate, the second as a prefix. */
private val RESERVED_GROUP_NAMES = listOf("__meta", "__attachment_meta")

private fun DeclaredElement.nameFinding(code: String, at: Int, character: Char) = nameFinding(
    code,
    if (character == '/') {
        "this name carries the path separator `/` at offset $at, so the element's own path disagrees with " +
            "the tree it is declared in"
    } else {
        "this name carries `$character` at offset $at, and A12 admits only letters, digits and underscores"
    },
)

private fun DeclaredElement.nameFinding(code: String, explain: String) = Finding(
    FindingCategory.A12_CONSTRAINT,
    FindingSeverity.ERROR,
    code,
    explain,
    path,
)

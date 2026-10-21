package io.github.mbackschat.a12.dm.consistency

/** KF315/KF322 and R4's carrier population separate path admission from the later name gates. */
internal fun TrackedFactBase.elementNameAlphabetFindings(element: TrackedDeclaration): List<Finding> {
    val kind = element.declaredKind
    if (kind != DeclaredKind.GROUP && kind != DeclaredKind.FIELD || element.name.isEmpty()) return emptyList()
    if (kind == DeclaredKind.FIELD && element.body.deletesAtDefaultVariant()) return emptyList()
    val root = element.path == "/${element.name}"
    // A12's entity walk stops at a refused path and never descends (KERNEL-FINDINGS KF580), so nothing beneath one
    // states a path or group class of its own; the field-name gates are not part of that walk and still speak.
    val belowRefusedPath = !root && element.path.removeSuffix("/${element.name}").removePrefix("/")
        .invalidElementPathOffset(root = true, field = false) != null
    if (belowRefusedPath && kind == DeclaredKind.GROUP) return emptyList()
    val invalid = element.name.invalidElementPathOffset(root, kind == DeclaredKind.FIELD)
    val findings = mutableListOf<Finding>()
    if (!belowRefusedPath && invalid != null) {
        findings += element.nameFinding("MVK_PATH_INVALID",
            "this name carries `${element.name[invalid]}` at offset $invalid, which this path position does not admit")
    } else if (!belowRefusedPath && '/' in element.name) {
        return listOf(element.nameFinding("MVK_PATHS_INCONSISTENT",
            "this name carries the path separator `/` at offset ${element.name.indexOf('/')}, so its path disagrees with the declaration tree"))
    }
    if (kind == DeclaredKind.GROUP) {
        if (invalid != null) return findings
        // A12 reads the prefix off every group segment of the path, so a group below one named `xml…` draws the
        // class at its own path too, however it is named itself — unless the path is already refused above it
        // (KERNEL-FINDINGS KF558).
        val segments = element.path.removePrefix("/")
        val xmlAncestor = segments.split('/').dropLast(1).firstOrNull { it.startsWithIgnoringAsciiCase("xml") }
            ?.takeIf { segments.invalidElementPathOffset(root = true, field = false) == null }
        val refusal = when {
            root && element.name.equalsIgnoringAsciiCase("Id") -> "MVK_ROOT_GROUP_NAME_INVALID" to
                "A12 reserves `Id` as a root group's whole name, ignoring case"
            element.name.first().isAsciiDigit() -> "MVK_NAME_STARTS_WITH_DIGIT" to
                "this root group's name begins with a digit; A12 requires a letter or underscore"
            element.name.startsWithIgnoringAsciiCase("xml") -> "MVK_NAME_STARTS_WITH_XML" to
                "this group's name begins with the reserved prefix `xml`"
            xmlAncestor != null -> "MVK_NAME_STARTS_WITH_XML" to
                "this group sits below `$xmlAncestor`, and A12 reads that group's reserved prefix `xml` into every " +
                    "group beneath it"
            else -> null
        }
        if (refusal != null) findings += element.nameFinding(refusal.first, refusal.second)
        // Asked independently of the prefix classes: below an `xml…` ancestor A12 states both (KERNEL-FINDINGS
        // KF583). Its own `xml` prefix, a leading digit and the root's reserved `Id` cannot begin with `_`.
        if (!root && element.name.startsWith('_') && !element.name.isReservedUnderscoreGroupName()) {
            findings += element.nameFinding("NAME_STARTS_WITH_UNDERSCORE_AND_WRONG_PATTERN",
                "A12 admits a leading underscore below the root only for `__meta` itself or a name beginning " +
                    "with `__attachment_meta`")
        }
    } else if (ElementPathPart.FIELD_NAME.invalidOffset(element.name) == null) {
        // R4-N's 1Co.de reaches both name gates even though the ordinary field path refuses its digit.
        if (element.name.any { it == '.' || it == ':' }) {
            findings += element.nameFinding("MVK_NAME_INVALID_DOT_COLON_A12_DV",
                "this field's name contains a dot or colon, which A12's field-name gate refuses")
        } else if (element.name.startsWithIgnoringAsciiCase("xml")) {
            findings += element.nameFinding("MVK_NAME_STARTS_WITH_XML_A12_DV",
                "this field's name begins with the reserved prefix `xml`")
        }
        if (element.name.first().isAsciiDigit()) {
            findings += element.nameFinding("MVK_NAME_STARTS_WITH_DIGIT_A12_DV",
                "this field's name begins with a digit, which A12's field-name gate refuses")
        }
    }
    return findings
}

/** KF490's message walk shares path admission, including ancestor segments, but rejects embedded separators. */
internal fun TrackedDeclaration.reachedByTheElementWalk(): Boolean = when (declaredKind) {
    DeclaredKind.GROUP, DeclaredKind.FIELD -> '/' !in name &&
        path.removePrefix("/").invalidElementPathOffset(root = true, field = declaredKind == DeclaredKind.FIELD) == null
    else -> true
}

private fun String.invalidElementPathOffset(root: Boolean, field: Boolean): Int? {
    val segments = split('/')
    var offset = 0
    for ((index, segment) in segments.withIndex()) {
        if (segment.isEmpty()) return (offset - 1).coerceAtLeast(0)
        val part = when {
            field && index == segments.lastIndex -> ElementPathPart.FIELD_PATH
            root && index == 0 -> ElementPathPart.ROOT_GROUP
            else -> ElementPathPart.GROUP
        }
        part.invalidOffset(segment)?.let { return offset + it }
        offset += segment.length + 1
    }
    return null
}

/** The field name gate admits an initial digit that the ordinary field path rejects (KF322, R4-N). */
private enum class ElementPathPart {
    ROOT_GROUP, GROUP, FIELD_PATH, FIELD_NAME;

    fun invalidOffset(segment: String): Int? {
        if (segment.isEmpty()) return 0
        return when (this) {
            ROOT_GROUP, GROUP -> when {
                this == GROUP && !segment.first().isAsciiLetter() && segment.first() != '_' -> 0
                else -> segment.indexOfFirst { !it.isGroupCharacter() }.takeIf { it >= 0 }
            }
            FIELD_PATH, FIELD_NAME -> when {
                this == FIELD_PATH && segment.first().isAsciiDigit() -> 0
                else -> segment.indices.firstOrNull { index ->
                    val char = segment[index]
                    when {
                        char == '.' -> index == 0 || index == segment.lastIndex || !segment[index + 1].isAsciiLetter()
                        else -> !char.isAsciiLetter() && !char.isAsciiDigit() && char != '_' && char != ':'
                    }
                }
            }
        }
    }
}

private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'
private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'
private fun Char.isGroupCharacter(): Boolean = isAsciiLetter() || isAsciiDigit() || this == '_' || this in "äöüÄÖÜß"

/**
 * The two underscore names A12 keeps for its own groups, and they are reserved DIFFERENTLY
 * ([KERNEL-FINDINGS KF569]): `__meta` as a whole name, `__attachment_meta` as a prefix — so
 * `__attachment_metadata` and `__attachment_metaX` are admitted while `__meta_x` and `__metadata` are refused.
 * Case counts on both.
 */
private fun String.isReservedUnderscoreGroupName(): Boolean = this == "__meta" || startsWith("__attachment_meta")

private fun TrackedDeclaration.nameFinding(code: String, explain: String) = Finding(
    FindingCategory.A12_CONSTRAINT, FindingSeverity.ERROR, code, explain, path,
)

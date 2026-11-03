package io.github.mbackschat.a12.dm.consistency

/**
 * The codeless sentences A12 states about the names an element's path is made of
 * (KERNEL-FINDINGS KF580).
 *
 * They are a judgement separate from the path gate in [elementNameAlphabetFindings], with alphabets of their own, and
 * the one that surprises is the group-path sentence: every element states it about the group path it lives in — a
 * nested group about its own path, any other element about its parent's — so one bad group draws it at every element
 * beneath it, while `MVK_PATH_INVALID` stays at the group alone. The root group answers with sentences of its own
 * instead. Each value is Java-trimmed as a whole before it is judged, so a nested group whose name ends in a blank
 * leaves its own path and its direct children unrefused, while a grandchild, whose path continues past the blank,
 * is refused.
 *
 * **Two carriers are left to A12.** A supplementary character in the judged value replaces every sentence with the
 * charset refusal (KF551), so stating one there
 * would be wrong. A name carrying `/` splits its path off the declaration tree, and A12 then attaches the sentences
 * to some elements beneath it and not to others, in an order KF580 records but does not explain; the checker states
 * none on such a path rather than guess.
 */
internal fun TrackedFactBase.elementPathSentenceFindings(element: TrackedDeclaration): List<Finding> {
    val kind = element.declaredKind ?: return emptyList()
    if (element.name.isEmpty() || '/' in element.name) return emptyList()
    val root = kind == DeclaredKind.GROUP && element.path == "/${element.name}"
    if (root) return listOfNotNull(rootGroupNameFinding(element))
    val groupPath = if (kind == DeclaredKind.GROUP) element.path else element.path.substringBeforeLast('/')
    if (!onTheDeclarationTree(groupPath)) return emptyList()
    val groupFinding = groupPath.takeIf { it.namedByA12() && it.refusedAsGroupPath() }?.let {
        element.sentenceFinding(RK_GROUP_PATH_INVALID,
            "this element lives at the group path `$it`, which A12 refuses: a group name may only contain letters, " +
                "digits and underscores, must begin with a letter or an underscore, and may not exceed " +
                "$NAME_PART_LIMIT characters")
    }
    val fieldFinding = element.name.takeIf { kind == DeclaredKind.FIELD && it.refusedAsFieldName() }?.let {
        element.sentenceFinding(RK_FIELD_NAME_ALPHABET, "this field's name `${it.javaTrimmed()}` is $FIELD_NAME_ALPHABET")
    }
    return listOfNotNull(fieldFinding, groupFinding)
}

/** Every proper prefix of [groupPath] is a declared group, which fails exactly where a name above carries `/`. */
private fun TrackedFactBase.onTheDeclarationTree(groupPath: String): Boolean {
    var prefix = ""
    return groupPath.removePrefix("/").split('/').all { segment ->
        prefix += "/$segment"
        declarationAt(prefix, DeclaredKind.GROUP) != null
    }
}

private fun rootGroupNameFinding(element: TrackedDeclaration): Finding? {
    if (!element.name.namedByA12()) return null
    val name = element.name.javaTrimmed()
    return when {
        !name.isRootGroupName() -> element.sentenceFinding(RK_ROOT_GROUP_NAME_ALPHABET,
            "this root group's name `$name` is not letters, digits and underscores")
        // A12 states one sentence per value: an alphabet refusal takes the place of the length refusal.
        name.length > NAME_PART_LIMIT -> element.sentenceFinding(RK_MEMBER_ABOVE_60_CHARACTERS,
            "this root group's name has ${name.length} characters, and A12 admits at most $NAME_PART_LIMIT")
        else -> null
    }
}

/** The root segment answers to the root alphabet, which admits a leading digit or umlaut; every other to the group one. */
private fun String.refusedAsGroupPath(): Boolean =
    javaTrimmed().removePrefix("/").split('/').withIndex().any { (index, segment) ->
        segment.length > NAME_PART_LIMIT || !(if (index == 0) segment.isRootGroupName() else segment.isNestedGroupName())
    }

private fun String.isRootGroupName(): Boolean = isNotEmpty() && all { it.isGroupNameCharacter() }

private fun String.isNestedGroupName(): Boolean =
    isNotEmpty() && (first() in 'a'..'z' || first() in 'A'..'Z' || first() == '_') && all { it.isGroupNameCharacter() }

private fun Char.isGroupNameCharacter(): Boolean =
    this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' || this == '_' || this in "äöüÄÖÜß"

private fun TrackedDeclaration.sentenceFinding(code: String, explain: String) =
    Finding(FindingCategory.A12_CONSTRAINT, FindingSeverity.ERROR, code, explain, path)

private const val NAME_PART_LIMIT = 60

/** A field name's parts: the first of letters, digits, underscores and colons, each further one after a dot and a letter. */
private val FIELD_NAME = Regex("[a-zA-Z0-9_:]+([.][a-zA-Z][a-zA-Z0-9_:]*)*")

/** What [RK_FIELD_NAME_ALPHABET] says a name is not. */
internal const val FIELD_NAME_ALPHABET =
    "not letters, digits, underscores and colons, in dot-separated parts that each begin with a letter"

/**
 * Whether A12 states its field-name sentence about this name, judged Java-trimmed. A name carrying a supplementary
 * character draws the charset refusal instead (KF551), so it is never refused here.
 */
internal fun String.refusedAsFieldName(): Boolean = namedByA12() && !FIELD_NAME.matches(javaTrimmed())

internal const val RK_GROUP_PATH_INVALID = "RK_GROUP_PATH_INVALID"
internal const val RK_ROOT_GROUP_NAME_ALPHABET = "RK_ROOT_GROUP_NAME_ALPHABET"
internal const val RK_FIELD_NAME_ALPHABET = "RK_FIELD_NAME_ALPHABET"
internal const val RK_MEMBER_ABOVE_60_CHARACTERS = "RK_MEMBER_ABOVE_60_CHARACTERS"

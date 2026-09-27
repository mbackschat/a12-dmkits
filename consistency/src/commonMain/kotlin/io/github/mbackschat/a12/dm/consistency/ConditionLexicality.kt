package io.github.mbackschat.a12.dm.consistency

/** Family selection projects the shared syntax verdict; recognition owns the code and position. */
internal fun TrackedFactBase.conditionLexicalFindings(element: TrackedDeclaration): List<Finding> =
    conditionSyntaxFindings(element, CheckFamily.CONDITION_LEXICALITY)

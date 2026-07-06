package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.parse.parseCondition
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The kernel grammar's `relativeUp` production is `relUp? '..' idName? stern='*'?` — the last `..` in a
 * parent-relative run may carry an explicit **turning-point label** `..Name` (optional since 2022, but still
 * emitted by older stored models — 10 conditions in the `a12-kernel-mm` metamodels use it). Per
 * `CheckRelativeUpImpl`, `Name` merely NAMES the ancestor the up-count lands on (the kernel throws
 * `MVK_INVALID_ENTITY` if it doesn't match that group's short name) — it NEVER changes the target, which is set
 * purely by the number of `..` tokens. So the interpreter resolves `..Name` exactly as `..`: `..Name/rest`
 * parses to the SAME AST as `../rest`, and `../..Name/rest` as `../../rest`.
 *
 * This is the syntactic lock (AST equality); [io.github.mbackschat.a12.dm.adapter.laws.NamedAncestorPathDiffTest]
 * is the behavioral lock against the real kernel. WI-P (SEMANTICS-FORMALIZATION-PROPOSAL, from Phase-0 S1;
 * INTERPRETER-FINDINGS IF55). Field-vs-field and presence predicates only — no NUMBER literal (its `Dec` compares by identity).
 */
class NamedAncestorPathParseTest {

    @Test
    fun namedTurningPointEqualsPlainParentInPredicateAndValueRef() =
        assertEquals(
            parseCondition("AllFieldsFilled(columnName, ../columnName) And [name] == [../name]", "/Data/Enum/Cols"),
            parseCondition("AllFieldsFilled(columnName, ..Enum/columnName) And [name] == [..Enum/name]", "/Data/Enum/Cols"),
        )

    @Test
    fun multipleLevelsWithNamedTurningPoint() =
        assertEquals(
            // `../..EnumerationType/Values*/value` — a relUp step then a named terminal, two levels up.
            parseCondition("NoFieldFilled(../../Values*/value)", "/Data/EnumerationType/Cols/Sub"),
            parseCondition("NoFieldFilled(../..EnumerationType/Values*/value)", "/Data/EnumerationType/Cols/Sub"),
        )

    @Test
    fun bareNamedAncestorReferencesTheAncestorGroupItself() =
        // `..Categories` (no descent) references the parent group — same as the leading-slash absolute form of it.
        assertEquals(
            parseCondition("GroupFilled(/Data/Categories)", "/Data/Categories/Entries"),
            parseCondition("GroupFilled(..Categories)", "/Data/Categories/Entries"),
        )
}

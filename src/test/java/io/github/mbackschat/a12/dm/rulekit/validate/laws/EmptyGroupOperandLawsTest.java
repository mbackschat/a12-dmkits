package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * KERNEL-FINDINGS — <b>declaring a group and NAMING one are separate questions.</b> A group carrying no
 * fields is legal to declare and the model stays kernel-valid; every operand that names it is then refused,
 * on codes of its own rather than on the unknown-group class. Reported as {@code SPEC-2026-08-29-05} by
 * a12-kernel-lean and measured here.
 *
 * <p><b>The gate is over the SUBTREE, not the direct children</b> — the row that carries the finding. A group
 * with no direct field but a field in a descendant subgroup is admitted everywhere an empty one is refused,
 * in both repetition shapes. Without it, "empty" would be indistinguishable from "childless".
 *
 * <p><b>Depth does not bound it, and neither does position or arity</b> — a12-kernel-lean reported the plain
 * multi-group count half as {@code SPEC-2026-08-30-01}; the table below carries it together with the empty
 * row that discriminates it, which is the pairing neither project had on one fixture.
 *
 * <p><b>Every condition references the error field.</b> An operand-level refusal reports ahead of
 * {@code MVK_ERROR_FIELD_NOT_REFERENCED}, so a table built without that conjunct reads correctly on its
 * refusing rows and reports the wrong code on every admitted control — measured, on the first attempt.
 *
 * <p>Own domain (a retail store model with a second, entirely unpopulated root).
 */
class EmptyGroupOperandLawsTest {

    private static final String ADMITTED = KernelLaws.ADMITTED;
    private static final String GROUP_IS_EMPTY = "MVK_GROUP_IS_EMPTY";

    /**
     * Four shapes per repetition kind: carrying nothing at all, carrying only a populated subgroup, and
     * carrying a direct field — plus a second root that carries nothing anywhere.
     */
    private static final String STORE = DocumentModelDraft.named("store").locale("en_US")
            .rootGroup("Store", s -> s
                    .field("Reference", FieldKind.STRING)
                    .group("Empty", e -> { })
                    .group("EmptyRows", e -> e.repeatable(20))
                    .group("Shell", sh -> sh.group("Inner", i -> i.field("InnerNote", FieldKind.STRING)))
                    .group("ShellRows", sh -> sh.repeatable(20)
                            .group("Inner", i -> i.field("InnerNote", FieldKind.STRING)))
                    .group("Filled", f -> f.field("FilledNote", FieldKind.STRING))
                    .group("Other", o -> o.field("OtherNote", FieldKind.STRING))
                    .group("Deep", d -> d.group("Mid", m -> m.group("Leaf", l -> l.field("LeafNote", FieldKind.STRING))))
                    .group("RowShell", r -> r.group("Rows", rows -> rows.repeatable(20)
                            .field("RowNote", FieldKind.STRING)))
                    .group("FilledRows", f -> f.repeatable(20).field("RowNote", FieldKind.STRING)))
            .rootGroup("Vacant", v -> { })
            .build().toJson();

    /** The rule's own reference, conjoined into every condition so the error field is always referenced. */
    private static final String REFERENCED = "FieldFilled(/Store/Reference) And ";

    private static String verdict(String groupCondition) {
        ValidationResult r = KernelLaws.checkModel(STORE, "/Store", "../Reference", REFERENCED + groupCondition);
        return r.ok() ? ADMITTED : r.diagnostics().stream()
                .map(d -> d.code().orElse("(no code: " + d.summary() + ")"))
                .collect(Collectors.joining(","));
    }

    /**
     * Four carriers, three group shapes each. The empty group is refused on every carrier; the shell — no
     * direct field, one populated descendant — is admitted on every carrier, beside the populated control.
     */
    @Test
    void anEmptyGroupIsRefusedOnEveryCarrierWhileASHELLIsAdmittedOnAllOfThem() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("presence — empty", verdict("GroupFilled(/Store/Empty)"));
        actual.put("presence — shell", verdict("GroupFilled(/Store/Shell)"));
        actual.put("presence — populated", verdict("GroupFilled(/Store/Filled)"));
        actual.put("row count — empty", verdict("NumberOfFilledGroups(/Store/EmptyRows*) > 0"));
        actual.put("row count — shell", verdict("NumberOfFilledGroups(/Store/ShellRows*) > 0"));
        actual.put("row count — populated", verdict("NumberOfFilledGroups(/Store/FilledRows*) > 0"));
        actual.put("all filled — empty", verdict("AllGroupsFilled(/Store/Empty, /Store/Filled)"));
        actual.put("all filled — shell", verdict("AllGroupsFilled(/Store/Shell, /Store/Filled)"));
        actual.put("collective fill — empty",
                verdict("GroupsNotCollectivelyFilled(/Store/Empty, /Store/Filled)"));
        actual.put("collective fill — shell",
                verdict("GroupsNotCollectivelyFilled(/Store/Shell, /Store/Filled)"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("presence — empty", GROUP_IS_EMPTY);
        expected.put("presence — shell", ADMITTED);
        expected.put("presence — populated", ADMITTED);
        expected.put("row count — empty", GROUP_IS_EMPTY);
        expected.put("row count — shell", ADMITTED);
        expected.put("row count — populated", ADMITTED);
        expected.put("all filled — empty", GROUP_IS_EMPTY);
        expected.put("all filled — shell", ADMITTED);
        expected.put("collective fill — empty", GROUP_IS_EMPTY);
        expected.put("collective fill — shell", ADMITTED);
        assertEquals(expected, actual, "emptiness is judged over the SUBTREE, and only naming one refuses");
    }

    /** A root group has its own class for the same condition, which is why the two are not one row. */
    @Test
    void anEmptyROOTGroupCarriesItsOwnClass() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("the unpopulated root", verdict("AtLeastOneGroupFilled(/Vacant)"));
        actual.put("the populated root — control", verdict("AtLeastOneGroupFilled(/Store)"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("the unpopulated root", "MVK_ROOT_GROUP_IS_EMPTY");
        expected.put("the populated root — control", ADMITTED);
        assertEquals(expected, actual, "an empty root is not reported on the ordinary group class");
    }

    /**
     * The plain multi-group {@code NumberOfFilledGroups} — the carrier a12-kernel-lean measured as
     * {@code SPEC-2026-08-30-01}, extended past its stated limits. A shell is admitted at either position and
     * at list length three; a field three levels down still counts as content; and a group whose only content
     * is a repeatable descendant is admitted too.
     *
     * <p>Three controls keep the table from reading green for the wrong reason: the EMPTY group on the same
     * carrier still refuses, so the gate is live here rather than absent; a single fixed group still draws the
     * arity class; and a group under a repeatable extent still draws the wildcard class. Without them,
     * "this carrier refuses nothing" would satisfy every admitted row.
     */
    @Test
    void theGROUPCOUNTAdmitsANestedShellAtEveryPositionDepthAndArity() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("shell first", verdict("NumberOfFilledGroups(/Store/Shell, /Store/Filled) > 0"));
        actual.put("shell second", verdict("NumberOfFilledGroups(/Store/Filled, /Store/Shell) > 0"));
        actual.put("shell in a three-group list",
                verdict("NumberOfFilledGroups(/Store/Filled, /Store/Shell, /Store/Other) > 0"));
        actual.put("content three levels down", verdict("NumberOfFilledGroups(/Store/Deep, /Store/Filled) > 0"));
        actual.put("content only in a repeatable descendant",
                verdict("NumberOfFilledGroups(/Store/RowShell, /Store/Filled) > 0"));
        actual.put("all-flat pair — control", verdict("NumberOfFilledGroups(/Store/Filled, /Store/Other) > 0"));
        actual.put("the EMPTY group on the same carrier — control",
                verdict("NumberOfFilledGroups(/Store/Empty, /Store/Filled) > 0"));
        actual.put("one fixed group — arity control", verdict("NumberOfFilledGroups(/Store/Shell) > 0"));
        actual.put("a group under a repeatable extent — wildcard control",
                verdict("NumberOfFilledGroups(/Store/ShellRows/Inner, /Store/Filled) > 0"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("shell first", ADMITTED);
        expected.put("shell second", ADMITTED);
        expected.put("shell in a three-group list", ADMITTED);
        expected.put("content three levels down", ADMITTED);
        expected.put("content only in a repeatable descendant", ADMITTED);
        expected.put("all-flat pair — control", ADMITTED);
        expected.put("the EMPTY group on the same carrier — control", GROUP_IS_EMPTY);
        expected.put("one fixed group — arity control", "MVK_PARAMSIZE_INVALIDGN");
        expected.put("a group under a repeatable extent — wildcard control", "MVK_NO_WILDCARD");
        assertEquals(expected, actual, "the group count reads content over the whole subtree, at any depth");
    }

    /**
     * The declare half, and the reason this class can make it: the model above carries an empty fixed group,
     * an empty repeatable group and an entirely unpopulated ROOT, and the kernel still loads it — every
     * admitted row in the tables above is that load, arbitrated.
     */
    @Test
    void theModelCarryingAllThreeEmptyShapesIsItselfAccepted() {
        assertEquals(ADMITTED, verdict("GroupFilled(/Store/Filled)"),
                "a rule naming only populated groups is admitted, so the declarations themselves are legal");
    }
}

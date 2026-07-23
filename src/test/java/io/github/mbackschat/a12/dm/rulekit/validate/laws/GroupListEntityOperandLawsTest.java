package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

/**
 * KERNEL-FINDINGS §1 — the fixed group-list quantifiers consume ONE common entity list that admits
 * BOTH field and group operands (SPEC-2026-07-23-09; kernel {@code CheckKontextListenBedingungImpl}
 * routes all five through {@code CheckEntityListenUtils} and only the <em>scalar</em>
 * {@code GroupFilled}/{@code GroupNotFilled} pair carries {@code checkOnlyGroups}).
 *
 * <ul>
 *   <li><b>Admission</b>: mixed field/group and field-only lists are accepted by all five; the
 *       count-zero/count-≥1 pair additionally accepts a <em>singleton</em> field.</li>
 *   <li><b>Duplicate/overlap bar</b> ({@code checkParamsReferencedOnlyOnce}): a direct duplicate
 *       operand draws {@code MVK_DUPLICATE_PARAM1}; an operand nested under a listed group draws
 *       {@code MVK_DUPLICATE_PARAM2} — fields participate in both.</li>
 *   <li><b>Arity bar</b> ({@code checkIsMoreThanOneGroupOrField}): the all/not-all/not-collectively
 *       family needs more than one operand even when the sole operand is a field
 *       ({@code MVK_PARAMSIZE_INVALID2}).</li>
 *   <li><b>Wildcard bar</b> ({@code checkNoWildCardsInGroup}): a starred FIELD operand
 *       ({@code Damages*}{@code /Description}) is barred from the same family
 *       ({@code MVK_NO_WILDCARDS_G_ALLOWED}) — the fixed lists never generalize to repeatable
 *       addressing there.</li>
 * </ul>
 *
 * <p>The rootGroup bars stay locked in {@link GroupQuantifierLawsTest}. These gates are
 * kernel-arbitrated (F4): the typed surface represents the shapes and lets the kernel refuse.
 */
class GroupListEntityOperandLawsTest {

    private static final String CLAIM_INTAKE = DocumentModelDraft.named("claimintake").locale("en_US")
            .rootGroup("Claim", c -> c
                    .field("PolicyNumber", FieldKind.STRING)
                    .field("IncidentNote", FieldKind.STRING)
                    .group("Vehicle", v -> v
                            .field("Vin", FieldKind.STRING)
                            .field("Plate", FieldKind.STRING))
                    .group("Handler", h -> h.field("Name", FieldKind.STRING))
                    .group("Damages", d -> d.repeatable(5).field("Description", FieldKind.STRING)))
            .build().toJson();

    // --- admission: mixed and field-only entity lists are legal for all five -------------------

    @Test
    void allGroupsFilledAcceptsAMixedFieldGroupList() {
        accepts("AllGroupsFilled(IncidentNote, Vehicle)");
    }

    @Test
    void allGroupsFilledAcceptsAFieldOnlyList() {
        accepts("AllGroupsFilled(IncidentNote, Vehicle/Vin)");
    }

    @Test
    void noGroupFilledAcceptsAMixedFieldGroupList() {
        accepts("NoGroupFilled(IncidentNote, Vehicle)");
    }

    @Test
    void atLeastOneGroupFilledAcceptsAMixedFieldGroupList() {
        accepts("AtLeastOneGroupFilled(IncidentNote, Vehicle)");
    }

    @Test
    void notAllGroupsFilledAcceptsAFieldOnlyList() {
        accepts("NotAllGroupsFilled(IncidentNote, Vehicle/Vin)");
    }

    @Test
    void groupsNotCollectivelyFilledAcceptsAMixedFieldGroupList() {
        accepts("GroupsNotCollectivelyFilled(IncidentNote, Vehicle)");
    }

    // --- the count-zero / count-≥1 pair admits a singleton FIELD -------------------------------

    @Test
    void noGroupFilledAcceptsASingletonField() {
        accepts("NoGroupFilled(IncidentNote)");
    }

    @Test
    void atLeastOneGroupFilledAcceptsASingletonField() {
        accepts("AtLeastOneGroupFilled(IncidentNote)");
    }

    // --- arity: the all/not-all/not-collectively family needs more than one operand ------------

    @Test
    void allGroupsFilledRejectsASingletonField() {
        rejects("AllGroupsFilled(IncidentNote)", "MVK_PARAMSIZE_INVALID2");
    }

    @Test
    void notAllGroupsFilledRejectsASingletonField() {
        rejects("NotAllGroupsFilled(IncidentNote)", "MVK_PARAMSIZE_INVALID2");
    }

    @Test
    void groupsNotCollectivelyFilledRejectsASingletonField() {
        rejects("GroupsNotCollectivelyFilled(IncidentNote)", "MVK_PARAMSIZE_INVALID2");
    }

    // --- duplicates and group/descendant overlap: fields participate ---------------------------

    @Test
    void aDirectDuplicateFieldOperandIsRejected() {
        rejects("AllGroupsFilled(IncidentNote, IncidentNote)", "MVK_DUPLICATE_PARAM1");
    }

    @Test
    void aFieldNestedUnderAListedGroupIsRejected() {
        rejects("AllGroupsFilled(Vehicle, Vehicle/Vin)", "MVK_DUPLICATE_PARAM2");
    }

    @Test
    void aGroupNestedUnderAListedGroupIsRejectedBesideAField() {
        rejects("NoGroupFilled(IncidentNote, Vehicle, Vehicle/Vin)", "MVK_DUPLICATE_PARAM2");
    }

    // --- wildcards: a starred field operand is barred from the ≥2-arity family -----------------

    @Test
    void aStarredFieldOperandIsRejectedByTheAllFamily() {
        rejects("NotAllGroupsFilled(Damages*/Description, Handler)", "MVK_NO_WILDCARDS_G_ALLOWED");
    }

    private static void accepts(String condition) {
        KernelLaws.acceptsModel(CLAIM_INTAKE, "/Claim", "../PolicyNumber",
                "FieldFilled(PolicyNumber) And " + condition);
    }

    private static void rejects(String condition, String code) {
        KernelLaws.rejectsModel(CLAIM_INTAKE, "/Claim", "../PolicyNumber",
                "FieldFilled(PolicyNumber) And " + condition, code);
    }
}

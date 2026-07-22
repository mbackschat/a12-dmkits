package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

/**
 * KERNEL-FINDINGS §9 — RepetitionNotUnique <b>key admission</b> (SPEC-2026-07-22-10). A key is any
 * <b>direct plain value-validating field</b>; there is NO Number/String-only kind restriction
 * (kernel {@code CheckWiederholungBedingungImpl} applies field/category/wildcard/semantic-index/
 * value-validation gates but no field-KIND gate — {@code CheckEntityUtils.checkIsValueValidation} rejects
 * only the no-value-validation profile). So Enumeration, Date, Time, DateTime, Boolean, and Confirm keys are
 * all admitted, while a group, a wildcard, and a no-value-validation String are rejected.
 *
 * <p>Two rejections have their own homes and are not duplicated here: a <b>semantic index</b> key
 * ({@code MVK_SEMANTIC_INDEX_NOT_ALLOWED}) is locked by {@code SemanticIndexSlotLawsTest}, and a key across
 * two parallel repeatable groups ({@code MVK_NOT_IN_SAME_PATH}) by {@code IterationLawsTest}. A <b>category</b>
 * is not a referenceable operand path — the typed {@code FieldRef}-only surface cannot express one and there
 * is no authorable condition syntax for it — so the category gate is a documented kernel boundary, not a
 * firing law here (catalog constraint + KERNEL-SEMANTICS §9).
 */
class RepetitionNotUniqueKeyKindLawsTest {

    /** A price-list whose repeatable {@code Tiers} rows carry one field of every value-validating kind. */
    private static String priceList() {
        return DocumentModelDraft.named("price-list").locale("en_US")
                .rootGroup("PriceList", root -> root.group("Tiers", tiers -> tiers.repeatable(50)
                        .enumField("Band", e -> e.value("STANDARD").value("PREMIUM").value("ELITE")
                                .category("Segment", "NORMAL", "TOP", "TOP"))
                        .dateField("EffectiveDate", d -> d.format("yyyy-MM-dd"))
                        .timeField("CutoffTime", t -> t.format("HH:mm:ss"))
                        .dateTimeField("PublishedAt", dt -> dt.format("yyyy-MM-dd'T'HH:mm:ss"))
                        .field("TaxIncluded", FieldKind.BOOLEAN)
                        .field("Approved", FieldKind.CONFIRM)
                        .stringField("Note", s -> { })
                        .stringField("RawBlob", s -> s.lineBreaks().noValueValidation())))
                .build().toJson();
    }

    // ── admitted: every value-validating kind, no Number/String restriction ──────────────────────────

    @Test
    void anEnumerationKeyIsAdmitted() {
        KernelLaws.acceptsModel(priceList(), "/PriceList", "../Tiers/Band", "RepetitionNotUnique(Tiers/Band)");
    }

    @Test
    void aDateKeyIsAdmitted() {
        KernelLaws.acceptsModel(priceList(), "/PriceList", "../Tiers/EffectiveDate",
                "RepetitionNotUnique(Tiers/EffectiveDate)");
    }

    @Test
    void aTimeKeyIsAdmitted() {
        KernelLaws.acceptsModel(priceList(), "/PriceList", "../Tiers/CutoffTime",
                "RepetitionNotUnique(Tiers/CutoffTime)");
    }

    @Test
    void aDateTimeKeyIsAdmitted() {
        KernelLaws.acceptsModel(priceList(), "/PriceList", "../Tiers/PublishedAt",
                "RepetitionNotUnique(Tiers/PublishedAt)");
    }

    @Test
    void aBooleanKeyIsAdmitted() {
        KernelLaws.acceptsModel(priceList(), "/PriceList", "../Tiers/TaxIncluded",
                "RepetitionNotUnique(Tiers/TaxIncluded)");
    }

    @Test
    void aConfirmKeyIsAdmitted() {
        KernelLaws.acceptsModel(priceList(), "/PriceList", "../Tiers/Approved",
                "RepetitionNotUnique(Tiers/Approved)");
    }

    @Test
    void aMixedKindCompositeKeyIsAdmitted() {
        KernelLaws.acceptsModel(priceList(), "/PriceList", "../Tiers/Band",
                "RepetitionNotUnique(Tiers/Band, Tiers/EffectiveDate, Tiers/TaxIncluded)");
    }

    // ── rejected: the forbidden reference shapes ─────────────────────────────────────────────────────

    @Test
    void aNoValueValidationStringKeyIsRejected() {
        KernelLaws.rejectsModel(priceList(), "/PriceList", "../Tiers/RawBlob",
                "RepetitionNotUnique(Tiers/RawBlob)", "MVK_INVALID_RAW_TYPE");
    }

    @Test
    void aWildcardKeyIsRejected() {
        KernelLaws.rejectsModel(priceList(), "/PriceList", "../Tiers/Band",
                "RepetitionNotUnique(Tiers*/Band)", "MVK_NO_WILDCARDS_ALLOWED");
    }

    @Test
    void aGroupKeyIsRejected() {
        KernelLaws.rejectsModel(priceList(), "/PriceList", "../Tiers/Band",
                "RepetitionNotUnique(Tiers)", "MVK_NO_GROUPS_ALLOWED");
    }
}

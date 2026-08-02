package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * KERNEL-FINDINGS §9 — the semantic index (`For`) is rejected inside <b>NumberOfFilledGroups</b> and
 * <b>RepetitionNotUnique</b> ({@code MVK_SEMANTIC_INDEX_NOT_ALLOWED}; source
 * {@code CheckFeldListenAnzahlOpImpl}/{@code CheckWiederholungBedingungImpl}), and <b>accepted</b> in
 * NumberOfFilledFields. This <i>contradicts</i> the BA reference (L902), which says "not supported for
 * NumberOfFilledFields and RepetitionNotUnique" — a Felder/Kontexte slip in the prose.
 */
class SemanticIndexSlotLawsTest {

    private static final String FILTERED_INDEXES = DocumentModelDraft.named("selection").locale("en_US")
            .rootGroup("Selection", root -> root
                    .numberField("Fallback", field -> { })
                    .stringField("FallbackText", field -> { })
                    .group("Address", address -> address.stringField("Street", field -> { }))
                    .group("FilterRows", rows -> rows.repeatable(10).numberField("Candidate", field -> { }))
                    .group("Indexed", rows -> rows.repeatable(10).indexField("Key")
                            .numberField("Primary", field -> { })
                            .numberField("Key", field -> { })))
            .build().toJson();

    @Test
    void numberOfFilledGroupsRejectsASemanticIndex() {
        KernelLaws.rejectsOrder("/Order", "../CustomerName",
                "FieldFilled(CustomerName) And NumberOfFilledGroups(Items For \"SKU-1\") > 0",
                "MVK_SEMANTIC_INDEX_NOT_ALLOWED");
    }

    @Test
    void numberOfFilledFieldsAcceptsASemanticIndex() {
        KernelLaws.acceptsOrder("/Order", "../CustomerName",
                "FieldFilled(CustomerName) And NumberOfFilledFields(Items/Count For \"SKU-1\", Items/UnitWeight For \"SKU-1\") > 0");
    }

    @Test
    void repetitionNotUniqueRejectsASemanticIndex() {
        KernelLaws.rejectsOrder("/Order", "../Items/Sku",
                "RepetitionNotUnique(Items/Sku For \"SKU-1\")", "MVK_SEMANTIC_INDEX_NOT_ALLOWED");
    }

    @Test
    void aSemanticIndexUnderAStarredGroupIsRejected() {
        KernelLaws.rejectsOrder("/Order", "../CustomerName",
                "FieldFilled(CustomerName) And [Items*/Count For \"SKU-1\"] > 0", "MVK_SEMANTIC_INDEX_AND_WILDCARD");
    }

    @Test
    void havingAdmitsAnOuterPlainFirstFilledGroupButRejectsBothIndexedShapes() {
        String prefix = "NumberOfFilledFields(FilterRows*/Candidate Having "
                + "[FilterRows/Candidate] == [$Fallback] And ";
        KernelLaws.acceptsModel(FILTERED_INDEXES, "/Selection", "../Fallback",
                prefix + "FirstFilledValue($Address, FallbackText) == \"outer\") > 0");
        KernelLaws.rejectsModel(FILTERED_INDEXES, "/Selection", "../Fallback",
                prefix + "FirstFilledValue($Indexed For \"7\", Fallback) > 0) > 0",
                "MVK_SEMANTIC_INDEX_NOT_ALLOWED");
        KernelLaws.rejectsModel(FILTERED_INDEXES, "/Selection", "../Fallback",
                prefix + "FirstFilledValue($Indexed For $Fallback, Fallback) > 0) > 0",
                "MVK_SEMANTIC_INDEX_NOT_ALLOWED");
    }
}

package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import org.junit.jupiter.api.Test;

/**
 * KERNEL-FINDINGS §9 — the semantic index (`For`) is rejected inside <b>NumberOfFilledGroups</b> and
 * <b>RepetitionNotUnique</b> ({@code MVK_SEMANTIC_INDEX_NOT_ALLOWED}; source
 * {@code CheckFeldListenAnzahlOpImpl}/{@code CheckWiederholungBedingungImpl}), and <b>accepted</b> in
 * NumberOfFilledFields. This <i>contradicts</i> the BA reference (L902), which says "not supported for
 * NumberOfFilledFields and RepetitionNotUnique" — a Felder/Kontexte slip in the prose.
 */
class SemanticIndexSlotLawsTest {

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
}

package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import io.github.mbackschat.a12.dm.ModelDrafts;
import org.junit.jupiter.api.Test;

/**
 * KERNEL-FINDINGS §8 — the {@code ==}/{@code !=} string/enum compatibility matrix (BA L1712-1742). A
 * <b>texts-bearing</b> enum (values carrying per-locale display texts) is incomparable with a plain
 * string and with a texts-less enum; two texts-bearing enums compare only when their value-to-text maps
 * agree on the overlap. A string and a texts-less enum DO compare.
 */
class EnumComparabilityLawsTest {

    private static final String ENUMS = ModelDrafts.enumComparability().toJson();

    @Test
    void stringComparesToATextlessEnum() {
        KernelLaws.acceptsModel(ENUMS, "/Doc", "../Str",
                "AllFieldsFilled(Str, NoText) And [Str] == [NoText]");
    }

    @Test
    void textBearingEnumsWithMatchingMapsCompare() {
        KernelLaws.acceptsModel(ENUMS, "/Doc", "../WithText",
                "AllFieldsFilled(WithText, WithText2) And [WithText] == [WithText2]");
    }

    @Test
    void stringCannotCompareToATextBearingEnum() {
        KernelLaws.rejectsModel(ENUMS, "/Doc", "../Str",
                "AllFieldsFilled(Str, WithText) And [Str] == [WithText]", "MVK_INVALID_COMPARE_ENUM_TO_STRING");
    }

    @Test
    void textlessAndTextBearingEnumsCannotCompare() {
        KernelLaws.rejectsModel(ENUMS, "/Doc", "../NoText",
                "AllFieldsFilled(NoText, WithText) And [NoText] == [WithText]", "MVK_INVALID_COMPARE_ENUM_TO_STRING");
    }
}

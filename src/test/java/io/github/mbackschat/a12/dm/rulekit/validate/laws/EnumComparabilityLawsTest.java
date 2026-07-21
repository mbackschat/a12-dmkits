package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import io.github.mbackschat.a12.dm.ModelDrafts;
import org.junit.jupiter.api.Test;

/**
 * Kernel 30.8.1's effective-display comparability gate for direct string/enumeration fields. An enum is
 * display-bearing only when at least one localized display differs from its stored token, so identity labels
 * remain textless. Two display-bearing enums need a consistent partial bijection in every shared locale.
 *
 * <p>Source: {@code IEnumerationType.hasDisplayValues}, {@code FieldUtils.isEnumWithDisplayValues},
 * {@code CheckVergleichsBedingungImpl}, and {@code CheckEntityListenUtils.checkDisplayValues}.
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
    void identityLabelsAreEffectivelyTextlessForStringInequality() {
        KernelLaws.acceptsModel(ENUMS, "/Doc", "../Str",
                "AllFieldsFilled(Str, IdentityText) And [Str] != [IdentityText]");
    }

    @Test
    void identityLabelsAreEffectivelyTextlessForEnumEquality() {
        KernelLaws.acceptsModel(ENUMS, "/Doc", "../IdentityText",
                "AllFieldsFilled(IdentityText, NoText) And [IdentityText] == [NoText]");
    }

    @Test
    void sharedStoredTokenMustHaveOneDisplay() {
        KernelLaws.rejectsModel(ENUMS, "/Doc", "../WithText",
                "AllFieldsFilled(WithText, ForwardConflict) And [WithText] == [ForwardConflict]",
                "MVK_INVALID_COMPARE_ENUM_TO_ENUM");
    }

    @Test
    void sharedDisplayMustDenoteOneStoredToken() {
        KernelLaws.rejectsModel(ENUMS, "/Doc", "../WithText",
                "AllFieldsFilled(WithText, ReverseConflict) And [WithText] == [ReverseConflict]",
                "MVK_INVALID_COMPARE_ENUM_TO_ENUM");
    }

    @Test
    void identityMappingParticipatesOnceBothDeclarationsAreDisplayBearing() {
        KernelLaws.rejectsModel(ENUMS, "/Doc", "../IdentityMixed",
                "AllFieldsFilled(IdentityMixed, WithText) And [IdentityMixed] == [WithText]",
                "MVK_INVALID_COMPARE_ENUM_TO_ENUM");
    }

    @Test
    void partiallyOverlappingCompatibleMapsCompare() {
        KernelLaws.acceptsModel(ENUMS, "/Doc", "../WithText",
                "AllFieldsFilled(WithText, PartialCompatible) And [WithText] == [PartialCompatible]");
    }

    @Test
    void disjointCompatibleMapsCompare() {
        KernelLaws.acceptsModel(ENUMS, "/Doc", "../WithText",
                "AllFieldsFilled(WithText, DisjointCompatible) And [WithText] == [DisjointCompatible]");
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

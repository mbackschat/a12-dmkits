package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupListRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;

/**
 * §9 / SPEC-2026-07-26-01 — ordinary-iteration admission for a starred group-list operand is decided per
 * queried level and per operator. The star contributes only its captured prefix: AtLeastOneGroupFilled is
 * guarded when every list operand references that level, while NoGroupFilled is unguarded when any does.
 * The existing And/Or rules then compose those leaf classifications.
 */
class StarredGroupListIterationAdmissionLawsTest {

    private final String modelJson = DocumentModelDraft.named("regional-survey").locale("en_US")
            .rootGroup("Survey", survey -> survey
                    .group("Profile", profile -> profile.stringField("Owner", ignored -> { }))
                    .group("Regions", regions -> {
                        regions.repeatable(5);
                        regions.group("Checks", checks -> {
                            checks.repeatable(5);
                            checks.stringField("Result", ignored -> { });
                        });
                    }))
            .build().toJson();
    private final BoundModel model = BoundModel.ofJson(modelJson);
    private final GroupListRef checks = model.flattenGroup(CHECKS + "*");
    private final GroupListRef profile = model.flattenGroup(PROFILE);
    private final Condition regionGuard = model.group(REGIONS).filled();

    @Test
    void starOnlyPositiveReferencesItsCapturedPrefixAndIsAdmitted() {
        KernelLaws.acceptsModel(modelJson,
                rule("StarOnlyPositive", Dsl.atLeastOneGroupFilled(checks)));
    }

    @Test
    void fixedPeerMakesTheMixedPositiveListUnguardedAtTheCapturedLevel() {
        KernelLaws.rejectsModel(modelJson,
                rule("MixedPositive", Dsl.atLeastOneGroupFilled(checks, profile)),
                "MVK_NEG_CONDITION_IN_ITERATION");
    }

    @Test
    void starOnlyNegativeIsUnguardedAtItsCapturedLevel() {
        KernelLaws.rejectsModel(modelJson,
                rule("StarOnlyNegative", Dsl.noGroupFilled(checks)),
                "MVK_NEG_CONDITION_IN_ITERATION");
    }

    @Test
    void independentIteratingAndGuardAdmitsTheNegativeList() {
        KernelLaws.acceptsModel(modelJson,
                rule("GuardedNegative", regionGuard.and(Dsl.noGroupFilled(checks))));
    }

    @Test
    void theSameGuardUnderOrDoesNotRescueTheNegativeList() {
        KernelLaws.rejectsModel(modelJson,
                rule("OrNegative", regionGuard.or(Dsl.noGroupFilled(checks))),
                "MVK_NEG_CONDITION_IN_ITERATION");
    }

    private Rule rule(String name, Condition condition) {
        return model.rule(model.group(REGIONS))
                .name(name)
                .errorField(model.str(RESULT))
                .when(condition)
                .code(name)
                .severity(Severity.ERROR)
                .message(Dsl.en("Regional check state is inconsistent."))
                .build();
    }

    private static final String PROFILE = "/Survey/Profile";
    private static final String REGIONS = "/Survey/Regions";
    private static final String CHECKS = "/Survey/Regions/Checks";
    private static final String RESULT = "/Survey/Regions/Checks/Result";
}

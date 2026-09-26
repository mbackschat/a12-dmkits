package io.github.mbackschat.a12.dm.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.LocalizedText;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;

/**
 * {@link ModelIndex#rules()} — the kernel-free enumeration of a model's authored rules, each with the content a
 * consumer needs to reason about or re-host it, and its error entity resolved against the rule element as
 * {@link ModelIndex#computations()} resolves a computation's target.
 */
class ModelIndexRulesTest {

    private static final String CLAIM = DocumentModelDraft.named("claim-rules").locale("en_US")
            .rootGroup("Claim", c -> c.numberField("Damage", n -> n.fractionDigits(2))
                    .group("Repairs", r -> r.repeatable(10).numberField("Hours", n -> n.fractionDigits(1))))
            .build().toJson();

    /** {@code DamageKnown} is appended to {@code /Claim} after the {@code Repairs} group, so it is listed second. */
    @Test
    void everyRuleIsEnumeratedInModelOrderWithItsContent() {
        RuleValidator validator = new RuleValidator();
        String model = validator.inject(validator.inject(CLAIM,
                        DraftRule.builder().groupPath("/Claim").name("DamageKnown").errorEntityRelPath("../Damage")
                                .errorCondition("FieldNotFilled(Damage)").errorCode("DAMAGE_MISSING")
                                .severity("WARNING").messages(List.of(new LocalizedText("en_US", "Enter the damage.")))
                                .build()),
                DraftRule.builder().groupPath("/Claim/Repairs").name("HoursPositive").errorEntityRelPath("../Hours")
                        .errorCondition("[Hours] < 0").errorCode("HOURS_NEGATIVE").severity("ERROR")
                        .messages(List.of(new LocalizedText("en_US", "Hours cannot be negative."))).build());

        // tag::model-rules[]
        // every authored rule, in model order, with its content as written:
        List<RuleInfo> rules = ModelIndex.ofSelfContained(model).rules();
        RuleInfo damage = rules.get(1);
        damage.errorCondition();    // "FieldNotFilled(Damage)"
        damage.severity();          // "WARNING"; "ERROR" where none is declared, A12's default
        damage.messages();          // {en_US=Enter the damage.}, locale to text
        damage.errorEntityPath();   // Optional[/Claim/Damage]: "../Damage" resolved (rel base = the element)
        // end::model-rules[]

        assertEquals(List.of(
                RuleInfo.builder().path("/Claim/Repairs/HoursPositive").groupPath("/Claim/Repairs")
                        .name("HoursPositive").errorEntityRelPath("../Hours").errorCondition("[Hours] < 0")
                        .errorCode("HOURS_NEGATIVE").severity("ERROR")
                        .messages(Map.of("en_US", "Hours cannot be negative.")).build(),
                RuleInfo.builder().path("/Claim/DamageKnown").groupPath("/Claim").name("DamageKnown")
                        .errorEntityRelPath("../Damage").errorCondition("FieldNotFilled(Damage)")
                        .errorCode("DAMAGE_MISSING").severity("WARNING")
                        .messages(Map.of("en_US", "Enter the damage.")).build()),
                rules);
        assertEquals(List.of(Optional.of("/Claim/Repairs/Hours"), Optional.of("/Claim/Damage")),
                rules.stream().map(RuleInfo::errorEntityPath).toList(), "the error entity, resolved");
    }

    /** A12's rule defaults its severity to ERROR ({@code a12internal.Rule}, {@code severity = Severity.ERROR}). */
    @Test
    void aRuleWithoutASeverityOrErrorEntityIsAnErrorOnNoEntity() {
        String model = new RuleValidator().inject(CLAIM, DraftRule.builder().groupPath("/Claim").name("Bare")
                .errorCondition("FieldFilled(Damage)").errorCode("BARE").build());

        RuleInfo bare = ModelIndex.ofSelfContained(model).rules().get(0);

        assertEquals("ERROR", bare.severity());
        assertEquals("", bare.errorEntityRelPath());
        assertEquals(Optional.empty(), bare.errorEntityPath(), "no error entity was authored");
        assertEquals(Map.of("en_US", "Bare"), bare.messages(), "the injected placeholder text");
    }

    /** An included model's rule is listed where it is mounted, its condition rebound as the include expansion does. */
    @Test
    void aMountedRuleIsListedAtItsMountedPathWithItsConditionRebound() {
        RuleValidator validator = new RuleValidator();
        String repairs = validator.inject(DocumentModelDraft.named("repairs").locale("en_US")
                        .rootGroup("Repairs", r -> r.numberField("Hours", n -> n.fractionDigits(1)))
                        .build().toJson(),
                DraftRule.builder().groupPath("/Repairs").name("HoursPositive").errorEntityRelPath("../Hours")
                        .errorCondition("[/Repairs/Hours] < 0").errorCode("HOURS_NEGATIVE").severity("ERROR")
                        .build());
        String claim = DocumentModelDraft.named("claim").locale("en_US")
                .include("repairs", "repairs")
                .rootGroup("Claim", c -> c.numberField("Damage", n -> n.fractionDigits(2)).mount("Work", "repairs"))
                .build().toJson();

        RuleInfo mounted = ModelIndex.of(claim, Map.of("repairs", repairs)::get).rules().get(0);

        assertEquals("/Claim/Work/HoursPositive", mounted.path());
        assertEquals("[/Claim/Work/Hours] < 0", mounted.errorCondition());
        assertEquals(Optional.of("/Claim/Work/Hours"), mounted.errorEntityPath());
    }

    @Test
    void aModelWithoutRulesHasNone() {
        assertEquals(List.of(), ModelIndex.ofSelfContained(CLAIM).rules());
    }
}

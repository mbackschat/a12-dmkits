package io.github.mbackschat.a12.dm.adapter;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldPresenceCondition;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupListPredicate;
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupListRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.LogicCondition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;

/**
 * The fixed group-list quantifiers admit the common field-or-group entity list
 * (SPEC-2026-07-23-09). Typed read-back must round-trip a mixed or field-only list into the same
 * {@link io.github.mbackschat.a12.dm.rulekit.dsl.GroupListOperand} sum the author built — a checked
 * field as a {@link FieldRef}, a group scope as a {@link GroupListRef} — while the fixed singleton
 * {@code NoGroupFilled(F)} canonicalizes to scalar field presence exactly like its group sibling.
 */
class GroupListEntityOperandReadTest {

    private static final DslRenderer RENDERER = new DslRenderer();

    private final BoundModel model = BoundModel.ofJson(DocumentModelDraft.named("claimintake").locale("en_US")
            .rootGroup("Claim", claim -> claim
                    .stringField("PolicyNumber", ignored -> { })
                    .stringField("IncidentNote", ignored -> { })
                    .group("Vehicle", vehicle -> vehicle.stringField("Vin", ignored -> { }))
                    .group("Damages", damages -> damages.repeatable(5).stringField("Detail", ignored -> { })))
            .build().toJson());

    @Test
    void aMixedFieldGroupListRoundTrips() {
        // tag::group-list-entity-operands[]
        Condition authored = model.field("/Claim/PolicyNumber").filled()
                .and(Dsl.atLeastOneGroupFilled(
                        model.field("/Claim/IncidentNote"),
                        model.flattenGroup("/Claim/Vehicle")));
        // end::group-list-entity-operands[]
        GroupListPredicate read = readBack("MixedList", authored);

        assertEquals(
                List.of(model.field("/Claim/IncidentNote"), model.flattenGroup("/Claim/Vehicle")),
                read.operands());
    }

    @Test
    void aFieldOnlyListRoundTrips() {
        Condition authored = model.field("/Claim/PolicyNumber").filled()
                .and(Dsl.notAllGroupsFilled(
                        model.field("/Claim/IncidentNote"),
                        model.field("/Claim/Vehicle/Vin")));
        GroupListPredicate read = readBack("FieldOnly", authored);

        assertEquals(
                List.of(model.field("/Claim/IncidentNote"), model.field("/Claim/Vehicle/Vin")),
                read.operands());
    }

    /** The fixed singleton has the scalar presence semantics — `NoGroupFilled(F)` reads back as
     *  `FieldNotFilled(F)`, mirroring the single-group `NoGroupFilled(G)` → `GroupNotFilled(G)` case. */
    @Test
    void aSingletonFieldNoGroupFilledCanonicalizesToScalarPresence() {
        Condition authored = model.field("/Claim/PolicyNumber").filled()
                .and(Dsl.noGroupFilled(model.field("/Claim/IncidentNote")));
        Rule rule = rule("SingleNo", authored);

        Condition converted = converted(KernelAdapter.of(model.add(rule)).read("/Claim/SingleNo"));
        FieldPresenceCondition presence = assertInstanceOf(FieldPresenceCondition.class,
                assertInstanceOf(LogicCondition.class, converted).right());

        assertFalse(presence.filled());
        assertEquals("/Claim/IncidentNote", presence.field().path());
    }

    /** The ≥1 singleton stays a one-operand list, mirroring the single-group `AtLeastOneGroupFilled(G)`. */
    @Test
    void aSingletonFieldAtLeastOneGroupFilledStaysAList() {
        Condition authored = model.field("/Claim/PolicyNumber").filled()
                .and(Dsl.atLeastOneGroupFilled(model.field("/Claim/IncidentNote")));
        GroupListPredicate read = readBack("SingleOne", authored);

        assertEquals(List.of(model.field("/Claim/IncidentNote")), read.operands());
    }

    /**
     * SPEC-2026-07-23-09 follow-up — the kernel's direct-duplicate gate skips a wildcarded operand, so the same
     * starred group may occupy several authored slots. BOTH occurrences must survive rendering and read-back:
     * the operand list is the evaluator's input, and silently collapsing it to one would change the tally the
     * kernel computes. ({@link #readBack} additionally asserts the rendered forms match, so a dropped occurrence
     * fails on the text too.)
     */
    @Test
    void aRepeatedStarredGroupRetainsBothOccurrences() {
        GroupListRef damages = model.flattenGroup("/Claim/Damages*");
        Condition authored = model.field("/Claim/PolicyNumber").filled()
                .and(Dsl.noGroupFilled(damages, damages));
        GroupListPredicate read = readBack("RepeatedStarNone", authored);

        assertEquals(List.of(damages, damages), read.operands());
    }

    @Test
    void aRepeatedStarredGroupRetainsBothOccurrencesForTheCountAtLeastOne() {
        GroupListRef damages = model.flattenGroup("/Claim/Damages*");
        Condition authored = model.field("/Claim/PolicyNumber").filled()
                .and(Dsl.atLeastOneGroupFilled(damages, damages));
        GroupListPredicate read = readBack("RepeatedStarOne", authored);

        assertEquals(List.of(damages, damages), read.operands());
    }

    private GroupListPredicate readBack(String name, Condition authored) {
        Rule rule = rule(name, authored);
        Condition converted = converted(KernelAdapter.of(model.add(rule)).read("/Claim/" + name));
        assertEquals(RENDERER.render(authored), RENDERER.render(converted));
        return assertInstanceOf(GroupListPredicate.class,
                assertInstanceOf(LogicCondition.class, converted).right());
    }

    private Rule rule(String name, Condition condition) {
        return model.rule(model.group("/Claim")).name(name)
                .errorField(model.field("/Claim/PolicyNumber")).when(condition)
                .code("CLM").severity(Severity.ERROR).message(en("claim intake incomplete")).build();
    }

    private static Condition converted(ReadResult result) {
        return assertInstanceOf(ReadResult.Converted.class, result,
                () -> "expected a converted rule, got " + result).condition();
    }
}

package io.github.mbackschat.a12.dm.adapter.laws;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.ComparisonCondition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.CountAggregate;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldListRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupPresenceCondition;
import io.github.mbackschat.a12.dm.rulekit.dsl.LogicCondition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;

/**
 * A {@code $}-correlated GROUP-PRESENCE leaf inside a {@code Having} filter survives typed read-back.
 *
 * <p><b>Why the read path gets its own test, and why it is the sharper half.</b> The kernel admits
 * {@code GroupFilled($G)} inside a filter beside an in-scope conjunct (KERNEL-FINDINGS §9,
 * a12-kernel-lean's {@code SPEC-2026-09-04-05}), so a real model may carry one. Before
 * {@code GroupPresenceCondition} could hold the marker, that model was not merely unauthorable — the
 * converter's {@code verifyOuterPreserved} backstop would count one source marker against none rendered and
 * degrade the whole rule to a verbatim {@link ReadResult.Opaque}. An over-strict client check on the read side
 * makes a legal model UNREADABLE rather than only unwritable, which is the half that is easy to miss.
 *
 * <p><b>The assertions are three, because a render round-trip alone is satisfiable by two wrongs.</b> The read
 * must be {@code Converted} rather than opaque; the AST node must actually carry {@code outer()}; and the
 * re-render must equal the authored condition. Dropping the marker on BOTH sides would pass the third alone.
 *
 * <p><b>The control</b> is the same rule with the marker absent: it reads back with {@code outer()} false, so
 * the flag tracks the source rather than being set unconditionally.
 *
 * <p>Own domain: an insurance claim whose line items are filtered while an adjuster group's presence is read
 * from the captured outer environment.
 */
class CorrelatedGroupPresenceReadTest {


    @Test
    void aMarkedGroupPresenceLeafInAFilterReadsBackWithItsMarker() {
        BoundModel model = claimModel();
        Condition authored = filteredCount(model, Dsl.groupFilled(model.group("/Claim/Adjuster"), true));

        GroupPresenceCondition presence = presenceLeafOf(model, "MarkedAdjuster", authored);

        assertTrue(presence.outer(), "the $ marker must survive read-back, not be normalized away");
        assertTrue(presence.filled());
        assertEquals(RuntimeLaws.render(authored),
                RuntimeLaws.render(readCondition(model, "MarkedAdjuster", authored)));
    }

    @Test
    void theUnmarkedSpellingReadsBackUnmarked() {
        BoundModel model = claimModel();
        Condition authored = filteredCount(model, Dsl.groupFilled(model.group("/Claim/Adjuster")));

        GroupPresenceCondition presence = presenceLeafOf(model, "PlainAdjuster", authored);

        assertFalse(presence.outer(), "an unmarked source must not acquire a marker on the way back");
        assertEquals(RuntimeLaws.render(authored),
                RuntimeLaws.render(readCondition(model, "PlainAdjuster", authored)));
    }

    // ---- fixtures --------------------------------------------------------------------------------

    /**
     * The admitted shape: the group leaf sits beside an in-scope conjunct, which is what binds the filter's
     * iteration. As the SOLE condition the kernel refuses it ({@code MVK_NO_ITERATION_FOR_WILDCARD}) whichever
     * spelling is used, so it could not be added to a model here at all.
     */
    private static Condition filteredCount(BoundModel model, Condition groupLeaf) {
        return Dsl.fieldFilled(model.field("/Claim/Reference"))
                .and(Dsl.numberOfFilledFields(model.flatten("/Claim/Items*/Code")
                        .having(model.num("/Claim/Items/Amount").gt(1).and(groupLeaf))).gt(0));
    }

    private static GroupPresenceCondition presenceLeafOf(BoundModel model, String name, Condition authored) {
        Condition filter = assertInstanceOf(LogicCondition.class, authored).right();
        // The authored tree is the oracle for WHERE the leaf sits; the read tree is compared to it whole by
        // the render assertion, and this navigation pulls the leaf out of the READ tree at that position.
        Condition read = readCondition(model, name, authored);
        Condition readFilter = assertInstanceOf(LogicCondition.class, read).right();
        assertEquals(filter.getClass(), readFilter.getClass(), "the read tree must have the authored shape");
        return assertInstanceOf(GroupPresenceCondition.class,
                assertInstanceOf(LogicCondition.class, filterConditionOf(readFilter)).right(),
                "the group-presence leaf must survive as a typed node");
    }

    /** The {@code Having} condition of the count's filtered list operand. */
    private static Condition filterConditionOf(Condition count) {
        CountAggregate aggregate = assertInstanceOf(CountAggregate.class,
                assertInstanceOf(ComparisonCondition.class, count).left());
        return assertInstanceOf(FieldListRef.class, aggregate.operands().getFirst()).filter().orElseThrow();
    }

    private static Condition readCondition(BoundModel model, String name, Condition authored) {
        Rule rule = model.rule(model.group("/Claim")).name(name)
                .errorField(model.field("/Claim/Reference")).when(authored)
                .code("CLM").severity(Severity.ERROR).message(en("claim incomplete")).build();
        ReadResult result = KernelAdapter.of(model.add(rule)).read("/Claim/" + name);
        return assertInstanceOf(ReadResult.Converted.class, result,
                () -> "a legal $-marked group leaf must not degrade to a verbatim read: " + result).condition();
    }

    private static BoundModel claimModel() {
        return BoundModel.ofJson(DocumentModelDraft.named("claim-correlated-presence").locale("en_US")
                .rootGroup("Claim", root -> root
                        .stringField("Reference", ignored -> { })
                        .group("Items", items -> items.repeatable(9)
                                .stringField("Code", ignored -> { })
                                .numberField("Amount", ignored -> { }))
                        .group("Adjuster", adjuster -> adjuster.stringField("Name", ignored -> { })))
                .build().toJson());
    }
}

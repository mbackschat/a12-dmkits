package io.github.mbackschat.a12.dm.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;

/**
 * The READ half of the keyed DATE_RANGE operand ([KF205](../../../../../../../../../../docs/KERNEL-FINDINGS.md)).
 *
 * <p><b>Why this exists beside the authoring law.</b> Shipping {@code DateRangeRef.forIndex} made a shape
 * authorable that the kernel had always admitted; the converter could read neither side of it, and the two
 * sides failed DIFFERENTLY. On the left of a comparison it threw and degraded the rule to {@code Opaque} —
 * loud, and merely unreadable. On the right it built a bare {@code DateRangeRef} and **silently dropped the
 * key**, so the rule round-tripped to a different, still-legal condition that reported itself
 * {@code Converted}. That second one is the wrong-value failure the project's "check the READ path too" rule
 * names, and it is why an authoring capability is not finished until its read is measured.
 *
 * <p>{@link #anUnthreadedSemanticIndexPositionIsRefusedRatherThanDropped} is the class lock: a per-site fix
 * leaves the next unthreaded position silent, so the drop now has the same backstop the {@code $} correlation
 * and the enum-category projection already had.
 *
 * <p>Own domain (e-commerce seasonal promotions).
 */
class KeyedRangeReadBackTest {

    private static final DslRenderer RENDER = new DslRenderer(ConditionLanguage.EN);

    private static String shopJson() {
        return DocumentModelDraft.named("promo-read").locale("en_US")
                .rootGroup("Shop", s -> s
                        .stringField("Reference", f -> { })
                        .dateRangeField("SeasonWindow", r -> r.format("yyyy-MM-dd").separator("/"))
                        .dateRangeField("ClearanceWindow", r -> r.format("yyyy-MM-dd").separator("/"))
                        .group("Runs", g -> g.repeatable(20).indexField("RunKey")
                                .stringField("RunKey", f -> { })
                                .dateRangeField("RunWindow", r -> r.format("yyyy-MM-dd").separator("/"))))
                .build().toJson();
    }

    /** The key on the LEFT of the comparison — the position that used to throw. */
    @Test
    void aKeyedRangeOnTheLeftRoundTripsWithItsKey() {
        BoundModel m = BoundModel.ofJson(shopJson());
        Condition keyed = m.dateRange("/Shop/Runs/RunWindow").forIndex("spring")
                .eq(m.dateRange("/Shop/SeasonWindow"));

        assertRoundTrips(m, "LeftRule", keyed);
    }

    /** The key on the RIGHT — the position that used to drop it and still report the rule converted. */
    @Test
    void aKeyedRangeOnTheRightRoundTripsWithItsKey() {
        BoundModel m = BoundModel.ofJson(shopJson());
        Condition keyed = m.dateRange("/Shop/SeasonWindow")
                .eq(m.dateRange("/Shop/Runs/RunWindow").forIndex("spring"));

        assertRoundTrips(m, "RightRule", keyed);
    }

    /** The field-keyed form travels the same way — the key is an entity, not only a literal. */
    @Test
    void aFieldKeyedRangeRoundTripsWithItsKey() {
        BoundModel m = BoundModel.ofJson(shopJson());
        Condition keyed = m.dateRange("/Shop/Runs/RunWindow").forIndex(m.field("/Shop/Reference"))
                .eq(m.dateRange("/Shop/SeasonWindow"));

        assertRoundTrips(m, "FieldKeyRule", keyed);
    }

    /**
     * The bare control: a range comparison with no key must NOT grow one. Both operands are non-repeatable
     * because the unkeyed ROW operand is refused from this locus outright ({@code MVK_NO_WILDCARD} — the locus
     * law of [KF204]), which is itself half the point of the keyed form.
     */
    @Test
    void anUnkeyedRangeComparisonStillRoundTripsUnkeyed() {
        BoundModel m = BoundModel.ofJson(shopJson());
        Condition bare = m.dateRange("/Shop/ClearanceWindow").eq(m.dateRange("/Shop/SeasonWindow"));

        String rendered = assertRoundTrips(m, "BareRule", bare);
        assertTrue(!rendered.contains(" For "), () -> "an unkeyed comparison must not gain a key: " + rendered);
    }

    /**
     * The class lock. A position the converter has not yet threaded must degrade the subject to a verbatim
     * {@code Opaque}, never produce a converted-looking rule with the key erased. Asserted on the backstop
     * directly because every position reachable today IS threaded — which is exactly when a guard stops being
     * testable through the front door and has to be locked at its own seam.
     */
    @Test
    void anUnthreadedSemanticIndexPositionIsRefusedRatherThanDropped() {
        ConditionConverter converter = new ConditionConverter(BoundModel.ofJson(shopJson()));

        UnsupportedConstructException refused = assertThrows(UnsupportedConstructException.class,
                () -> converter.verifySemanticIndexPreserved("[/Shop/Runs/RunWindow] == [/Shop/SeasonWindow]", 1));
        assertTrue(refused.getMessage().contains("silently drop a semantic index"),
                () -> "the backstop names what was dropped: " + refused.getMessage());

        // …and it does NOT fire when the key survived, in either language's rendering of the operator.
        converter.verifySemanticIndexPreserved("[/Shop/Runs/RunWindow For \"spring\"] == [/Shop/SeasonWindow]", 1);
        converter.verifySemanticIndexPreserved("[/Shop/Runs/RunWindow Fuer \"spring\"] == [/Shop/SeasonWindow]", 1);
    }

    /** Every row rides a {@code FieldFilled(Reference)} guard so the kernel's error-field gate is met and the
     *  read-back under test is the only thing the assertion can fail on. */
    private static String assertRoundTrips(BoundModel m, String name, Condition condition) {
        Condition guarded = Dsl.fieldFilled(m.field("/Shop/Reference")).and(condition);
        KernelAdapter adapter = KernelAdapter.of(m.add(rule(m, name, guarded)));
        ReadResult read = adapter.read("/Shop/" + name);
        ReadResult.Converted converted = assertInstanceOf(ReadResult.Converted.class, read,
                () -> "expected a converted read, got: " + read);
        String rendered = RENDER.render(converted.condition());
        assertEquals(RENDER.render(guarded), rendered, "the read-back condition must be the authored one");
        return rendered;
    }

    private static Rule rule(BoundModel m, String name, Condition condition) {
        return m.rule(m.group("/Shop")).name(name).errorField(m.field("/Shop/Reference"))
                .when(condition).code("KEYED").severity(Severity.ERROR)
                .message(Dsl.en("Keyed range read-back probe.")).build();
    }
}

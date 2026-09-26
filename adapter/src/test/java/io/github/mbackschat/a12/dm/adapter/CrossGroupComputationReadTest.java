package io.github.mbackschat.a12.dm.adapter;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.de;
import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation;
import io.github.mbackschat.a12.dm.rulekit.read.ReadComputation;

/**
 * The read direction for a computation <b>declared outside its computed field's parent group</b> — the shape
 * {@code Computation.Builder.group(...)} authors and A12 has always been able to store.
 *
 * <p><b>What was broken.</b> The converter derived the declaring group from the COMPUTED FIELD's path rather
 * than from the computation element's own, so every cross-group computation read back claiming a placement the
 * model does not have. Nothing threw and the calculated field was right, which is why it survived: the damage
 * only appears one step later, because {@code groupPath} is both the base the operand paths render against and
 * the group a write-back inserts into. A read → write-back therefore MOVED the element into the target's parent
 * and rewrote its stored reference to match. {@link #aCrossGroupComputationSurvivesReadModifyWriteBack} is the
 * test that shows the consequence rather than just the field.
 */
class CrossGroupComputationReadTest {

    private static final String EFFECTIVE = "/Subscription/Billing/EffectiveFee";
    private static final String BASE = "/Subscription/Billing/BaseFee";
    /** Declared one level above its computed field — the element is /Subscription/EffectiveFeeComp. */
    private static final String DECLARING_GROUP = "/Subscription";
    private static final String COMPUTATION = "/Subscription/EffectiveFeeComp";

    private static BoundModel base() {
        return BoundModel.ofJson(TestResources.text("models/subscription.dm.json"));
    }

    private static Computation crossGroup(BoundModel m) {
        return m.computation(m.num(EFFECTIVE)).name("EffectiveFeeComp")
                .group(m.group(DECLARING_GROUP))
                .calc(m.num(BASE).value())
                .message(en("EffectiveFee must equal BaseFee."), de("Effektivgebühr = Basisgebühr."))
                .build();
    }

    @Test
    void theDeclaringGroupIsReadFromTheComputationElementNotFromItsTarget() {
        BoundModel m = base();
        Computation got = read(m.add(crossGroup(m)), COMPUTATION);

        assertEquals(DECLARING_GROUP, got.groupPath(),
                "the computation lives in /Subscription; /Subscription/Billing is only where it writes");
        assertEquals(EFFECTIVE, got.computedField().path().value());
        assertEquals("../Billing/EffectiveFee", got.computedFieldRelPath());
    }

    /** The whole point of getting {@code groupPath} right: the element must not move when it is written back. */
    @Test
    void aCrossGroupComputationSurvivesReadModifyWriteBack() {
        BoundModel m = base();
        BoundModel withComp = m.add(crossGroup(m));

        Computation current = read(withComp, COMPUTATION);
        BoundModel applied = withComp.replaceComputation(COMPUTATION, current);

        assertEquals(java.util.Set.of(COMPUTATION), KernelAdapter.of(applied).computationFullNames(),
                "a write-back of what was read must leave the computation exactly where it was");
        assertEquals(current.draft(), read(applied, COMPUTATION).draft());
    }

    /** The default placement must read back exactly as before — the byte-identity half of the change. */
    @Test
    void aComputationBesideItsFieldStillReadsBackWithThatGroup() {
        BoundModel m = base();
        Computation beside = m.computation(m.num(EFFECTIVE)).name("EffectiveFeeComp")
                .calc(m.num(BASE).value())
                .message(en("EffectiveFee must equal BaseFee."), de("Effektivgebühr = Basisgebühr."))
                .build();

        Computation got = read(m.add(beside), "/Subscription/Billing/EffectiveFeeComp");
        assertEquals("/Subscription/Billing", got.groupPath());
        assertEquals("../EffectiveFee", got.computedFieldRelPath());
    }

    private static Computation read(BoundModel model, String fullName) {
        ReadComputation result = KernelAdapter.of(model).readComputation(fullName);
        return assertInstanceOf(ReadComputation.Converted.class, result,
                () -> "expected a converted computation, got: " + result).computation();
    }
}

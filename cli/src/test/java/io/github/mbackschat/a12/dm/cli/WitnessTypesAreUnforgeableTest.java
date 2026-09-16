package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * A witness to a consistency gate must be <b>obtainable only from a gate</b> — so every type
 * {@link ConsistencyEvidence} accepts as a witness hides its constructors.
 *
 * <p><b>The property, and why the type is the whole mechanism.</b> {@code ConsistencyEvidence} has no
 * {@code kernelConfirmed()} factory on purpose: evidence is minted by handing over an object whose <em>type</em>
 * is the proof that a gate ran. That inverts entirely if the type can be constructed by anyone —
 * {@code new Synthesized(anyJson, KERNEL_CONFIRMED)} then publishes a kernel receipt over JSON no kernel ever
 * saw, and the evidence layer cannot tell the difference. The witness is the load-bearing part; a record's
 * canonical constructor cannot be narrower than the record, so a {@code public record} witness is a hole by
 * construction.
 *
 * <p><b>Why a derived guard rather than a list.</b> {@link ReviewGateReceiptTest} pinned this for the two review
 * artefacts, and the same defect was then reintroduced three more times in one session, on types nobody thought
 * of as receipts — a synthesis result, an import result, and the validator's own result. Enumerating the witness
 * set by hand is what failed; so the set is read from {@code ConsistencyEvidence}'s own {@code of(…)} signatures
 * at runtime. A new witness factory is in scope the moment it is declared, without anyone remembering this file.
 *
 * @see ReviewGateReceiptTest the same property for the review artefacts, with the discriminating ungated case
 */
class WitnessTypesAreUnforgeableTest {

    /**
     * The one witness that cannot be closed, with the reason — an exception is thrown and caught, so it has to be
     * publicly constructible. Its receipt is correspondingly weaker: it attests "a gate refused" from the fact of
     * having been thrown by a gate call, which is why {@link ConsistencyEvidence#of(ModelConsistencyException)}
     * documents the two tiers it covers. Listed rather than silently skipped, so the exemption is visible.
     */
    private static final Map<String, String> CANNOT_BE_CLOSED = Map.of(
            "ModelConsistencyException",
            "an exception is constructed to be thrown; the witness is that a gate call threw it");

    /** Every witness type {@code ConsistencyEvidence} trusts, read from its own live signatures. */
    private static List<Class<?>> witnessTypes() {
        List<Class<?>> types = new ArrayList<>();
        for (Method m : ConsistencyEvidence.class.getDeclaredMethods()) {
            if (m.getName().equals("of") && Modifier.isPublic(m.getModifiers())
                    && Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 1) {
                types.add(m.getParameterTypes()[0]);
            }
        }
        return types;
    }

    /**
     * The guard would pass vacuously if the factories were renamed or the reflection stopped matching, which is
     * exactly the failure mode this whole effort kept producing. So the denominator is asserted first.
     */
    @Test
    void theWitnessSetIsActuallyBeingRead() {
        assertEquals(11, witnessTypes().size(),
                () -> "expected every ConsistencyEvidence.of(witness) overload to be found, got "
                        + witnessTypes() + ". Change this number only alongside a witness factory — a silent drop "
                        + "to zero would make the check below pass over an empty loop");
    }

    @Test
    void noWitnessTypeCanBeConstructedByItsConsumer() {
        List<String> forgeable = new ArrayList<>();
        for (Class<?> witness : witnessTypes()) {
            if (CANNOT_BE_CLOSED.containsKey(witness.getSimpleName())) {
                continue;
            }
            for (Constructor<?> c : witness.getDeclaredConstructors()) {
                int mods = c.getModifiers();
                if (Modifier.isPublic(mods) || Modifier.isProtected(mods)) {
                    forgeable.add(witness.getSimpleName() + "(" + c.getParameterCount() + " args)");
                }
            }
        }
        assertTrue(forgeable.isEmpty(),
                () -> "these gate witnesses can be constructed by the code that consumes them, so the receipt they "
                        + "carry proves nothing: " + forgeable + ". A witness is trusted BECAUSE only a gate can "
                        + "produce it — give it a private constructor and a factory that runs the gate (see "
                        + "ModelReport/ModelDiff), or state the reason in CANNOT_BE_CLOSED");
    }

    /** No stale exemptions: every excused name is really still a witness. */
    @Test
    void theExemptionLedgerIsHonest() {
        List<String> live = witnessTypes().stream().map(Class::getSimpleName).toList();
        for (var entry : CANNOT_BE_CLOSED.entrySet()) {
            assertTrue(live.contains(entry.getKey()),
                    () -> entry.getKey() + " is excused from the witness check but is no longer a witness — drop "
                            + "the row rather than leaving it to cover a future type of the same name");
            assertTrue(!entry.getValue().isBlank(), () -> entry.getKey() + " needs a reason");
        }
    }
}

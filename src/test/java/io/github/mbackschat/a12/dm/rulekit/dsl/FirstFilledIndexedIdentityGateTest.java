package io.github.mbackschat.a12.dm.rulekit.dsl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.core.KernelCodeRefusal;

/**
 * {@code FirstFilledValue}'s INDEXED owner entities, controlled in the module that owns their identity.
 *
 * <p>This carrier's operand is richer than the sealed unions': an owner entity may be a field or a group, and
 * its semantic index may be keyed by a literal or by a field read. When the duplicate predicate was merged onto
 * {@code FirstFilledOperand}, those key arms arrived with no rulekit-side control — a mutant emptying either
 * survived the whole root lane and died only in an {@code interpreter-bridge} translation test, which is the
 * same structural gap a cold review had already found once for the sealed unions' indexed arms.
 *
 * <p>Kernel rows ({@code validate.laws.DuplicateOverlapCarrierSweepLawsTest}): one owner under one literal key
 * twice is {@code MVK_DUPLICATE_PARAM1} when the key resolves; under two keys it is admitted, because the two
 * operands address different repetitions.
 */
class FirstFilledIndexedIdentityGateTest {

    private static final BoundModel ORDER = Fixtures.order();

    private static FirstFilledOperand keyed(String key) {
        return FirstFilledOperand.of(new SemanticIndexValue(ORDER.field("/Order/Items/Count"), key));
    }

    @Test
    void oneOwnerUnderOneKeyTwiceIsAnExactDuplicate() {
        var refusal = assertThrows(IllegalArgumentException.class,
                () -> Dsl.firstFilledNumber(keyed("a"), keyed("a")));
        // The duplicate gate answers, and its class is withheld: an index that does not resolve answers first in
        // A12, which the handle cannot see ([AF160], validate.laws.FirstFilledValueGateOrderLawsTest).
        assertTrue(refusal.getMessage().contains("is listed more than once"), refusal::getMessage);
        assertFalse(refusal instanceof KernelCodeRefusal, refusal::getMessage);
    }

    /** One variable apart: two keys address two repetitions, so refusing the pair would over-reject. */
    @Test
    void theSameOwnerUnderTwoKeysIsNotADuplicate() {
        assertDoesNotThrow(() -> Dsl.firstFilledNumber(keyed("a"), keyed("b")),
                "the key is part of the identity, so these are two operands");
    }
}

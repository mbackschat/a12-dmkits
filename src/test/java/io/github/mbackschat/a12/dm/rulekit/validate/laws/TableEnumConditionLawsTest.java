package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * KERNEL-FINDINGS §8 — the one gate of the table-enum condition family that a model WITHOUT a table enum
 * reaches, and the five behind it that it pre-empts.
 *
 * <p>[MVK-LEDGER] carried six codes on one sentence — "table-backed enums are not DM-JSON-serializable, so
 * the construct is un-buildable" — and the sentence is right about five of them and inverted on the sixth.
 * {@code CheckFieldAndValueListConditionImpl.check} runs {@code checkField} FIRST, and that call ends in
 * {@code CheckEntityUtils.checkEnumWithTableReference}, which raises {@code MVK_TABLE_ENUM_EXPECTED} when the
 * first operand is <b>not</b> a table enum. So the absence of a table enum does not make that code
 * unreachable — it is exactly the condition that fires it, and it is the gate every ordinary model hits.
 *
 * <p><b>The five behind it are gated by that same call, which is why they stay closed.</b>
 * {@code MVK_DEPENDENT_COLUMN_SIZE_MISMATCH} and {@code MVK_ONLY_ENUMS_ALLOWED} are raised after
 * {@code checkField} returns, and {@code MVK_STRING_VALUE_NOT_IN_COLUMN} /
 * {@code MVK_ENUM_VALUES_NOT_IN_COLUMN} sit inside {@code enumType.getTable().ifPresent(...)} below that —
 * so reaching any of them needs the stored table enum that [KF366](../../../../../../../../../../../docs/KERNEL-FINDINGS.md#kf366) measured the
 * READER refusing. {@code MVK_INVALID_TABLE_ENUM} is the mirror image and has a second, independent reason:
 * its sole raiser is {@code CheckEntityUtils.checkNoTableEnum}, which no site in the 30.8.1 checkout calls.
 *
 * <p>This grid measures only the reachable gate and its neighbours. It does NOT measure the five, and cannot:
 * the shape that would reach them has no stored spelling.
 *
 * <p>Own domain (the shared order fixture), whose {@code /Order/Priority} is an ordinary enumeration.
 */
class TableEnumConditionLawsTest {

    /** The error field is named outside the operand under test, so no row moves the referenced-field gate. */
    private static final String GUARD = "FieldFilled(/Order/Quantity) And ";

    private static String verdict(String condition) {
        return KernelLaws.orderVerdict("/Order", "../Quantity", GUARD + condition);
    }

    @Test
    void whichGateTheValueNotConsistentConditionReachesWithoutATableEnum() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("an ordinary ENUM field as the first operand",
                verdict("ValueNotConsistent(/Order/Priority To \"HIGH\")"));
        actual.put("a STRING field as the first operand",
                verdict("ValueNotConsistent(/Order/CustomerName To \"HIGH\")"));
        actual.put("a NUMBER field as the first operand",
                verdict("ValueNotConsistent(/Order/UnitPrice To \"HIGH\")"));
        actual.put("a GROUP as the first operand",
                verdict("ValueNotConsistent(/Order/ShippingAddress To \"HIGH\")"));
        actual.put("a repetition-list SELECTOR as the first operand",
                verdict("ValueNotConsistent(/Order/Items*/Sku To \"HIGH\")"));
        actual.put("an ordinary enum with TWO value-list members",
                verdict("ValueNotConsistent(/Order/Priority To \"HIGH\", \"LOW\")"));
        actual.put("an ordinary enum with a FIELD as the value-list member",
                verdict("ValueNotConsistent(/Order/Priority To /Order/RushLevel)"));
        actual.put("a field that does not exist",
                verdict("ValueNotConsistent(/Order/NoSuchField To \"HIGH\")"));
        actual.put("the guard alone — the liveness control", verdict("FieldFilled(/Order/Priority)"));

        Map<String, String> expected = new LinkedHashMap<>();
        // The gate is reached by EVERY first operand that is not a table enum, which is every operand a
        // stored model can spell — so the code the ledger called unreachable is the one law of this family
        // an ordinary model cannot avoid.
        expected.put("an ordinary ENUM field as the first operand", "MVK_TABLE_ENUM_EXPECTED");
        expected.put("a STRING field as the first operand", "MVK_TABLE_ENUM_EXPECTED");
        expected.put("a NUMBER field as the first operand", "MVK_TABLE_ENUM_EXPECTED");
        // …and the two rows that are NOT fields answer to the checks ahead of it, so the order of
        // `checkIsField` / `checkNoWildCards` / `checkEnumWithTableReference` is measured rather than read.
        expected.put("a GROUP as the first operand", "MVK_NO_GROUPS_ALLOWED");
        expected.put("a repetition-list SELECTOR as the first operand", "MVK_NO_WILDCARDS_ALLOWED");
        // The arity and member-kind laws sit BEHIND the gate, so neither a wrong member count nor a
        // non-enum member can be seen here — the table-enum demand answers first in both.
        expected.put("an ordinary enum with TWO value-list members", "MVK_TABLE_ENUM_EXPECTED");
        expected.put("an ordinary enum with a FIELD as the value-list member", "MVK_TABLE_ENUM_EXPECTED");
        expected.put("a field that does not exist", "MVK_INVALID_ENTITY");
        expected.put("the guard alone — the liveness control", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "which gate the table-enum condition family reaches");
    }
}

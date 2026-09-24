package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * KERNEL-FINDINGS §9 — <b>which class a GROUP operand and a {@code RepetitionNotUnique} KEY draw when they do
 * not resolve</b>: the class of the segment that missed, as a field path's miss does (KF356), not the entity
 * class for every miss (a12-kernel-lean SPEC-2026-09-24-01, their report only; every row is measured here).
 *
 * <p>The split is by the path's FIRST segment and its spelling. An ABSOLUTE path whose first segment is not the
 * root draws {@code MVK_INVALID_ROOT_GROUP}, a declared group read at root position ({@code /Claims}) included;
 * a walk above the root draws {@code MVK_TOO_MANY_PARENT_GROUPS}; and any other miss, a relative one included
 * however its first segment reads, draws {@code MVK_INVALID_ENTITY}. A starred path is judged segment by segment,
 * so a star on a segment that is not a repeatable group answers ahead of a later miss, and a key is resolved
 * before the key list's own wildcard gate reads it.
 *
 * <p>Own domain: an insurance policy with a fixed insured party and broker and repeatable claims. {@code Remark}
 * is declared in two groups so a bare {@code Remark} is ambiguous model-wide, and short-name references are on.
 */
class GroupOperandAndKeyResolverClassLawsTest {

    private static final String POLICY = ModelDocument.read(DocumentModelDraft.named("policy").locale("en_US")
                    .rootGroup("Policy", p -> p
                            .field("Holder", FieldKind.STRING)
                            .group("Insured", i -> i.field("Remark", FieldKind.STRING).field("Age", FieldKind.NUMBER))
                            .group("Broker", b -> b.field("BrokerName", FieldKind.STRING))
                            .group("Claims", c -> c.repeatable(20)
                                    .field("ClaimRef", FieldKind.STRING)
                                    .field("Remark", FieldKind.STRING)
                                    .field("Amount", FieldKind.NUMBER)))
                    .build().toJson())
            .setFieldRefByShortNameAllowed(true)
            .toJson();

    /** The fixed operands, each beside its expected class, the admitted control first. */
    private static final Map<String, String> FIXED = orderedMap(
            "/Policy/Insured", "admitted",
            "/Nope/Insured", "MVK_INVALID_ROOT_GROUP",
            "/Nope", "MVK_INVALID_ROOT_GROUP",
            "/Claims", "MVK_INVALID_ROOT_GROUP",
            "../Insured", "MVK_TOO_MANY_PARENT_GROUPS",
            "/Policy/Nope", "MVK_INVALID_ENTITY",
            "Nope", "MVK_INVALID_ENTITY");

    /** Every carrier that reads a fixed group operand, with the slot the operand fills. */
    private static final List<String> FIXED_CARRIERS = List.of(
            "AtLeastOneGroupFilled(%s, Broker)",
            "AllGroupsFilled(%s, Broker)",
            "NotAllGroupsFilled(%s, Broker)",
            "GroupsNotCollectivelyFilled(%s, Broker)",
            "NoGroupFilled(%s, Broker)",
            "NumberOfFilledGroups(%s, Broker) > 0",
            "GroupFilled(%s)");

    @Test
    void aFixedGroupOperandDrawsTheClassOfTheSegmentThatMissed() {
        Map<String, String> actual = new LinkedHashMap<>();
        Map<String, String> expected = new LinkedHashMap<>();
        for (String carrier : FIXED_CARRIERS) {
            FIXED.forEach((operand, code) -> {
                String row = carrier.formatted(operand);
                actual.put(row, verdict("../Holder", "FieldFilled(Holder) And " + row));
                expected.put(row, code);
            });
        }
        assertEquals(expected, actual, "what each fixed group operand draws on every group carrier");
    }

    @Test
    void aStarredGroupOperandIsJudgedSegmentBySegment() {
        Map<String, String> starred = orderedMap(
                "Claims*", "admitted",
                "Nope*", "MVK_INVALID_ENTITY",
                "Claims*/Nope", "MVK_INVALID_ENTITY",
                "Nope/Claims*", "MVK_INVALID_ENTITY",
                "/Nope/Claims*", "MVK_INVALID_ROOT_GROUP",
                "/Claims*", "MVK_INVALID_ROOT_GROUP",
                "Insured*", "MVK_INVALID_WILDCARD",
                "Insured*/Nope", "MVK_INVALID_WILDCARD",
                "/Policy*/Claims*", "MVK_INVALID_WILDCARD",
                "Holder*/Nope", "MVK_INVALID_WILDCARD",
                "../Claims*", "MVK_TOO_MANY_PARENT_GROUPS");
        Map<String, String> actual = new LinkedHashMap<>();
        Map<String, String> expected = new LinkedHashMap<>();
        for (String carrier : List.of("AtLeastOneGroupFilled(%s)", "NumberOfFilledGroups(%s) > 0")) {
            starred.forEach((operand, code) -> {
                String row = carrier.formatted(operand);
                actual.put(row, verdict("../Holder", "FieldFilled(Holder) And " + row));
                expected.put(row, code);
            });
        }
        assertEquals(expected, actual, "what each starred group operand draws on both starred carriers");
    }

    /**
     * The same segment order on a FIELD path, where the gate that answers before a later miss can be either
     * per-segment gate: a star on a segment that does not repeat, or the H-model {@code .*} suffix, which
     * outranks the landing site (KF356). The rows with a miss AFTER the gated segment are the discriminators;
     * the rest are their controls.
     */
    @Test
    void aFieldPathAnswersAtTheFirstGatedSegmentBeforeALaterMiss() {
        Map<String, String> rows = orderedMap(
                "Sum(Claims*/Amount) > 0", "admitted",
                "Sum(Claims*/Nope) > 0", "MVK_INVALID_ENTITY",
                "Sum(Nope*/Amount) > 0", "MVK_INVALID_ENTITY",
                "Sum(Insured*/Age) > 0", "MVK_INVALID_WILDCARD",
                "Sum(Insured*/Nope) > 0", "MVK_INVALID_WILDCARD",
                "Sum(Holder*/Nope) > 0", "MVK_INVALID_WILDCARD",
                "Sum(Claims.*/Amount) > 0", "MVK_POINT_IN_A12_MODEL",
                "Sum(Claims.*/Nope) > 0", "MVK_POINT_IN_A12_MODEL",
                "Sum(Insured.*/Nope) > 0", "MVK_POINT_IN_A12_MODEL",
                "FieldFilled(Insured*/Nope)", "MVK_INVALID_WILDCARD",
                "FieldFilled(Claims.*/Nope)", "MVK_POINT_IN_A12_MODEL");
        Map<String, String> actual = new LinkedHashMap<>();
        rows.keySet().forEach(row -> actual.put(row, verdict("../Holder", "FieldFilled(Holder) And " + row)));
        assertEquals(rows, actual, "what each field path draws once a gated segment precedes a miss");
    }

    /** The third per-segment gate, a starred up-step, asked from a rule one level below the root. */
    @Test
    void aStarredUpStepAnswersBeforeALaterMiss() {
        Map<String, String> rows = orderedMap(
                "Sum(../Claims*/Amount) > 0", "admitted",
                "Sum(../Nope) > 0", "MVK_INVALID_ENTITY",
                "Sum(..*/Claims*/Amount) > 0", "MVK_INVALID_WILDCARD_REL",
                "Sum(..*/Nope) > 0", "MVK_INVALID_WILDCARD_REL");
        Map<String, String> actual = new LinkedHashMap<>();
        rows.keySet().forEach(row -> actual.put(row,
                verdictIn("/Policy/Insured", "../Remark", "FieldFilled(Remark) And " + row)));
        assertEquals(rows, actual, "what each up-step path draws from a nested rule group");
    }

    /**
     * The key rows, placed at the key group's parent with the error field on the key's own group, which is the
     * one placement where every control is admitted: from a field outside {@code Claims} each draws
     * {@code MVK_NO_WILDCARD} first (KF355), which would hide every class below.
     */
    @Test
    void aKeyDrawsTheSegmentClassesAndIsResolvedBeforeTheWildcardGate() {
        Map<String, String> keys = orderedMap(
                "Claims/ClaimRef", "admitted",
                "/Policy/Claims/ClaimRef", "admitted",
                "ClaimRef", "admitted",
                "/Policy/Claims/Nope", "MVK_INVALID_ENTITY",
                "/Policy/Nope/ClaimRef", "MVK_INVALID_ENTITY",
                "Nope", "MVK_INVALID_ENTITY",
                "/Nope/Claims/ClaimRef", "MVK_INVALID_ROOT_GROUP",
                "../Claims/ClaimRef", "MVK_TOO_MANY_PARENT_GROUPS",
                "Remark", "MVK_FIELDNAME_NOT_UNIQUE",
                "Claims*/ClaimRef", "MVK_NO_WILDCARDS_ALLOWED",
                "Insured*/Remark", "MVK_INVALID_WILDCARD",
                "Insured*/Nope", "MVK_INVALID_WILDCARD",
                "Claims*/Nope", "MVK_INVALID_ENTITY",
                "Nope*/ClaimRef", "MVK_INVALID_ENTITY");
        Map<String, String> actual = new LinkedHashMap<>();
        keys.keySet().forEach(key ->
                actual.put(key, verdict("../Claims/ClaimRef", "RepetitionNotUnique(" + key + ")")));
        assertEquals(keys, actual, "what each RepetitionNotUnique key draws");
    }

    /** {@code admitted}, or the COMPLETE emitted code list — a partial list would hide a co-firing gate. */
    private static String verdict(String errorField, String condition) {
        return verdictIn("/Policy", errorField, condition);
    }

    private static String verdictIn(String group, String errorField, String condition) {
        ValidationResult r = KernelLaws.checkModel(POLICY, group, errorField, condition);
        return r.ok() ? "admitted" : String.join(" + ", r.diagnostics().stream()
                .map(d -> d.code().orElse("(no code)")).distinct().toList());
    }

    private static Map<String, String> orderedMap(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }
}

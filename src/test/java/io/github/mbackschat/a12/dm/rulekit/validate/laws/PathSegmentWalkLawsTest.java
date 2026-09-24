package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * KERNEL-FINDINGS §9 — <b>where an up-step may stand in a path, and what a starred segment answers before one
 * is refused</b>, plus the short-name fallback on a starred segment. Each row is a spelling the checker-vs-kernel
 * path grid ({@code adapter.laws.CheckerPathGridAgreementTest}) disagreed on; the rows here are what A12 says.
 *
 * <p>An up-step is admitted only as the leading run of a RELATIVE path. Written after an UNSTARRED name, or in
 * an absolute path, it is refused as a token, whether or not that name resolves. Written after a STARRED name,
 * that name's own gates answer first — its resolution, its {@code .*} suffix, its landing site — and only a
 * name that clears all three reaches the token refusal. The earlier record of this gate varied the star on the
 * up-step and never on the segment before it, which is why its "starred or not" did not see the split.
 *
 * <p>Own domain: {@link PolicyPathFixture}.
 */
class PathSegmentWalkLawsTest {

    private static final String SHORT_NAMES_ON = PolicyPathFixture.policy(true);
    private static final String SHORT_NAMES_OFF = PolicyPathFixture.policy(false);

    @Test
    void anUpStepAfterAnUnstarredNameIsATokenWhateverThatNameResolvesTo() {
        Map<String, String> rows = orderedMap(
                "Claims*/Amount", "MVK_NO_WILDCARDS_ALLOWED",
                "Claims/..", "MVK_UNEXPECTED_TOKEN",
                "Holder/../Amount", "MVK_UNEXPECTED_TOKEN",
                "Nope/../Holder", "MVK_UNEXPECTED_TOKEN",
                "Claims/..*/Nope*", "MVK_UNEXPECTED_TOKEN",
                "/Policy/Insured/..", "MVK_UNEXPECTED_TOKEN",
                "/Policy/../Claims.*", "MVK_UNEXPECTED_TOKEN",
                "/../Holder", "MVK_UNEXPECTED_TOKEN",
                "/..*", "MVK_UNEXPECTED_TOKEN");
        assertEquals(rows, verdicts(SHORT_NAMES_ON, "/Policy", "Holder", rows), "an up-step after an unstarred name");
    }

    /**
     * The starred twin of every row above: the up-step now follows a starred name, so that name is judged
     * first. The first row is the control — an admitted starred name, whose up-step is then refused as a token
     * exactly as after an unstarred one.
     */
    @Test
    void aStarredNameAnswersItsOwnGatesBeforeTheUpStepIsRefused() {
        Map<String, String> rows = orderedMap(
                "Claims*/..", "MVK_UNEXPECTED_TOKEN",
                "Claims*/../Amount", "MVK_UNEXPECTED_TOKEN",
                "/Policy/Claims*/..", "MVK_UNEXPECTED_TOKEN",
                "Nope*/..", "MVK_INVALID_ENTITY",
                "Nope*/../Holder", "MVK_INVALID_ENTITY",
                "/Claims*/..", "MVK_INVALID_ROOT_GROUP",
                "Claims.*/..", "MVK_POINT_IN_A12_MODEL",
                "Insured*/..", "MVK_INVALID_WILDCARD",
                "Holder*/..", "MVK_INVALID_WILDCARD");
        assertEquals(rows, verdicts(SHORT_NAMES_ON, "/Policy", "Holder", rows), "an up-step after a starred name");
    }

    /**
     * From one level down the suffixed name does not resolve at all, so resolution answers ahead of its suffix,
     * as KF356 has it on a path without an up-step; the leading up-step is the admitted control.
     */
    @Test
    void fromANestedRuleTheStarredNameIsResolvedFirstToo() {
        Map<String, String> rows = orderedMap(
                "../Holder", "admitted",
                "Claims.*/..", "MVK_INVALID_ENTITY",
                "Nope*/..", "MVK_INVALID_ENTITY",
                "..", "MVK_ROOT_GROUP_REFERENCED",
                "..*", "MVK_INVALID_WILDCARD_REL");
        assertEquals(rows, verdicts(SHORT_NAMES_ON, "/Policy/Insured", "Remark", rows), "from a nested rule");
    }

    /** A leading up-run that leaves the model answers with the depth class, starred or not. */
    @Test
    void anUpRunThatLeavesTheModelDrawsTheDepthClass() {
        Map<String, String> rows = orderedMap(
                "..", "MVK_TOO_MANY_PARENT_GROUPS",
                "..*", "MVK_TOO_MANY_PARENT_GROUPS",
                "../..", "MVK_TOO_MANY_PARENT_GROUPS",
                "..*/..", "MVK_TOO_MANY_PARENT_GROUPS");
        assertEquals(rows, verdicts(SHORT_NAMES_ON, "/Policy", "Holder", rows), "an up-run from a root-level rule");
    }

    /**
     * The model-wide short-name fallback reaches a STARRED first segment, not only a bare leaf: a starred name
     * the rule's group does not declare still resolves when some other group declares it once, and is then
     * refused at its landing site. Two rivals predict the same {@code Holder*} row. An ancestor walk would find
     * {@code Holder} in the parent but never {@code BrokerName} in a sibling group; a lookup that ignores the
     * flag would give the same verdict with it off. The sibling row and the flag-off twin separate them.
     */
    @Test
    void theShortNameFallbackReachesAStarredFirstSegment() {
        Map<String, String> on = orderedMap(
                "Holder", "admitted",
                "Holder*", "MVK_INVALID_WILDCARD",
                "Holder*/Claims", "MVK_INVALID_WILDCARD",
                "BrokerName*", "MVK_INVALID_WILDCARD",
                "Nope*", "MVK_INVALID_ENTITY");
        assertEquals(on, verdicts(SHORT_NAMES_ON, "/Policy/Insured", "Remark", on), "short-name references on");
        Map<String, String> off = orderedMap(
                "Holder", "MVK_INVALID_ENTITY",
                "Holder*", "MVK_INVALID_ENTITY",
                "BrokerName*", "MVK_INVALID_ENTITY");
        assertEquals(off, verdicts(SHORT_NAMES_OFF, "/Policy/Insured", "Remark", off), "short-name references off");
    }

    /** An ambiguous starred name meets the uniqueness demand the fallback carries on a bare leaf. */
    @Test
    void anAmbiguousStarredNameIsNotUnique() {
        Map<String, String> rows = orderedMap(
                "Age*", "MVK_INVALID_WILDCARD",
                "Remark*", "MVK_FIELDNAME_NOT_UNIQUE");
        assertEquals(rows, verdicts(SHORT_NAMES_ON, "/Policy", "Holder", rows), "a starred short name from the root");
    }

    /** Each operand, read as {@code FieldFilled(<operand>)} beside the error field's own reference. */
    private static Map<String, String> verdicts(String model, String group, String errorLeaf, Map<String, String> rows) {
        Map<String, String> actual = new LinkedHashMap<>();
        rows.keySet().forEach(operand -> actual.put(operand, verdict(model, group, errorLeaf,
                "FieldFilled(" + errorLeaf + ") And FieldFilled(" + operand + ")")));
        return actual;
    }

    /** {@code admitted}, or the COMPLETE emitted code list — a partial list would hide a co-firing gate. */
    private static String verdict(String model, String group, String errorLeaf, String condition) {
        ValidationResult r = KernelLaws.checkModel(model, group, "../" + errorLeaf, condition);
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

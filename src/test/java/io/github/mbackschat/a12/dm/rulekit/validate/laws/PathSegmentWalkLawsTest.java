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
 * <p>An up-step is admitted only as the leading run of a RELATIVE path. Anywhere else it is refused as a token,
 * and whether the segments before it are judged first depends on what precedes it. With no star on a named
 * segment before it, and no leading up-run, they are never resolved: an undeclared name makes no difference.
 * With either, the walk judges them first — resolution, the {@code .*} suffix, the landing site — and only a
 * prefix that clears all three reaches the token refusal. A star AFTER the up-step changes nothing. The
 * earlier record of this gate varied the star on the up-step and never on the segment before it, which is why
 * its "starred or not" did not see the split. A message parameter is never walked.
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

    /**
     * The fallback answers the FIRST segment of a longer path too, starred or not, and on both surfaces:
     * {@code Remark/Nope} is not unique where a positional reading would call it an entity miss. That is the
     * row the two readings disagree on, because the fallback indexes fields and a field has nothing below it,
     * so a unique first segment ({@code Age/Nope}) is an entity miss either way. The flag-off twin shows the
     * fallback is what answers.
     */
    @Test
    void theShortNameFallbackAnswersTheFirstSegmentOfALongerPath() {
        Map<String, String> on = orderedMap(
                "Remark/Nope", "MVK_FIELDNAME_NOT_UNIQUE",
                "Remark*/Nope", "MVK_FIELDNAME_NOT_UNIQUE",
                "Remark/Claims*", "MVK_FIELDNAME_NOT_UNIQUE",
                "Age/Nope", "MVK_INVALID_ENTITY",
                "Age*/Nope", "MVK_INVALID_WILDCARD");
        assertEquals(on, verdicts(SHORT_NAMES_ON, "/Policy", "Holder", on), "short-name references on");
        Map<String, String> off = orderedMap(
                "Remark/Nope", "MVK_INVALID_ENTITY",
                "Age/Nope", "MVK_INVALID_ENTITY");
        assertEquals(off, verdicts(SHORT_NAMES_OFF, "/Policy", "Holder", off), "short-name references off");
        Map<String, String> message = orderedMap(
                "see $Remark$", "ERROR_TEXT_FIELDNAME_NOT_UNIQUE",
                "see $Remark/Nope$", "ERROR_TEXT_FIELDNAME_NOT_UNIQUE",
                "see $Age/Nope$", "INVALID_ENTITY");
        Map<String, String> actual = new LinkedHashMap<>();
        message.keySet().forEach(text -> actual.put(text, KernelLaws.messageVerdict(SHORT_NAMES_ON, "/Policy",
                "../Holder", "FieldFilled(Holder)", "en_US", text)));
        assertEquals(message, actual, "a message parameter");
    }

    /** An ambiguous starred name meets the uniqueness demand the fallback carries on a bare leaf. */
    @Test
    void anAmbiguousStarredNameIsNotUnique() {
        Map<String, String> rows = orderedMap(
                "Age*", "MVK_INVALID_WILDCARD",
                "Remark*", "MVK_FIELDNAME_NOT_UNIQUE");
        assertEquals(rows, verdicts(SHORT_NAMES_ON, "/Policy", "Holder", rows), "a starred short name from the root");
    }

    /**
     * What makes the walk reach a misplaced up-step is a star on a NAMED segment BEFORE it, not a star
     * anywhere: {@code Nope/../Claims*} is a token error although it carries a star, and {@code Nope/Claims*}
     * then {@code ..} is an entity miss. Without such a star the segments before the up-step are never
     * resolved, so an undeclared name and a field make no difference.
     */
    @Test
    void onlyAStarBeforeTheUpStepMakesTheWalkReachIt() {
        Map<String, String> rows = orderedMap(
                "Nope/../Claims*", "MVK_UNEXPECTED_TOKEN",
                "Nope/../Insured*", "MVK_UNEXPECTED_TOKEN",
                "Holder/../Claims*", "MVK_UNEXPECTED_TOKEN",
                "Claims/Nope/..", "MVK_UNEXPECTED_TOKEN",
                "Claims/Amount/..", "MVK_UNEXPECTED_TOKEN",
                "Claims/Amount/../Claims*", "MVK_UNEXPECTED_TOKEN",
                "Claims/..*", "MVK_UNEXPECTED_TOKEN",
                "Nope/..*", "MVK_UNEXPECTED_TOKEN",
                "Nope/../Claims.*", "MVK_UNEXPECTED_TOKEN",
                "Insured/Nope/../Claims*", "MVK_UNEXPECTED_TOKEN",
                "/Nope/..", "MVK_UNEXPECTED_TOKEN",
                "/Nope/../Claims*", "MVK_UNEXPECTED_TOKEN",
                "/Policy/Nope/..", "MVK_UNEXPECTED_TOKEN",
                "Nope/Claims*/..", "MVK_INVALID_ENTITY",
                "Claims*/Nope/..", "MVK_INVALID_ENTITY",
                "/Nope/Claims*/..", "MVK_INVALID_ROOT_GROUP",
                "Claims.*/Nope/..", "MVK_POINT_IN_A12_MODEL",
                "Claims*/Amount*/..", "MVK_INVALID_WILDCARD",
                "Claims/Amount*/..", "MVK_INVALID_WILDCARD",
                "Claims*/Amount/..", "MVK_UNEXPECTED_TOKEN",
                "Claims*/../..", "MVK_UNEXPECTED_TOKEN",
                "Claims*/..*", "MVK_UNEXPECTED_TOKEN");
        assertEquals(rows, verdicts(SHORT_NAMES_ON, "/Policy", "Holder", rows), "a star before the up-step, or none");
    }

    /**
     * A LEADING up-run makes the walk reach a later misplaced up-step as well, so {@code ../Nope} then
     * {@code ..} is an entity miss where a bare {@code Nope/..} is a token error. The starred first step is
     * judged first, as it is without the later up-step.
     */
    @Test
    void aLeadingUpRunIsWalkedBeforeALaterUpStepIsRefused() {
        Map<String, String> rows = orderedMap(
                "../Claims/..", "MVK_UNEXPECTED_TOKEN",
                "../Claims*/..", "MVK_UNEXPECTED_TOKEN",
                "../Claims*/Amount/..", "MVK_UNEXPECTED_TOKEN",
                "../Nope/..", "MVK_INVALID_ENTITY",
                "../Nope*/..", "MVK_INVALID_ENTITY",
                "..*/Claims/..", "MVK_INVALID_WILDCARD_REL",
                "Remark/..", "MVK_UNEXPECTED_TOKEN",
                "Remark*/..", "MVK_INVALID_WILDCARD");
        assertEquals(rows, verdicts(SHORT_NAMES_ON, "/Policy/Insured", "Remark", rows), "a leading up-run");
    }

    /**
     * A named turning point is an up-step too, so it is misplaced after a named segment exactly as a bare one
     * is, starred or not, and a star before it has its prefix judged first.
     */
    @Test
    void aNamedTurningPointAfterANameIsMisplacedToo() {
        Map<String, String> rows = orderedMap(
                "Claims/..Policy/Holder", "MVK_UNEXPECTED_TOKEN",
                "Claims/..Policy*/Holder", "MVK_UNEXPECTED_TOKEN",
                "Claims/..Nope/Holder", "MVK_UNEXPECTED_TOKEN",
                "Claims*/..Policy/Holder", "MVK_UNEXPECTED_TOKEN",
                "Nope*/..Policy/Holder", "MVK_INVALID_ENTITY",
                "/Policy/..Policy/Holder", "MVK_UNEXPECTED_TOKEN");
        assertEquals(rows, verdicts(SHORT_NAMES_ON, "/Policy", "Holder", rows), "a named turning point after a name");
    }

    /**
     * A starred LEADING up-step answers at its own segment, so a later segment's depth class and a later
     * {@code .*} suffix are never reached: the walk's order holds past the up-run too. The controls are the
     * same later defects with the leading step unstarred, where each does answer.
     */
    @Test
    void aStarredLeadingUpStepAnswersBeforeALaterSegmentDoes() {
        Map<String, String> rows = orderedMap(
                "..*/../Amount", "MVK_INVALID_WILDCARD_REL",
                "..*/..*/Nope", "MVK_INVALID_WILDCARD_REL",
                "..*/Claims.*", "MVK_INVALID_WILDCARD_REL",
                "..*/Claims.*/Amount", "MVK_INVALID_WILDCARD_REL",
                "../../Amount", "MVK_TOO_MANY_PARENT_GROUPS",
                "../Claims.*", "MVK_POINT_IN_A12_MODEL");
        assertEquals(rows, verdicts(SHORT_NAMES_ON, "/Policy/Insured", "Remark", rows), "a starred leading up-step");
    }

    /** The refusal is the path's, not the carrier's: a group operand and an aggregate operand draw it too. */
    @Test
    void theRefusalDoesNotDependOnTheCarrier() {
        Map<String, String> rows = orderedMap(
                "AtLeastOneGroupFilled(Claims/.., Insured)", "MVK_UNEXPECTED_TOKEN",
                "Sum(Claims/../Claims*/Amount) > 0", "MVK_UNEXPECTED_TOKEN");
        Map<String, String> actual = new LinkedHashMap<>();
        rows.keySet().forEach(row -> actual.put(row,
                verdict(SHORT_NAMES_ON, "/Policy", "Holder", "FieldFilled(Holder) And " + row)));
        assertEquals(rows, actual, "a misplaced up-step on two more carriers");
    }

    /**
     * A12 answers the FIRST defect in the text, and a misplaced up-step takes its turn like any other: an
     * undeclared name before it wins, and a wildcard class before it wins too. So does a name before a stray
     * closing parenthesis, which is refused as a token when it stands alone — the control that shows the
     * checks run as the parser reaches each reference rather than after a finished parse.
     */
    @Test
    void theFirstDefectInTheTextAnswers() {
        Map<String, String> rows = orderedMap(
                "FieldFilled(Holder) And FieldFilled(Nope2) And FieldFilled(Claims/..)", "MVK_INVALID_ENTITY",
                "FieldFilled(Holder) And FieldFilled(Claims/..) And FieldFilled(Nope2)", "MVK_UNEXPECTED_TOKEN",
                "FieldFilled(Holder) And FieldFilled(Claims*/Amount) And FieldFilled(Claims/..)",
                "MVK_NO_WILDCARDS_ALLOWED",
                "FieldFilled(Holder) And FieldFilled(Holder))", "MVK_UNEXPECTED_TOKEN",
                "FieldFilled(Holder) And FieldFilled(Nope2) And FieldFilled(Holder))", "MVK_INVALID_ENTITY");
        Map<String, String> actual = new LinkedHashMap<>();
        rows.keySet().forEach(row -> actual.put(row, verdict(SHORT_NAMES_ON, "/Policy", "Holder", row)));
        assertEquals(rows, actual, "the first defect in the text");
    }

    /**
     * A message parameter is never walked: every misplaced up-step there is a token error, a leading up-run
     * ahead of a miss included, where the same path in a condition draws the entity class. The controls are
     * the surface's own vocabulary for a plain miss and a climb above the root.
     */
    @Test
    void aMessageParameterRefusesEveryMisplacedUpStepAsAToken() {
        Map<String, String> fromRoot = orderedMap(
                "see $Holder$", KernelLaws.ADMITTED,
                "see $Claims/../Holder$", "MVK_UNEXPECTED_TOKEN",
                "see $Nope/../Holder$", "MVK_UNEXPECTED_TOKEN",
                "see $Insured/../Holder$", "MVK_UNEXPECTED_TOKEN",
                "see $Claims/..$", "MVK_UNEXPECTED_TOKEN",
                "see $/Policy/../Holder$", "MVK_UNEXPECTED_TOKEN",
                "see $/../Holder$", "MVK_UNEXPECTED_TOKEN");
        Map<String, String> actual = new LinkedHashMap<>();
        fromRoot.keySet().forEach(text -> actual.put(text, KernelLaws.messageVerdict(SHORT_NAMES_ON, "/Policy",
                "../Holder", "FieldFilled(Holder)", "en_US", text)));
        assertEquals(fromRoot, actual, "a message parameter from a root-level rule");
        Map<String, String> nested = orderedMap(
                "see $../Holder$", KernelLaws.ADMITTED,
                "see $../Nope$", "INVALID_ENTITY",
                "see $../../Holder$", "ERROR_TEXT_PARAMETER_TOO_MANY_PARENT_GROUPS",
                "see $../Insured/../Holder$", "MVK_UNEXPECTED_TOKEN",
                "see $../Claims/..$", "MVK_UNEXPECTED_TOKEN",
                "see $../Nope/../Holder$", "MVK_UNEXPECTED_TOKEN",
                "see $../Nope/..$", "MVK_UNEXPECTED_TOKEN");
        Map<String, String> nestedActual = new LinkedHashMap<>();
        nested.keySet().forEach(text -> nestedActual.put(text, KernelLaws.messageVerdict(SHORT_NAMES_ON,
                "/Policy/Insured", "../Remark", "FieldFilled(Remark) And FieldFilled(../Holder)", "en_US", text)));
        assertEquals(nested, nestedActual, "a message parameter from a nested rule");
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

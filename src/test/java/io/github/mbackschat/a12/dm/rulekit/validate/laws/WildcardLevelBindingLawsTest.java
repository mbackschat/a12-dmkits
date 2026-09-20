package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;

/**
 * KERNEL-FINDINGS §9 — is {@code MVK_NO_WILDCARD} a claim about the REFERENCE or about each LEVEL?
 *
 * <p>An external differential reports this code split 33 with a star against 21 without — their count over
 * a corpus we cannot query, cited as their report, and
 * asks whether the family is two-sided — "a star where none is admitted" as well as "no star where one is
 * required" — or whether its message-to-code mapping is conflating two templates.
 *
 * <p>Neither, on a source reading, and this table is what settles it. The kernel's bundle carries ONE template
 * for the code — <i>"Missing specification of an asterisk for '%s' in '%s'"</i> — and the opposite direction is
 * two different codes ({@code MVK_NO_WILDCARDS_ALLOWED}, {@code MVK_NO_WILDCARDS_G_ALLOWED}). But
 * {@code CheckEntityUtils} accumulates its wildcard flag LEVEL BY LEVEL as it walks a reference's indices, and
 * the check returns immediately unless the level under inspection iterates — so the judgement is per level, and
 * a reference can carry a star at one level and still be missing one at another. That would explain 33
 * star-containing cases without any second direction, and it makes "does this reference contain a star" the
 * wrong question for a client-side gate.
 *
 * <p><b>The axis this table holds fixed, and why.</b> Every row puts the error field at the ROOT, outside every
 * repeatable level. That is deliberate rather than incidental: the same operand shape can be admitted outright
 * when the error field sits inside the iterated group ([KF185](#kf185)), so mixing loci would vary two things
 * at once. The locus axis is a separate table, not a missing control.
 */
class WildcardLevelBindingLawsTest {

    private static final String NO_STAR = "MVK_NO_WILDCARD";

    private static String verdict(String condition) {
        var result = KernelLaws.checkModel(Fixtures.projectJson(), "/Project", "../ProjectName",
                "FieldFilled(ProjectName) And " + condition);
        if (result.ok()) {
            return KernelLaws.ADMITTED;
        }
        return result.diagnostics().stream()
                .map(d -> d.code().orElse("NO_CODE"))
                .distinct()
                .sorted()
                .collect(Collectors.joining("+"));
    }

    /** One table, so a kernel shift shows every cell rather than aborting at the first. */
    @Test
    void everyRepeatableLevelAnswersForItsOwnStar() {
        var actual = new LinkedHashMap<String, String>();

        // Two repeatable levels, four spellings. If the rule is per LEVEL, only the fully starred one is
        // admitted and the other three all draw the code — including the two that DO contain a star.
        actual.put("both levels starred", verdict("AllFieldsFilled(Milestones*/Tasks*/Effort)"));
        actual.put("outer starred, inner bare — CONTAINS a star",
                verdict("AllFieldsFilled(Milestones*/Tasks/Effort)"));
        actual.put("inner starred, outer bare — CONTAINS a star",
                verdict("AllFieldsFilled(Milestones/Tasks*/Effort)"));
        actual.put("neither level starred", verdict("AllFieldsFilled(Milestones/Tasks/Effort)"));

        // The single-level control, so the two-level rows above cannot be explained by depth alone.
        actual.put("one level, starred", verdict("AllFieldsFilled(Milestones*/Budget)"));
        actual.put("one level, bare", verdict("AllFieldsFilled(Milestones/Budget)"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("both levels starred", KernelLaws.ADMITTED);
        expected.put("outer starred, inner bare — CONTAINS a star", NO_STAR);
        expected.put("inner starred, outer bare — CONTAINS a star", NO_STAR);
        expected.put("neither level starred", NO_STAR);
        expected.put("one level, starred", KernelLaws.ADMITTED);
        expected.put("one level, bare", NO_STAR);

        assertEquals(expected, actual, "one verdict per level-binding spelling");
    }
}

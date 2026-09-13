package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.core.KernelPatternPolicy;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;

/**
 * WHICH declared string patterns A12 admits on a field, measured at the field locus rather than the
 * condition one.
 *
 * <p>{@link PatternLawsTest} measures the CONDITION operator's pattern operand — a literal inside
 * {@code PatternMatched} — and the estate's account of the gate was written from there. The declared
 * {@code StringType.pattern} is a different locus with its own kernel check ({@code CheckStringPattern},
 * registered as the {@code ungueltigesMusterA12} custom condition), and a checker reading stored DM-JSON
 * meets this one. The two are asserted to agree only where a row says so.
 *
 * <p><b>Why the rows are authored as DM-JSON rather than through the kit.</b> {@code StringFieldBuilder}
 * routes every pattern through {@code KernelPatternPolicy.requireAccepted}, which throws before the kernel
 * is ever asked — so authoring through the kit would measure our own preflight. This is the observation
 * route [KERNEL-FINDINGS KF216] describes: write the refused shape into the model file and let
 * {@code checkConsistency} answer.
 *
 * <p><b>The load-bearing row is {@code \*+}.</b> The kernel's gate is a textual blacklist — a regex applied
 * to the pattern SOURCE — so an escaped metacharacter followed by {@code +} matches it just as an unescaped
 * one does, though the pattern it spells is perfectly ordinary. An implementation that consumes escapes
 * while scanning disagrees exactly there and nowhere else, which is why {@code KernelPatternPolicy} did so
 * undetected until this row existed — no other row can tell the two readings apart.
 *
 * <p>Own domain (the shared order fixture), on {@code /Order/CustomerName}.
 */
class DeclaredPatternGateLawsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * The declared locus has its OWN code, and it is not the condition locus's.
     *
     * <p>{@link PatternLawsTest} locks {@code MVK_INVALID_PATTERN} for a refused pattern LITERAL inside a
     * condition. The field gate answers {@code MVK_PATTERN_INVALID} — the same two words, swapped — so a
     * consumer keying on the condition's code sees nothing here. Measured, because the two loci share a
     * check and nothing about that suggests two codes.
     */
    private static final String PATTERN_INVALID = "MVK_PATTERN_INVALID";

    /** A12's verdict on the order fixture with {@code CustomerName} carrying [regex] as its pattern. */
    private static String verdictFor(String regex) {
        return KernelLaws.verdictOf(storedModelWith(regex));
    }

    /** The order fixture as stored DM-JSON, with {@code CustomerName} carrying [regex] as its pattern. */
    private static String storedModelWith(String regex) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(Fixtures.orderJson());
            for (var element : root.path("content").path("modelRoot").path("rootGroups").get(0)
                    .path("Group").path("elements")) {
                if ("CustomerName".equals(element.path("name").asText()) && element.has("Field")) {
                    ObjectNode fieldType = MAPPER.createObjectNode();
                    fieldType.put("type", "StringType");
                    ObjectNode inner = fieldType.putObject("StringType");
                    // A declared pattern needs an error text for every declared locale, or the model is
                    // refused for the missing text instead and the row measures the wrong gate.
                    inner.putArray("errorMessage").addObject()
                            .put("locale", "en_US").put("text", "the reference is malformed");
                    inner.put("pattern", regex);
                    ((ObjectNode) element.path("Field")).set("fieldType", fieldType);
                    return root.toString();
                }
            }
            throw new IllegalStateException("the fixture no longer declares the root field `CustomerName`");
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the fixture must parse", unreadable);
        }
    }

    /** Each row's pattern, keyed by what it varies, so the kernel and our preflight read the same grid. */
    private static Map<String, String> grid() {
        Map<String, String> rows = new LinkedHashMap<>();
        rows.put("an ordinary pattern — the control", "[A-Z]{2}[0-9]+");
        rows.put("a pattern Java cannot compile", "[A-Z");

        // The blacklist's four alternatives, one witness each.
        rows.put("a possessive quantifier", "a++");
        rows.put("a possessive bounded quantifier", "a{2}+");
        rows.put("a lookbehind", "(?<=a)b");
        rows.put("a NAMED capture group", "(?<name>a)b");
        rows.put("an atomic group", "(?>a)b");
        rows.put("an input-start escape", "\\Aabc");
        rows.put("a lowercase property escape", "\\p{L}+");
        rows.put("a nested unescaped bracket", "[a[b]");

        // The rows that say the gate is TEXTUAL: the same constructs escaped, where the pattern is ordinary
        // and the blacklist still decides on the source characters.
        rows.put("an ESCAPED star followed by a quantifier", "a\\*+");
        rows.put("escaped brackets, one after another", "\\[a\\[b");
        rows.put("two escaped plus signs", "a\\+\\+");

        // Uppercase \P is absent from the blacklist expression; a 2026-07-20 note read its refusal as a
        // rejection of `\P` itself. The rival mechanism is that `{L}+` ends in `}` followed by `+`, which
        // the possessive alternative matches — so the quantifier is what the next two rows vary, holding
        // the escape fixed, and the lowercase pair is the control that keeps the escape observable.
        rows.put("an UPPERCASE property escape, quantified", "\\P{L}+");
        rows.put("an UPPERCASE property escape, unquantified", "\\P{L}");
        rows.put("a lowercase property escape, unquantified", "\\p{L}");
        return rows;
    }

    @Test
    void whichDeclaredPatternsA12AdmitsOnAStringField() {
        Map<String, String> actual = new LinkedHashMap<>();
        grid().forEach((row, regex) -> actual.put(row, verdictFor(regex)));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("an ordinary pattern — the control", KernelLaws.ADMITTED);
        expected.put("a pattern Java cannot compile", PATTERN_INVALID);
        expected.put("a possessive quantifier", PATTERN_INVALID);
        expected.put("a possessive bounded quantifier", PATTERN_INVALID);
        expected.put("a lookbehind", PATTERN_INVALID);
        expected.put("a NAMED capture group", PATTERN_INVALID);
        expected.put("an atomic group", PATTERN_INVALID);
        expected.put("an input-start escape", PATTERN_INVALID);
        expected.put("a lowercase property escape", PATTERN_INVALID);
        expected.put("a nested unescaped bracket", PATTERN_INVALID);
        expected.put("an ESCAPED star followed by a quantifier", PATTERN_INVALID);
        expected.put("escaped brackets, one after another", KernelLaws.ADMITTED);
        expected.put("two escaped plus signs", KernelLaws.ADMITTED);
        expected.put("an UPPERCASE property escape, quantified", PATTERN_INVALID);
        expected.put("an UPPERCASE property escape, unquantified", KernelLaws.ADMITTED);
        expected.put("a lowercase property escape, unquantified", PATTERN_INVALID);
        assertEquals(expected, actual, "which declared patterns the field-level gate admits");
    }

    /**
     * A model A12 admits stays READABLE through the kit, which is the sharper half of the same defect.
     *
     * <p>{@code FieldTypeSpec.StringSpec}'s constructor runs the preflight, and {@code ModelDocument.read}
     * builds one for every declared field — so an over-strict policy does not merely block authoring, it
     * makes a stored model A12 accepts impossible to load, inspect or modify. {@code \P{L}} was exactly
     * that model until 2026-09-13, and nothing on the authoring side would have shown it.
     */
    @Test
    void aKernelAdmittedPatternStaysReadableThroughTheKit() {
        String stored = storedModelWith("\\P{L}");
        assertEquals(KernelLaws.ADMITTED, KernelLaws.verdictOf(stored), "the premise: A12 admits it");
        ModelDocument.read(stored);
    }

    /**
     * Our own preflight agrees with the kernel on this grid, in BOTH directions.
     *
     * <p>{@code KernelPatternPolicy} refuses a pattern before the kernel is ever asked, on the authoring
     * path AND on the read path — {@code FieldTypeSpec.StringSpec}'s constructor calls it, so an
     * over-strict policy does not merely block authoring, it makes a legal stored model unreadable. That is
     * the refusal class CLAUDE.md requires a measured basis for, and this is the measurement: the grid
     * above is the basis, and the assertion is that the policy neither adds to it nor falls short.
     *
     * <p>It did both until 2026-09-13 — it rejected {@code \P{L}}, which A12 admits, and it admitted
     * {@code a\*+}, which A12 refuses.
     */
    @Test
    void theClientSidePreflightRefusesExactlyWhatTheKernelRefuses() {
        Map<String, Boolean> kernelRefuses = new LinkedHashMap<>();
        Map<String, Boolean> policyRefuses = new LinkedHashMap<>();
        grid().forEach((row, regex) -> {
            kernelRefuses.put(row, !KernelLaws.ADMITTED.equals(verdictFor(regex)));
            policyRefuses.put(row, KernelPatternPolicy.rejection(regex).isPresent());
        });
        assertEquals(kernelRefuses, policyRefuses,
                "the client-side pattern preflight disagrees with the kernel. A `true` it adds is an "
                        + "over-rejection that also makes a legal stored model unreadable; a `false` is "
                        + "delegation, which is safe but no longer what this policy claims");
    }
}

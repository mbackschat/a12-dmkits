package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * <b>The computation cascade's {@code OverlayContext} reads every piece of cell CONTENT through the cascade.</b>
 *
 * <p>It is an {@code EvalContext by d} decorator, so a member it does not override answers from the wrapped document
 * as if nothing had been computed, and nothing fails to compile. Three readers did exactly that — group presence, the
 * group-scoped fill tally and an enumeration's category — and each was wrong against both kernel strategies
 * ({@code adapter.laws.CascadeGroupPresenceDiffTest}, 2026-09-23). So every {@code EvalContext} member, property or
 * function, must be overridden, or appear in {@link #STRUCTURAL} with the reason it carries no computed content. A new
 * interface member fails here until someone decides which it is, and a stale entry fails too.
 *
 * <p>Recall limit: the scan reads member NAMES, so an overload the decorator skips while overriding its sibling is
 * not caught.
 */
class OverlayContextContentReadGuardTest {

    private static final String EVAL = "interpreter/src/commonMain/kotlin/io/github/mbackschat/a12/dm/interpreter/eval/";

    /** The members left to the wrapped context, each with the reason no computed value can change its answer. */
    static final Map<String, String> STRUCTURAL = Map.ofEntries(
            Map.entry("evaluated", "the row-content gate, which a computation never consults (Interpreter.gated); "
                    + "its one other reader, a self-excluded correlation join, asks it of a repeatable row, which "
                    + "is content in itself"),
            Map.entry("boundLevelsOf", "a repetition coordinate of the reading context, not a cell"),
            Map.entry("repsOf", "a repetition coordinate of the reading context, not a cell"),
            Map.entry("scopePrefixOf", "a repetition coordinate of the reading context, not a cell"),
            Map.entry("containedFields", "model structure: the fields a group declares"),
            Map.entry("enumCategoryValue", "a projection of the value it is passed, not a read of a cell"),
            Map.entry("declaredKind", "model metadata"),
            Map.entry("dateFormatOf", "model metadata"),
            Map.entry("datePrecisionOf", "model metadata"),
            Map.entry("signed", "model metadata: a number field's declared sign"),
            Map.entry("presenceUnknown", "a parallel iteration's invalid-index state, decided by the iteration"),
            Map.entry("starOmission", "validation's omission typing; a computation types no omission"),
            Map.entry("messageDisplayValue", "rule-message interpolation, which the compute path never renders"),
            Map.entry("partialDateBoundaryAt", "a read of a partial-precision Date cell, which no computation can "
                    + "write: A12 refuses one as a computation target "
                    + "(adapter.laws.ComputedPartialDateTargetAdmissionDiffTest, finding KF81)"),
            Map.entry("customConditionData", "the compute path registers no custom condition, so one in a "
                    + "computation is refused before any read (Interpreter.evalCustom)"));

    @Test
    void everyContentReadIsOverriddenOrDeclaredStructural() throws IOException {
        Checkout checkout = Checkout.locate();
        String contract = Files.readString(checkout.resolve(EVAL + "EvalContext.kt"));
        String decorator = Files.readString(checkout.resolve(EVAL + "OverlayContext.kt"));
        assertEquals(List.of(), problems(contract, decorator, STRUCTURAL));
    }

    /** The does-fire half, on a two-member contract whose decorator overrides one and declares nothing. */
    @Test
    void anUndeclaredDelegatedMemberAndAStaleEntryAreBothReported() {
        String contract = """
                interface EvalContext {
                    fun field(path: String): Val
                    fun groupFilled(groupPath: String): Boolean = false
                    val evaluated: Boolean
                }
                """;
        String decorator = """
                internal class OverlayContext(
                    private val d: EvalContext,
                ) : EvalContext by d {
                    override fun field(path: String): Val = d.field(path)
                }
                """;
        assertEquals(List.of("groupFilled is delegated but not declared structural",
                        "evaluated is delegated but not declared structural",
                        "repsOf is declared structural but is not an EvalContext member"),
                problems(contract, decorator, Map.of("repsOf", "coordinate")));
        assertEquals(List.of("field is declared structural but the decorator overrides it"),
                problems(contract, decorator, Map.of("field", "x", "groupFilled", "y", "evaluated", "z")));
    }

    static List<String> problems(String contract, String decorator, Map<String, String> structural) {
        // Properties too: the row-content flag is a `val`, and a scan of `fun` alone passed while it read the raw row.
        Pattern declaration = Pattern.compile("(?m)^    (?:fun|val) (\\w+)\\b");
        Set<String> members = names(body(contract, "interface EvalContext"), declaration);
        // The decorator implements this one too, and `by d` does not forward it.
        if (contract.contains("interface GroupListEnvironmentProvider")) {
            members.addAll(names(body(contract, "interface GroupListEnvironmentProvider"), declaration));
        }
        Set<String> overridden = names(body(decorator, "class OverlayContext"),
                Pattern.compile("(?m)^    override (?:fun|val) (\\w+)\\b"));
        List<String> problems = new java.util.ArrayList<>();
        for (String member : members) {
            if (!overridden.contains(member) && !structural.containsKey(member)) {
                problems.add(member + " is delegated but not declared structural");
            }
        }
        for (String name : new TreeSet<>(structural.keySet())) {
            if (!members.contains(name)) {
                problems.add(name + " is declared structural but is not an EvalContext member");
            } else if (overridden.contains(name)) {
                problems.add(name + " is declared structural but the decorator overrides it");
            }
        }
        return problems;
    }

    /** From the declaration line to the first column-zero closing brace, which ends a top-level declaration. */
    private static String body(String source, String declaration) {
        int start = source.indexOf(declaration);
        if (start < 0) {
            throw new IllegalStateException("no '" + declaration + "' in the scanned source");
        }
        int end = source.indexOf("\n}", start);
        return source.substring(start, end < 0 ? source.length() : end);
    }

    private static Set<String> names(String text, Pattern declaration) {
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = declaration.matcher(text);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        if (names.isEmpty()) {
            throw new IllegalStateException("the scan matched no member — the declaration pattern no longer fits");
        }
        return names;
    }
}

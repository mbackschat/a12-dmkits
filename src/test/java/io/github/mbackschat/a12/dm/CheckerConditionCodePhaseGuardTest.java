package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Every A12 code the portable checker reports has a place in the texts' order, or a stated reason for none.
 *
 * <p><b>The defect this exists to stop, measured 2026-09-25.</b> {@code Check.kt}'s two phase sets say which
 * classes of a DSL text are withheld behind an earlier defect: {@code POSITIONED_CONDITION_CODES}, drawn during
 * the parse, and {@code POST_PARSE_CONDITION_CODES}, drawn once it finished. A code in neither is neither
 * withheld nor placed, so its class co-fires beside the one A12 names — and 106 operand-gate codes were in
 * neither, from every operator family, because the sets were grown one measured family at a time and a new
 * family never had to say where its codes stand. Nothing went red: each family's own test builds its findings
 * directly, and the report assembly that applies the sets sees only the codes someone listed.
 *
 * <p><b>Both sides are derived, the exemptions are not.</b> The reported codes come from
 * {@link CheckerReportedCodes}, the phase sets are read out of {@code Check.kt} as source, and a code in
 * neither must be named here with the reason it stands outside the order. The list is authored on purpose:
 * "this class is not about a DSL text" is a judgement no scan can make, and writing it down is the moment the
 * question gets asked. A code that stops being reported must leave the list too, so it cannot rot into a
 * catalogue of past codes.
 *
 * <p><b>What it proves, and what it does not.</b> It proves every reported code was CLASSIFIED — never that
 * the class it was given is A12's. That is the laws tier's to measure, and the sets' own KDocs name what was.
 */
class CheckerConditionCodePhaseGuardTest {

    private static final Path CHECK = Path.of(
            "consistency/src/commonMain/kotlin/io/github/mbackschat/a12/dm/consistency/Check.kt");

    private static final Pattern CODE = Pattern.compile("\"(MVK_[A-Z0-9_]+)\"");

    /** Why a reported code stands outside the texts' order. */
    private enum Outside {
        /** Ordered by the reference family's ROLE, not its code: a condition reference is placed as one. */
        REFERENCE,
        /** A suppression's class, raised ahead of the texts as their preamble (KF298). */
        PREAMBLE,
        /** The error-text checker's, judged only beside a sound DSL by the message law (KF338). */
        ERROR_TEXT,
        /** About a declaration member other than its texts: a name, the code, the locales, a message's presence. */
        DECLARATION_MEMBER,
        /** The calculation module's classes about a computation's other members, unmeasured against a text (DG86). */
        CALCULATION_MEMBER,
        /** About a field type, an enumeration, a group, a name or a path, which carry no DSL text at all. */
        MODEL_STRUCTURE,
    }

    private static final Map<String, Outside> OUTSIDE_THE_TEXTS = outside(
            Map.entry(Outside.REFERENCE, Set.of("MVK_INVALID_ENTITY", "MVK_FIELDNAME_NOT_UNIQUE",
                    "MVK_INVALID_ROOT_GROUP", "MVK_TOO_MANY_PARENT_GROUPS", "MVK_ERROR_FIELD_MISSING",
                    "MVK_MISSING_ERROR_FIELD")),
            Map.entry(Outside.PREAMBLE,
                    Set.of("MVK_INVALID_SUPPRESSED_WARNING", "MVK_INVALID_COMPARE_DEC_PLACES")),
            Map.entry(Outside.ERROR_TEXT, Set.of("MVK_INDEX_FOR_ERROR_TEXT_INVALID")),
            Map.entry(Outside.DECLARATION_MEMBER, Set.of("MVK_RULE_NAME_INVALID", "MVK_ERROR_CODE_MISSING",
                    "MVK_ERROR_MESSAGE_MISSING", "MVK_ERROR_MESSAGE_FOR_LANGUAGE_MISSING", "MVK_LANGUAGE_DUPLICATE",
                    "MVK_SUPP_LANGUAGES_MISSING", "MVK_CONDITION_MISSING")),
            Map.entry(Outside.CALCULATION_MEMBER, Set.of("MVK_ERROR_EMPTY_PRECONDITION",
                    "MVK_ERROR_WRONG_ROUNDING_MODE", "MVK_ERROR_INDEX_AS_CALCULATED_FIELD", "MVK_ERROR_CALC_CYCLE")),
            Map.entry(Outside.MODEL_STRUCTURE, Set.of("MVK_ADDITIONAL_CHECK_INVALID", "MVK_ARITY_TOO_BIG",
                    "MVK_CATEGORY_VALUE_DUPLICATE", "MVK_CATEGORY_VALUE_INVALID", "MVK_COUNT_OF_VALUES_INVALID",
                    "MVK_DISPLAY_VALUES_DUPLICATE", "MVK_DISPLAY_VALUE_INVALID",
                    "MVK_ENUM_VALUE_WITH_INVALID_CHARACTER",
                    "MVK_ERROR_MESSAGE_AND_NO_PATTERN", "MVK_ERROR_MESSAGE_AND_PATTERN_INVALID",
                    "MVK_FRACT_DIGITS_INVALID", "MVK_FRACT_DIGITS_MISSING", "MVK_INDEX_FIELD_NO_VALUE_VALIDATION",
                    "MVK_INTERNAL_VALUES_AND_DISPLAY_VALUES", "MVK_INTERNAL_VALUES_DUPLICATE",
                    "MVK_INTERNAL_VALUE_INVALID", "MVK_INVALID_INDEX_FIELD", "MVK_INVALID_SORTINFO",
                    "MVK_INVALID_SORT_FIELD", "MVK_LINE_BREAK_AND_MAX_LENGTH_1_INVALID", "MVK_MAX_LENGTH_TOO_BIG",
                    "MVK_MAX_LENGTH_TOO_SMALL", "MVK_MAX_TOO_BIG", "MVK_MAX_VALUES_INVALID",
                    "MVK_MIN_LENGTH_BIGGER_MAX_LENGTH", "MVK_MIN_LENGTH_TOO_BIG", "MVK_MIN_VALUE_BIGGER_THAN_MAX_VALUE",
                    "MVK_MISSING_CATEGORY_VALUE", "MVK_MISSING_INTERNAL_VALUE", "MVK_NAME_INVALID_A12_DV",
                    "MVK_NAME_INVALID_DOT_COLON_A12_DV", "MVK_NAME_STARTS_WITH_DIGIT",
                    "MVK_NAME_STARTS_WITH_DIGIT_A12_DV", "MVK_NAME_STARTS_WITH_XML", "MVK_NAME_STARTS_WITH_XML_A12_DV",
                    "MVK_NO_VAL_AND_HINT_LISTS", "MVK_OPTIONAL_DATE_TYPE_INVALID", "MVK_PATHS_INCONSISTENT",
                    "MVK_PATH_INVALID", "MVK_PATTERN_INVALID", "MVK_ROOT_GROUP_NAME_INVALID",
                    "MVK_SORT_FIELD_DUPLICATE",
                    "MVK_SORT_FIELD_NO_VALUE_VALIDATION", "MVK_STRING_TYPE_NO_VAL_AND_NO_LINE_BREAK",
                    "MVK_STRING_TYPE_PARAMS_INCONSISTENT", "MVK_S_VALUE_INVALID", "MVK_UNSPECIFIED_TYPE_NO_CODEGEN",
                    "MVK_VALUES_DUPLICATE", "MVK_VALUE_TOO_LONG", "MVK_VALUE_TOO_SHORT")));

    @Test
    void everyReportedCodeHasAPlaceInTheTextsOrderOrAStatedReasonForNone() {
        Set<String> positioned = phaseSet("POSITIONED_CONDITION_CODES");
        Set<String> postParse = phaseSet("POST_PARSE_CONDITION_CODES");
        Set<String> unplaced = new TreeSet<>(CheckerReportedCodes.all());
        unplaced.removeAll(positioned);
        unplaced.removeAll(postParse);
        unplaced.removeAll(OUTSIDE_THE_TEXTS.keySet());
        assertEquals(Set.of(), unplaced, "these reported codes are in neither phase set of Check.kt, so their "
                + "class co-fires beside the one A12 names; list each where A12 raises it, or name it here with "
                + "the reason it stands outside the texts' order");
    }

    /** Each code in exactly one place, so no class is both ordered and exempted from the order. */
    @Test
    void noCodeIsClassifiedTwice() {
        Set<String> positioned = phaseSet("POSITIONED_CONDITION_CODES");
        Set<String> postParse = phaseSet("POST_PARSE_CONDITION_CODES");
        Map<String, String> twice = new TreeMap<>();
        Stream.of(positioned, postParse).flatMap(Set::stream)
                .filter(OUTSIDE_THE_TEXTS::containsKey)
                .forEach(code -> twice.put(code, "a phase set and " + OUTSIDE_THE_TEXTS.get(code)));
        positioned.stream().filter(postParse::contains).forEach(code -> twice.put(code, "both phase sets"));
        assertEquals(Map.of(), twice, "a code is classified in two places");
    }

    /** An exemption for a code no family reports any more is a stale claim about the checker. */
    @Test
    void everyExemptionIsAReportedCode() {
        Set<String> stale = new TreeSet<>(OUTSIDE_THE_TEXTS.keySet());
        stale.removeAll(CheckerReportedCodes.all());
        assertEquals(Set.of(), stale, "these exemptions name codes the checker no longer reports");
    }

    /**
     * The positive control: a parse that stopped matching the sets would read both as empty and every code as
     * unplaced, which fails loudly — but one that matched the WRONG block would pass on whatever it found.
     */
    @Test
    void bothPhaseSetsParse() {
        Set<String> positioned = phaseSet("POSITIONED_CONDITION_CODES");
        Set<String> postParse = phaseSet("POST_PARSE_CONDITION_CODES");
        assertTrue(positioned.size() > 100 && positioned.contains("MVK_UNEXPECTED_TOKEN")
                        && positioned.contains("MVK_NO_NUMBER"),
                "POSITIONED_CONDITION_CODES no longer parses into the set this guard reads: " + positioned);
        assertTrue(postParse.size() > 10 && postParse.contains("MVK_ERROR_FIELD_NOT_REFERENCED")
                        && !postParse.contains("MVK_UNEXPECTED_TOKEN"),
                "POST_PARSE_CONDITION_CODES no longer parses into the set this guard reads: " + postParse);
    }

    /** The string literals of the {@code setOf(…)} [name] is initialized with, read to its closing line. */
    private static Set<String> phaseSet(String name) {
        String source = read(CHECK);
        int from = source.indexOf("private val " + name + " = setOf(");
        assertTrue(from >= 0, "Check.kt no longer declares " + name);
        Matcher code = CODE.matcher(source.substring(from, source.indexOf("\n)", from)));
        Set<String> codes = new TreeSet<>();
        while (code.find()) {
            codes.add(code.group(1));
        }
        return codes;
    }

    @SafeVarargs
    private static Map<String, Outside> outside(Map.Entry<Outside, Set<String>>... reasons) {
        return Stream.of(reasons)
                .flatMap(reason -> reason.getValue().stream().map(code -> Map.entry(code, reason.getKey())))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> {
                    throw new IllegalStateException("a code is listed under two reasons");
                }, TreeMap::new));
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

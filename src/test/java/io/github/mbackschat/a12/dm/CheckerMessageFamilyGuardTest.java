package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Every error-text code the portable checker states comes from a family it withholds behind a refused DSL.
 *
 * <p><b>The defect this exists to stop, measured 2026-09-24.</b> A12 examines a declaration's message only
 * once its condition or operation is sound ({@code KERNEL-FINDINGS KF338}), and {@code ReportExecution.kt} applies that
 * law through explicit message-stage contributions. The Base Year and
 * no-text classes were drawn from inside the condition and slot families, so they co-fired beside a refused DSL
 * until each arm became a family of its own. Nothing went red, because each family's own tests build its
 * findings directly and never meet report assembly.
 *
 * <p><b>What is read, and from where.</b> The error-text vocabulary is the kernel's own enum
 * ({@link KernelErrorVocabulary}); the files stating each code are {@link CheckerReportedCodes}'s; the family
 * reaching each file is read from {@code CheckProducers.kt}'s registrations and named top-level helper
 * calls across files; the message-stage families come from those same registrations. A file stating a code
 * must be reached by a withheld family. Where a non-withheld family reaches it too, the literal's function
 * must not reach that family's entry through the calls inside the file. A family judging a carrier with no
 * DSL at all is exempt by name, each with the measurement that says nothing withholds it.
 *
 * <p><b>What it does not see.</b> Class-method dispatch, function values and role-dependent branches are
 * not a Kotlin call-graph proof. Named top-level calls use a same-file-first name approximation, not Kotlin overload resolution.
 * {@code ReferenceDiagnostic.kt} picks its unprefixed codes by the reference's role and is named below
 * with that reason. Files reached only by message entries may contain private classes, as the parameter
 * reader does; the guard does not prove each method inside those classes reachable. The {@code MVK_}-keyed classes of the same checker ({@code MVK_INDEX_FOR_ERROR_TEXT_INVALID},
 * the parameter parser's {@code MVK_UNEXPECTED_TOKEN}) are outside the enum and share a spelling with grammar
 * classes, so no vocabulary selects them.
 */
class CheckerMessageFamilyGuardTest {

    private static final Path SOURCES =
            Path.of("consistency/src/commonMain/kotlin/io/github/mbackschat/a12/dm/consistency");

    /** Files stating an error-text code without hosting a family, each with why that is not a leak. */
    private static final Map<String, String> SHARED_HELPERS = Map.of(
            "ReferenceDiagnostic.kt",
            "turns a reference outcome into a finding; the unprefixed codes belong to the message-parameter ROLE, "
                    + "which only the message-parameter family reads");

    /**
     * Families stating error-text codes about a carrier that has no DSL beside it, each with why nothing
     * withholds them. Keyed by family rather than file, so a rule-message arm added to the same file still
     * needs a withheld family of its own.
     */
    private static final Map<String, String> NO_DSL_FAMILIES = Map.of(
            "FIELD_TYPE_MESSAGE_TEXT",
            "judges a field type's own message, which A12's element walk checks in a pass of its own: a field has "
                    + "no condition, and a refused condition or a broken error target on a rule of the same model "
                    + "leaves the field's class standing (validate.laws.FieldTypeMessageTextLawsTest"
                    + ".aRulesRefusalElsewhereWithholdsNothing)",
            "UNIQUENESS_CRITERIA",
            "judges a document uniqueness criterion's own text, which A12's criterion rule asks before code generation: "
                    + "a criterion has no condition, and a refused rule condition in the same model leaves its "
                    + "error-text class standing (adapter.laws.CheckerUniquenessCriterionAgreementTest, row "
                    + "'a refused rule beside an unterminated parameter')");

    private static final Pattern FAMILY = Pattern.compile("CheckFamily\\.([A-Z_]+)");

    private static final Pattern CALL = Pattern.compile("\\.([a-z]\\w*)\\(");

    /** A top-level declaration, the unit a literal is attributed to; a function's is named, a property's not. */
    private static final Pattern TOP_LEVEL = Pattern.compile("(?m)^(?:[a-z@][^\\n]*)$");

    private static final Pattern FUNCTION = Pattern.compile("^(?:(?:internal|private) )?fun (?:[^(]*\\.)?(\\w+)\\(");

    @Test
    void everyErrorTextCodeComesFromAWithheldFamily() {
        Set<String> vocabulary = KernelErrorVocabulary.errorTextCodes();
        Map<String, Set<String>> reported = CheckerReportedCodes.bySource(vocabulary::contains);
        assertTrue(reported.size() >= 20, "the scan found only " + reported.size() + " error-text codes");
        Set<String> withheld = messageFamilies();
        Map<String, List<Registration>> byFile = registrationsByFile();
        Map<String, String> sourceTexts = sourceTexts();
        Set<String> hosts = new TreeSet<>(withheld);
        hosts.addAll(NO_DSL_FAMILIES.keySet());

        Map<String, String> leaks = new TreeMap<>();
        reported.forEach((code, sources) -> sources.forEach(source -> {
            String file = SOURCES.relativize(Path.of(source)).toString();
            if (SHARED_HELPERS.containsKey(file)) {
                return;
            }
            List<Registration> hosted = byFile.getOrDefault(file, List.of());
            Set<String> entriesWithheld = entries(hosted, hosts, true);
            Set<String> entriesNot = entries(hosted, hosts, false);
            if (entriesWithheld.isEmpty()) {
                leaks.put(code + " in " + file, "the file hosts no explicit message-stage family");
                return;
            }
            if (!entriesNot.isEmpty()) {
                Set<String> reach = reachOf(sourceTexts.get(file), code);
                reach.retainAll(entriesNot);
                if (!reach.isEmpty()) {
                    leaks.put(code + " in " + file, "reached from the non-withheld entry " + reach);
                }
            }
        }));
        assertEquals(Map.of(), leaks, "an error-text class reaches a family A12's message law does not withhold, "
                + "so it co-fires beside a refused DSL; register the family at the message stage or move the arm "
                + "into a family of its own");
    }

    /** The positive control: the registrations parse into the families the withheld set names. */
    @Test
    void theRegistrationsParse() {
        Map<String, List<Registration>> byFile = registrationsByFile();
        Set<String> withheld = messageFamilies();
        assertTrue(withheld.size() >= 8 && withheld.contains("ERROR_TEXT_PRESENCE"), "withheld: " + withheld);
        Set<String> registered = new TreeSet<>();
        byFile.values().forEach(list -> list.forEach(r -> registered.addAll(r.families())));
        Set<String> unregistered = new TreeSet<>(withheld);
        unregistered.removeAll(registered);
        assertEquals(Set.of(), unregistered, "a withheld family has no registration this guard can read");
        Set<String> exemptUnregistered = new TreeSet<>(NO_DSL_FAMILIES.keySet());
        exemptUnregistered.removeAll(registered);
        assertEquals(Set.of(), exemptUnregistered, "an exempt family has no registration, so its exemption is stale");
        Set<String> bothWays = new TreeSet<>(NO_DSL_FAMILIES.keySet());
        bothWays.retainAll(withheld);
        assertEquals(Set.of(), bothWays, "a family is both withheld and exempt from withholding");
        assertTrue(byFile.getOrDefault("DeclarationSlots.kt", List.of()).size() >= 2,
                "DeclarationSlots.kt should host two families: " + byFile.get("DeclarationSlots.kt"));
    }

    /**
     * The positive control for the reach walk, on a synthetic source: the literal's own function, a caller of
     * it and a caller of THAT are reached, while a function stating the code only in a comment is not. Without
     * it, a walk that reached nothing would report no leak on every real file, and read as a green guard.
     */
    @Test
    void theReachWalkFollowsCallsInsideAFile() {
        String source = """
                internal fun TrackedFactBase.withheldEntry(): List<Finding> = middle()

                private fun middle(): List<Finding> = stating()

                private fun stating(): List<Finding> = listOf(finding("ERROR_TEXT_PROBE"))

                internal fun TrackedFactBase.otherEntry(): List<Finding> = quiet()

                // "ERROR_TEXT_PROBE" named in a comment only
                private fun quiet(): List<Finding> = emptyList()
                """;
        assertEquals(Set.of("stating", "middle", "withheldEntry"), reachOf(source, "ERROR_TEXT_PROBE"));
    }

    @Test
    void registrationsFollowHelpersAcrossFilesWithoutHidingNonWithheldEntries() {
        String registrations = """
                internal val CHECK_PRODUCERS: List<CheckProducer> = listOf(
                    CheckProducer.Messages(setOf(CheckFamily.MESSAGE_PARAMETER_REFERENCE, CheckFamily.MESSAGE_VALUE_SUFFIX)) {
                        base, at, text -> base.messageEntry(at, text)
                    },
                    condition(CheckFamily.BASE_YEAR_REQUIREMENT) { base, at -> base.conditionEntry(at) },
                )
                """;
        Map<String, String> sources = Map.of(
                "Entry.kt", """
                    internal fun messageEntry() = bridge() + quiet()
                    internal fun conditionEntry() = leakingHelper()
                    private fun quiet() = 0
                    """,
                "Bridge.kt", """
                    internal fun bridge() = (messageHelper() + nestedBridge()).also { "".isEmpty() }
                    """,
                "Helpers.kt", """
                    internal fun messageHelper() = stating()
                    internal fun leakingHelper() = stating()
                    private fun stating() = "ERROR_TEXT_PROBE"
                    """,
                "nested/Entry.kt", """
                    internal fun nestedBridge() = 0
                    private fun quiet() = "ERROR_TEXT_PROBE"
                    private fun isEmpty() = "ERROR_TEXT_PROBE"
                    """);
        Map<String, List<Registration>> actual = registrationsByFile(registrations, sources);
        Set<String> withheld = Set.of("MESSAGE_PARAMETER_REFERENCE", "MESSAGE_VALUE_SUFFIX");
        List<Registration> helpers = actual.getOrDefault("Helpers.kt", List.of());
        assertTrue(entries(helpers, withheld, true).contains("messageHelper"), actual.toString());
        Set<String> nonWithheld = entries(helpers, withheld, false);
        nonWithheld.retainAll(reachOf(sources.get("Helpers.kt"), "ERROR_TEXT_PROBE"));
        assertEquals(Set.of("leakingHelper", "stating"), nonWithheld,
                "a cross-file condition call must still expose its message literal");
        assertTrue(entries(actual.getOrDefault("Entry.kt", List.of()), withheld, true).contains("quiet"),
                "a reached private name binds inside its declaring file");
        assertEquals(Set.of("nestedBridge"), entries(actual.getOrDefault("nested/Entry.kt", List.of()), withheld, true),
                "the reached nested file keeps its relative identity without binding its private name twins");
    }

    /** One `CHECK_PRODUCERS` entry: the families it completes and the functions it calls. */
    private record Registration(Set<String> families, Set<String> entries) {
    }

    /** R5 declares message-stage entry at registration; no code or parallel family table selects it. */
    private static Set<String> messageFamilies() {
        Set<String> families = new TreeSet<>();
        for (String entry : entriesOf(block(read(SOURCES.resolve("CheckProducers.kt")),
                "internal val CHECK_PRODUCERS: List<CheckProducer> = listOf("))) {
            if (entry.stripLeading().startsWith("messages(") || entry.stripLeading().startsWith("CheckProducer.Messages(")
                    || entry.contains("DeclarationStage.MESSAGE)")) {
                families.addAll(familiesIn(entry));
            }
        }
        return families;
    }

    private static Map<String, List<Registration>> registrationsByFile() {
        Map<String, String> textByFile = sourceTexts();
        return registrationsByFile(read(SOURCES.resolve("CheckProducers.kt")), textByFile);
    }

    private record FunctionBody(String file, String name, String text, boolean local) {
    }

    /** Named top-level calls are followed across files; private names bind only within their declaring file. */
    private static Map<String, List<Registration>> registrationsByFile(String registrations, Map<String, String> textByFile) {
        List<FunctionBody> functions = new ArrayList<>();
        textByFile.forEach((file, text) -> functions.addAll(functionsIn(file, text)));
        Map<String, List<Registration>> byFile = new TreeMap<>();
        for (String entry : entriesOf(block(registrations,
                "internal val CHECK_PRODUCERS: List<CheckProducer> = listOf("))) {
            Set<String> families = familiesIn(entry);
            Set<FunctionBody> reached = new LinkedHashSet<>();
            List<FunctionBody> work = new ArrayList<>();
            CALL.matcher(entry).results().map(m -> m.group(1)).forEach(name ->
                    work.addAll(callees(functions, "CheckProducers.kt", name)));
            for (int at = 0; at < work.size(); at++) {
                FunctionBody function = work.get(at);
                if (!reached.add(function)) continue;
                NAMED_CALL.matcher(function.text()).results().map(m -> m.group(1)).forEach(name ->
                        work.addAll(callees(functions, function.file(), name)));
            }
            reached.forEach(function -> byFile.computeIfAbsent(function.file(), key -> new ArrayList<>())
                    .add(new Registration(families, Set.of(function.name()))));
        }
        return byFile;
    }

    private static final Pattern NAMED_CALL = Pattern.compile("\\b([a-z]\\w*)\\s*\\(");

    private static List<FunctionBody> callees(List<FunctionBody> functions, String file, String name) {
        List<FunctionBody> local = functions.stream().filter(f -> f.file().equals(file) && f.name().equals(name)).toList();
        return local.isEmpty() ? functions.stream().filter(f -> !f.local() && f.name().equals(name)).toList() : local;
    }

    private static List<FunctionBody> functionsIn(String file, String source) {
        String text = withoutComments(source);
        List<int[]> extents = new ArrayList<>();
        List<String> names = new ArrayList<>();
        List<Boolean> privateNames = new ArrayList<>();
        Matcher line = TOP_LEVEL.matcher(text);
        while (line.find()) {
            Matcher function = FUNCTION.matcher(line.group());
            extents.add(new int[] {line.start(), text.length()});
            names.add(function.find() ? function.group(1) : null);
            privateNames.add(line.group().startsWith("private "));
        }
        List<FunctionBody> functions = new ArrayList<>();
        for (int i = 0; i < extents.size(); i++) {
            if (i + 1 < extents.size()) extents.get(i)[1] = extents.get(i + 1)[0];
            if (names.get(i) != null) functions.add(new FunctionBody(file, names.get(i), body(text, extents.get(i)), privateNames.get(i)));
        }
        return functions;
    }

    private static Map<String, String> sourceTexts() {
        Map<String, String> texts = new TreeMap<>();
        for (Path path : sources()) {
            String name = SOURCES.relativize(path).toString();
            assertTrue(texts.putIfAbsent(name, read(path)) == null, "source filename collision: " + name);
        }
        return texts;
    }

    private static Set<String> entries(List<Registration> hosted, Set<String> withheld, boolean inWithheld) {
        Set<String> entries = new TreeSet<>();
        hosted.stream().filter(r -> withheld.containsAll(r.families()) == inWithheld)
                .forEach(r -> entries.addAll(r.entries()));
        return entries;
    }

    /** Every function of [text] whose calls, inside the file, lead to a literal of [code]. */
    private static Set<String> reachOf(String source, String code) {
        String text = withoutComments(source);
        List<int[]> extents = new ArrayList<>();
        List<String> names = new ArrayList<>();
        Matcher line = TOP_LEVEL.matcher(text);
        while (line.find()) {
            Matcher function = FUNCTION.matcher(line.group());
            extents.add(new int[] {line.start(), text.length()});
            names.add(function.find() ? function.group(1) : null);
        }
        for (int i = 0; i + 1 < extents.size(); i++) {
            extents.get(i)[1] = extents.get(i + 1)[0];
        }
        Set<String> reach = new LinkedHashSet<>();
        for (int i = 0; i < extents.size(); i++) {
            if (names.get(i) != null && body(text, extents.get(i)).contains("\"" + code + "\"")) {
                reach.add(names.get(i));
            }
        }
        boolean grew = true;
        while (grew) {
            grew = false;
            for (int i = 0; i < extents.size(); i++) {
                String name = names.get(i);
                if (name == null || reach.contains(name)) {
                    continue;
                }
                String body = body(text, extents.get(i));
                if (reach.stream().anyMatch(callee -> Pattern.compile("\\b" + callee + "\\(").matcher(body).find())) {
                    grew = reach.add(name);
                }
            }
        }
        return reach;
    }

    private static String body(String text, int[] extent) {
        return text.substring(extent[0], extent[1]);
    }

    /** The top-level comma-separated entries of the list opened by [block]'s head. */
    private static List<String> entriesOf(String block) {
        List<String> entries = new ArrayList<>();
        int depth = 0;
        int from = block.indexOf('(') + 1;
        for (int i = from; i < block.length(); i++) {
            char c = block.charAt(i);
            if (c == '(' || c == '{' || c == '[') {
                depth++;
            } else if (c == ')' || c == '}' || c == ']') {
                depth--;
            } else if (c == ',' && depth == 0) {
                entries.add(block.substring(from, i));
                from = i + 1;
            }
        }
        entries.add(block.substring(from));
        return entries.stream().filter(e -> !e.isBlank()).toList();
    }

    private static Set<String> familiesIn(String text) {
        Set<String> families = new TreeSet<>();
        FAMILY.matcher(text).results().forEach(m -> families.add(m.group(1)));
        return families;
    }

    /** From [head] to the line that closes it at column 0. */
    private static String block(String source, String head) {
        int from = source.indexOf(head);
        assertTrue(from >= 0, "no longer declared: " + head);
        return withoutComments(source.substring(from, source.indexOf("\n)", from)));
    }

    /** Comments blanked, not removed, so an offset still points where it did. */
    private static String withoutComments(String text) {
        StringBuilder out = new StringBuilder(text);
        Matcher comment = Pattern.compile("/\\*.*?\\*/|//[^\\n]*", Pattern.DOTALL).matcher(text);
        while (comment.find()) {
            for (int i = comment.start(); i < comment.end(); i++) {
                if (out.charAt(i) != '\n') {
                    out.setCharAt(i, ' ');
                }
            }
        }
        return out.toString();
    }

    private static List<Path> sources() {
        try (var files = Files.walk(SOURCES)) {
            return files.filter(p -> p.toString().endsWith(".kt")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

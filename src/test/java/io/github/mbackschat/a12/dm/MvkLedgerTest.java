package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.opentest4j.TestAbortedException;

/**
 * Instrument 2 of the constraint-mining discipline: {@code docs/MVK-LEDGER.md} must classify
 * <em>exactly</em> the {@code MVK_*} codes the kernel can report — no missing codes (a kernel
 * upgrade adding diagnostics fails here, proactively) and no stale ones.
 *
 * <p><b>Five sources</b>, and the count is the point: four internal-tier enums (loaded reflectively from the
 * test classpath only — N8: tests may read internal tier), the parser's own MESSAGE BUNDLE — which is the
 * same six codes as the fourth enum, read twice from independent artifacts so the two must agree — and the
 * {@code MM_Common} metamodel's rules (the deep source — structural laws <em>are</em> metamodel rules;
 * checked opt-in against the sibling {@code a12-kernel-mm} clone).
 *
 * <p><b>The bundle was added 2026-09-12 because its absence made this instrument quietly incomplete.</b> The
 * ledger claims to classify exactly what the kernel can report, and this test reported it complete while all
 * six parser-bundle codes were missing — including the four {@code checkConsistency} actually returns for a
 * condition that does not parse. A denominator assembled from the sources one happens to know is the failure
 * mode a completeness guard exists to prevent, so a new source belongs here rather than in a reader's memory.
 */
class MvkLedgerTest {

    private static final String[] ENUM_CLASSES = {
            "com.mgmtp.a12.kernel.core.parser.internal.error.ParserErrorTypesEnum",
            "com.mgmtp.a12.kernel.core.calc.internal.util.CalculationErrorType",
            "com.mgmtp.a12.kernel.core.service.internal.errortext.FehlertextErrorType",
            // Added 2026-09-12. The parse-failure codes were first found in a resource bundle and recorded as
            // living in no enum at all; they have an enum, one package over from the one already read here.
            // The absence claim was an enumeration over ONE enum stated as an enumeration over all of them.
            "com.mgmtp.a12.kernel.core.parser.internal.common.error.CommonParserErrorTypesEnum",
    };

    /** The parser's own message bundle — the source the three enums above do not cover. */
    private static final String PARSER_MESSAGE_BUNDLE =
            "internal.vk_parser_common_error_messages.error_messages";

    private static final String KERNEL_MM_DIR =
            System.getProperty("a12.kernel.mm.dir", "../a12-kernel-mm/documentModels");

    /**
     * The codes whose ledger rows rest on the metamodel axis being unmeasured — seven of the eleven that
     * block a portable VALID verdict.
     */
    private static final Set<String> UNMEASURED_ON_THE_METAMODEL_AXIS = Set.of(
            "MVK_ERROR_FIELD_NOT_VALID",
            "MVK_INVALID_PREDEFINED_TYPE",
            "MVK_NOT_ON_ERRORFIELD_PATH",
            "MVK_ONLY_STRING_ENUM_NUMBER_CMP_DATE_ALLOWED_NO_FIELD",
            "MVK_RULE_GROUP_IS_EMPTY_OR_INVALID",
            "MVK_CALCULATION_FIELD_AND_NO_CALCULATION_RULE",
            "MVK_ERROR_ITERATION_UNCHECKED");

    /**
     * KF418's witness: measured FIRING while its raiser count is zero everywhere in the kernel checkout.
     *
     * That is the exact mechanism making a Java-source absence inconclusive, so a sweep that cannot see THIS
     * code cannot support an absence claim about any code.
     */
    private static final String METAMODEL_AXIS_CONTROL = "MVK_INTERNAL_VALUE_INVALID";

    /**
     * Below this the corpus was not really read. The live count is <b>105 codes carried by a metamodel rule
     * across the corpus's 59 JSON models</b> (2026-09-18) — the ledger's own long-standing figure for
     * {@code MM_Common} carrier rules, which is what corroborates this sweep against an established
     * measurement. The floor sits well under it because it answers "did the sweep happen".
     */
    private static final int METAMODEL_CODE_FLOOR = 60;

    /** The consult-only kernel checkout — the SOURCE oracle for whether a code has a Java raiser. */
    private static final String KERNEL_DIR = System.getProperty("a12.kernel.dir", "../a12-kernel");

    /**
     * The four of the seven whose ledger rows claim no JAVA raiser — the claim this guard locks.
     *
     * <p><b>Three more rows claimed it and were wrong, which is why this exists</b> (measured 2026-09-19).
     * {@code MVK_ERROR_FIELD_NOT_VALID} is raised at {@code ParserService:517}, which calls a
     * {@code throwError} overload that does {@code throw new MVKParserError};
     * {@code MVK_RULE_GROUP_IS_EMPTY_OR_INVALID} at {@code CheckRegelKontextEntityImpl:94}; and
     * {@code MVK_ERROR_ITERATION_UNCHECKED} at {@code NormalisierteBerechnungsFormHelper:251} and
     * {@code :463}, both behind {@code isHModel}. Three rows of a CI-locked ledger carried a false negative
     * existential because the METAMODEL axis got a guard and the JAVA axis — the half asserted first and
     * relied on most — got none. An absence nobody executes is an absence nobody re-checks.
     */
    private static final Set<String> NO_JAVA_RAISER = Set.of(
            "MVK_INVALID_PREDEFINED_TYPE",
            "MVK_NOT_ON_ERRORFIELD_PATH",
            "MVK_ONLY_STRING_ENUM_NUMBER_CMP_DATE_ALLOWED_NO_FIELD",
            "MVK_CALCULATION_FIELD_AND_NO_CALCULATION_RULE");

    /**
     * A code measured to HAVE a Java raiser, so a mis-scoped or empty walk cannot pass as an absence.
     *
     * <p>The metamodel sweep beside this one needs a control for the same reason; the difference is that this
     * one is a REAL raiser rather than a witness borrowed from another axis, so it fails if the walk stops
     * reaching {@code kernel-core-parser} at all.
     */
    private static final String JAVA_RAISER_CONTROL = "MVK_ERROR_FIELD_NOT_VALID";

    /**
     * Below this the checkout was not really walked. Live count <b>1821 non-generated {@code .java} files</b>
     * (2026-09-19); the floor sits well under it because it answers "did the walk happen", not "how big is
     * the kernel".
     */
    private static final int KERNEL_JAVA_FILE_FLOOR = 1200;

    @Test
    void ledgerCoversTheKernelEnumsExactly() throws IOException {
        Set<String> kernelCodes = new LinkedHashSet<>();
        for (String className : ENUM_CLASSES) {
            Class<?> enumClass;
            try {
                enumClass = Class.forName(className);
            } catch (ClassNotFoundException e) {
                throw new TestAbortedException("kernel enum not on test classpath: " + className);
            }
            for (Object constant : enumClass.getEnumConstants()) {
                String name = ((Enum<?>) constant).name();
                if (name.startsWith("MVK_")) {
                    kernelCodes.add(name);
                }
            }
        }
        assertCovered(kernelCodes, "kernel enums");
    }

    /**
     * The source the ledger was blind to until 2026-09-12, and a second reading of the fourth enum.
     *
     * <p>The three enums originally listed here do not contain the codes {@code checkConsistency} actually
     * returns for a stored condition that does not parse, and all six were missing from the ledger while this
     * test reported it complete. They were then recorded as living in no enum at all, which was false —
     * {@code CommonParserErrorTypesEnum} declares exactly these six and is now read above, so this arm is a
     * cross-check on the same section rather than its only source. Four of them are
     * measured in {@code rulekit.validate.laws.RuleConditionSyntaxLawsTest}.
     *
     * <p>The bundle ships inside {@code kernel-core-parser}, so it is read from the test classpath like the
     * enums rather than from the sibling source checkout — no opt-in gate, and a kernel upgrade that adds a
     * parser diagnostic fails here the same way a new enum constant does.
     */
    @Test
    void ledgerCoversTheParserMessageBundleExactly() throws IOException {
        ResourceBundle bundle;
        try {
            bundle = ResourceBundle.getBundle(PARSER_MESSAGE_BUNDLE, Locale.US);
        } catch (MissingResourceException absent) {
            throw new TestAbortedException("parser message bundle not on test classpath: " + PARSER_MESSAGE_BUNDLE);
        }
        Set<String> bundleCodes = new LinkedHashSet<>();
        for (String key : bundle.keySet()) {
            if (key.startsWith("MVK_")) {
                bundleCodes.add(key);
            }
        }
        // A bundle that resolved but carries no MVK_ key would make the assertion below vacuously true, which
        // is precisely the silent-empty-denominator failure this whole ledger exists to prevent.
        assertFalse(bundleCodes.isEmpty(),
                "the parser message bundle resolved but carries no MVK_ key — the denominator is empty and "
                        + "this check would pass while verifying nothing");
        assertCovered(bundleCodes, "parser message bundle");
    }

    @Test
    void ledgerCoversTheMetamodelRuleCodesExactly() throws IOException {
        // The deep source: MM_Common's rules ARE the structural laws checkConsistency enforces
        // (via the generated mmvalidators on the classpath). Opt-in like the kernel-mm harness.
        Path dir = Path.of(KERNEL_MM_DIR, "MM_Common_v2");
        assumeTrue(Files.isDirectory(dir), "a12-kernel-mm not present — skipping metamodel parity");

        Path canonical;
        try (var files = Files.list(dir)) {
            canonical = files
                    .filter(f -> f.getFileName().toString().endsWith(".json"))
                    .filter(f -> !f.getFileName().toString().contains("-previous"))
                    .sorted()
                    .findFirst()
                    .orElseThrow();
        }
        Set<String> mmCodes = new LinkedHashSet<>();
        Matcher m = Pattern.compile("\"errorCode\": \"(MVK_[A-Z_0-9]+)\"").matcher(Files.readString(canonical));
        while (m.find()) {
            mmCodes.add(m.group(1));
        }
        assertCovered(mmCodes, "MM_Common metamodel rules");
    }

    /**
     * The seven codes the ledger leaves UNMEASURED on the metamodel axis carry no metamodel rule here.
     *
     * <p><b>It enforces a re-open trigger that was prose until now.</b> Those rows say no JAVA site in kernel
     * {@code 30.8.1} raises the code, qualified by KF415: a zero raiser count over the kernel's Java sources
     * is not dormancy, because the metamodel raises codes appearing in no file of that checkout. So the
     * disposition rests on the metamodel axis being unmeasured, and
     * {@code PORTABLE-CONSISTENCY-IMPLEMENTATION-PLAN.md} names reaching it as the re-open trigger for all
     * seven — and for the VALID bar that seven of its eleven blocking codes sit on. A trigger nothing
     * executes is the "parsed but never consumed" class, so it gets a test: should a corpus bump give any of
     * the seven a carrier rule, that row's disposition is stale and the code becomes a candidate false-pass
     * route, which this fails to say.
     *
     * <p><b>Wider than {@link #ledgerCoversTheMetamodelRuleCodesExactly}, deliberately.</b> That test reads
     * the first canonical file of {@code MM_Common_v2} to check ledger COVERAGE; this sweeps every file of
     * the corpus, because an absence claim may not rest on the one directory the codes were expected in.
     * The breadth is DEFENSIVE rather than corrective, which is worth knowing: measured 2026-09-18, both
     * scopes yield the same 105 codes and nothing is carried outside {@code MM_Common_v2}'s canonical
     * files — so a future divergence between them is a real signal about where the laws moved, not noise.
     *
     * <p><b>The positive control is what makes the absence mean anything, and it is not decorative.</b>
     * {@link #METAMODEL_AXIS_CONTROL} is KF418's witness — a code measured FIRING while its raiser count is
     * zero everywhere in the kernel checkout, which is precisely the mechanism that makes a Java-source
     * absence inconclusive. It is carried by a rule here, so this instrument can see a metamodel-axis raiser;
     * without that row, a mis-scoped or empty read would report all seven absent and look like a result. The
     * code floor guards the same failure from the other side. *Recall limit, stated because this is a
     * negative existential: it measures THIS estate's corpus (60 files, 105 carried codes — the ledger's own
     * long-standing figure), so it cannot exclude a metamodel we do not hold.*
     */
    @Test
    void theMetamodelAxisCarriesNoRuleForTheCodesTheLedgerLeavesUnmeasuredThere() throws IOException {
        Path root = Path.of(KERNEL_MM_DIR);
        assumeTrue(Files.isDirectory(root), "a12-kernel-mm not present — skipping metamodel carrier sweep");

        Set<String> carried = codesCarriedByAnyMetamodelRule(root);

        assertTrue(
                carried.contains(METAMODEL_AXIS_CONTROL),
                "the positive control " + METAMODEL_AXIS_CONTROL + " is not carried by any rule in "
                        + root.toAbsolutePath() + ", so this sweep cannot see a metamodel-axis raiser at all "
                        + "and the absences below would be an artefact of the read rather than a measurement");
        assertTrue(
                carried.size() >= METAMODEL_CODE_FLOOR,
                "swept only " + carried.size() + " carried codes, below the floor of " + METAMODEL_CODE_FLOOR
                        + " — a partial read reports every code absent and looks clean");

        var nowCarried = UNMEASURED_ON_THE_METAMODEL_AXIS.stream().filter(carried::contains).sorted().toList();
        assertEquals(
                java.util.List.of(),
                nowCarried,
                nowCarried.size() + " code(s) dispositioned as unmeasured on the metamodel axis now HAVE a "
                        + "carrier rule in the corpus. The re-open trigger has fired: re-disposition each row "
                        + "in docs/MVK-LEDGER.md, and re-check whether it is a live false-pass route the "
                        + "checker should report — " + nowCarried);
    }

    /**
     * The JAVA half of the same absence claim, which went unguarded until three rows of it turned out false.
     *
     * <p><b>What the rows assert.</b> Four of the seven say nothing in kernel {@code 30.8.1}'s Java sources
     * raises the code, so the pre-flight they were credited with guards nothing. That is a negative
     * existential over 1821 files, exactly the shape this estate requires an enumeration for — and it had
     * none, while its metamodel sibling next door had a control, a floor and a reader test.
     *
     * <p><b>The discriminator is declaration versus use, because every one of the seven appears in Java.</b>
     * Each is an enum constant somewhere ({@code ParserErrorTypesEnum}, {@code CalculationErrorType}), so
     * "appears in a .java file" proves nothing. A DECLARATION is the enum-constant line itself; anything else
     * naming the code is a USE, and a use in this estate is a raise — the three found on 2026-09-19 reach
     * {@code throw new MVKParserError} or {@code errorLookup.reportError} within two frames. Measured that
     * day: all seven have exactly one declaration, and the use counts are 1/0/0/0/1/0/2.
     *
     * <p><b>Reachability is deliberately NOT claimed.</b> Two of the three raisers sit behind a condition
     * ({@code !parser.isLenient()}, {@code isHModel}), so this says a raiser EXISTS, never that a given model
     * reaches it — the ledger's rows make the existence claim, and that is the one guarded here.
     *
     * <p><b>What this does NOT cover, stated because a sibling claim shape failed the same day.</b> Some rows
     * do not say "no raiser exists" but "the raiser exists and nothing CALLS it" — a call-site absence, which
     * needs a call graph rather than a token sweep and is not checked here. {@code MVK_INVALID_TABLE_ENUM}'s
     * row carried exactly that claim about {@code CheckEntityUtils.checkNoTableEnum} and it was false
     * ({@code CheckFeldOpImpl:105} calls it, live, in the {@code FELD_WERT_ALS_ZAHL} case; corrected
     * 2026-09-19 in [KF422]). The hand list above is therefore deliberate rather than derived from the ledger
     * prose: a regex over these cells cannot tell "no raiser" from "raiser never called", and mis-reading the
     * second as the first is how a correct row nearly got "corrected" during this very sweep.
     */
    @Test
    void theJavaAxisRaisesNoneOfTheCodesTheLedgerCallsDormantThere() throws IOException {
        Path root = Path.of(KERNEL_DIR);
        assumeTrue(Files.isDirectory(root), "a12-kernel not present — skipping the Java raiser sweep");

        Map<String, Integer> declarations = new LinkedHashMap<>();
        Map<String, Set<String>> uses = new LinkedHashMap<>();
        Map<String, Pattern> declarationOf = new LinkedHashMap<>();
        for (String code : UNMEASURED_ON_THE_METAMODEL_AXIS) {
            declarations.put(code, 0);
            uses.put(code, new LinkedHashSet<>());
            declarationOf.put(code, Pattern.compile("^\\s*" + Pattern.quote(code) + "\\s*,"));
        }

        java.util.List<Path> sources;
        try (var walk = Files.walk(root)) {
            sources = walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .filter(p -> !p.toString().contains("/build/") && !p.toString().contains("delombok"))
                    .toList();
        }
        for (Path source : sources) {
            // Lenient decode: a stray non-UTF-8 byte must not abort a sweep whose whole job is completeness.
            String[] lines = new String(Files.readAllBytes(source), StandardCharsets.UTF_8).split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                for (String code : UNMEASURED_ON_THE_METAMODEL_AXIS) {
                    if (!lines[i].contains(code)) {
                        continue;
                    }
                    if (declarationOf.get(code).matcher(lines[i]).find()) {
                        declarations.merge(code, 1, Integer::sum);
                    } else {
                        uses.get(code).add(root.relativize(source) + ":" + (i + 1));
                    }
                }
            }
        }

        assertTrue(
                sources.size() >= KERNEL_JAVA_FILE_FLOOR,
                "walked only " + sources.size() + " java files, below the floor of " + KERNEL_JAVA_FILE_FLOOR
                        + " — a partial walk reports every code unraised and looks clean");
        assertFalse(
                uses.get(JAVA_RAISER_CONTROL).isEmpty(),
                "the positive control " + JAVA_RAISER_CONTROL + " has no use site in " + root.toAbsolutePath()
                        + ", so this sweep cannot see a Java raiser at all and the absences below would be an "
                        + "artefact of the read rather than a measurement");
        assertEquals(
                java.util.List.of(),
                declarations.entrySet().stream().filter(e -> e.getValue() != 1).map(Map.Entry::toString).sorted()
                        .toList(),
                "every code must be declared exactly once; a different count means the kernel renamed or "
                        + "removed it and the ledger row is stale rather than merely unraised");

        var raised = NO_JAVA_RAISER.stream().filter(c -> !uses.get(c).isEmpty()).sorted()
                .map(c -> c + " -> " + uses.get(c)).toList();
        assertEquals(
                java.util.List.of(),
                raised,
                raised.size() + " code(s) dispositioned as having no JAVA raiser now have a use site. Correct "
                        + "the row in docs/MVK-LEDGER.md and KF415 in docs/KERNEL-FINDINGS.md, and re-check "
                        + "whether the code is a live false-pass route the checker should report — " + raised);
    }

    /**
     * The carrier reader detects a planted code, so the absence above is a measurement and not a blind read.
     *
     * <p>Independent of the sibling corpus on purpose: the sweep's own positive control needs the corpus to
     * be present, and this one proves the READER works whether or not it is — the two failure modes are
     * different, and only this one survives a machine without the clone. A negative row keeps it honest
     * about the pattern rather than about the mere presence of the string.
     */
    @Test
    void theCarrierReaderFindsAPlantedCodeAndIgnoresOneThatIsNotAnErrorCode() throws IOException {
        Path planted = Files.createTempDirectory("mvk-carrier-control");
        String subject = UNMEASURED_ON_THE_METAMODEL_AXIS.iterator().next();
        Files.writeString(
                planted.resolve("model.json"),
                "{\"rules\":[{\"errorCode\": \"" + subject + "\"},"
                        + "{\"comment\":\"MVK_NOT_AN_ERROR_CODE mentioned in prose\"}]}",
                StandardCharsets.UTF_8);

        Set<String> carried = codesCarriedByAnyMetamodelRule(planted);

        assertTrue(
                carried.contains(subject),
                "the reader missed a planted errorCode, so every absence it reports is an artefact of the "
                        + "reader rather than of the corpus");
        assertFalse(
                carried.contains("MVK_NOT_AN_ERROR_CODE"),
                "a code named in prose is not a CARRIER, and counting it would make the sweep report "
                        + "reachability that no rule actually declares");
    }

    /**
     * Every {@code MVK_*} code any rule in the corpus names as its error code.
     *
     * <p>Scoped to {@code *.json}, which is a denominator decision and so is stated: a model IS a JSON
     * document, and walking every regular file instead threw {@link java.nio.charset.MalformedInputException}
     * on a stray {@code .DS_Store} — read lossily that file yields no {@code MVK_} occurrence, so the scope
     * costs no recall while removing a crash on Finder metadata.
     */
    private static Set<String> codesCarriedByAnyMetamodelRule(Path root) throws IOException {
        var carried = new LinkedHashSet<String>();
        var errorCode = Pattern.compile("\"errorCode\"\\s*:\\s*\"(MVK_[A-Z_0-9]+)\"");
        try (var files = Files.walk(root)) {
            var models = files.filter(Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .toList();
            for (Path file : models) {
                Matcher found = errorCode.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (found.find()) {
                    carried.add(found.group(1));
                }
            }
        }
        return carried;
    }

    private static void assertCovered(Set<String> sourceCodes, String sourceName) throws IOException {
        Set<String> ledgerCodes = new LinkedHashSet<>();
        Pattern row = Pattern.compile("^\\| `(MVK_[A-Z_0-9]+)` \\|");
        for (String line : Files.readAllLines(Path.of("docs/MVK-LEDGER.md"))) {
            Matcher m = row.matcher(line);
            if (m.find()) {
                ledgerCodes.add(m.group(1));
            }
        }
        Set<String> missing = new LinkedHashSet<>(sourceCodes);
        missing.removeAll(ledgerCodes);
        assertTrue(missing.isEmpty(), () -> sourceName
                + " codes missing from docs/MVK-LEDGER.md (new diagnostics? classify them): " + missing);
    }

    /**
     * The direction this instrument did not check, which is why a code nothing declares could sit here.
     *
     * <p>{@link #assertCovered} computes only {@code source − ledger}, so a row invented by hand — or left
     * behind when a kernel upgrade removes a diagnostic — was invisible forever, while the ledger's own
     * preamble advertised "exact two-way parity with its source". A row naming a code no kernel source
     * declares is worse than a missing one: it reads as a classified constraint that a consumer may then
     * build a refusal on.
     *
     * <p>It is checked per SECTION rather than against the union, because each section names its own source
     * and a union check would let a code sit under the wrong heading. The metamodel section is excluded: its
     * source is the opt-in sibling clone, so on a machine without it the rows would all read as stale.
     */
    @Test
    void noLedgerRowNamesACodeItsSourceDoesNotDeclare() throws IOException {
        Map<String, Set<String>> declared = new LinkedHashMap<>();
        for (String className : ENUM_CLASSES) {
            Class<?> enumClass;
            try {
                enumClass = Class.forName(className);
            } catch (ClassNotFoundException e) {
                throw new TestAbortedException("kernel enum not on test classpath: " + className);
            }
            String section = className.substring(className.lastIndexOf('.') + 1);
            Set<String> codes = declared.computeIfAbsent(SECTION_OF.getOrDefault(section, section),
                    unused -> new LinkedHashSet<>());
            for (Object constant : enumClass.getEnumConstants()) {
                String name = ((Enum<?>) constant).name();
                if (name.startsWith("MVK_")) {
                    codes.add(name);
                }
            }
        }
        Map<String, Set<String>> stale = new LinkedHashMap<>();
        Map<String, Set<String>> inLedger = rowsBySection();
        declared.forEach((section, codes) -> {
            Set<String> extra = new LinkedHashSet<>(inLedger.getOrDefault(section, Set.of()));
            extra.removeAll(codes);
            if (!extra.isEmpty()) {
                stale.put(section, extra);
            }
        });
        assertEquals(Map.of(), stale,
                "docs/MVK-LEDGER.md rows whose own source no longer declares the code — delete them, or move "
                        + "them under the section of the source that does declare them");
    }

    /** Every `MVK_*` row of the ledger, grouped by the backticked source its `##` heading names. */
    private static Map<String, Set<String>> rowsBySection() throws IOException {
        Map<String, Set<String>> bySection = new LinkedHashMap<>();
        Pattern heading = Pattern.compile("^## `([A-Za-z_0-9]+)`");
        Pattern row = Pattern.compile("^\\| `(MVK_[A-Z_0-9]+)` \\|");
        String section = "";
        for (String line : Files.readAllLines(Path.of("docs/MVK-LEDGER.md"))) {
            Matcher h = heading.matcher(line);
            if (h.find()) {
                section = h.group(1);
                continue;
            }
            Matcher m = row.matcher(line);
            if (m.find()) {
                bySection.computeIfAbsent(section, unused -> new LinkedHashSet<>()).add(m.group(1));
            }
        }
        return bySection;
    }

    /**
     * Where a source's rows live when the section is not named after the class.
     *
     * <p>The parse-failure codes were found in the resource bundle first and the section is named after it;
     * the enum that declares the same six was found later. Both are read, which makes the section a genuine
     * cross-check — bundle keys, enum constants and ledger rows must agree.
     */
    private static final Map<String, String> SECTION_OF =
            Map.of("CommonParserErrorTypesEnum", "vk_parser_common_error_messages");
}

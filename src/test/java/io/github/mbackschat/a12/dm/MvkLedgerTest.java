package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
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

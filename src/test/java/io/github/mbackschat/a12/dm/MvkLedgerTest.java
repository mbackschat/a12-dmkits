package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Locale;
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
 * <p><b>Five sources</b>, and the count is the point: three internal-tier enums (loaded reflectively from the
 * test classpath only — N8: tests may read internal tier), the parser's own MESSAGE BUNDLE, and the
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
     * The FOURTH source, and the one the ledger was blind to until 2026-09-12.
     *
     * <p>The three enums above do not contain the codes {@code checkConsistency} actually returns for a
     * stored condition that does not parse: those live in the parser's own message bundle, and all six of its
     * keys were missing from the ledger while this test reported the ledger complete. Four of them are
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
}

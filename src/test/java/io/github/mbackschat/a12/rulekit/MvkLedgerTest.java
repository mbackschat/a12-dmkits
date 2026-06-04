package io.github.mbackschat.a12.rulekit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.opentest4j.TestAbortedException;

/**
 * Instrument 2 of the constraint-mining discipline: {@code docs/MVK-LEDGER.md} must classify
 * <em>exactly</em> the {@code MVK_*} codes the kernel can report — no missing codes (a kernel
 * upgrade adding diagnostics fails here, proactively) and no stale ones. Four sources: three
 * internal-tier enums (loaded reflectively from the test classpath only — N8: tests may read
 * internal tier) and the {@code MM_Common} metamodel's rules (the deep source — structural laws
 * <em>are</em> metamodel rules; checked opt-in against the sibling {@code a12-kernel-mm} clone).
 */
class MvkLedgerTest {

    private static final String[] ENUM_CLASSES = {
            "com.mgmtp.a12.kernel.core.parser.internal.error.ParserErrorTypesEnum",
            "com.mgmtp.a12.kernel.core.calc.internal.util.CalculationErrorType",
            "com.mgmtp.a12.kernel.core.service.internal.errortext.FehlertextErrorType",
    };

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

package io.github.mbackschat.a12.rulekit;

import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * <em>exactly</em> the {@code MVK_*} codes the kernel declares — no missing codes (a kernel upgrade
 * adding diagnostics fails here, proactively) and no stale ones. The enums are internal-tier,
 * loaded reflectively from the test classpath only (N8: tests may read internal tier).
 */
class MvkLedgerTest {

    private static final String[] ENUM_CLASSES = {
            "com.mgmtp.a12.kernel.core.parser.internal.error.ParserErrorTypesEnum",
            "com.mgmtp.a12.kernel.core.calc.internal.util.CalculationErrorType",
    };

    /** Codes observed from checkConsistency but not declared in the enums above (ledger's third section). */
    private static final Set<String> OBSERVED_ONLY =
            Set.of("MVK_ERROR_MESSAGE_MISSING", "MVK_ERROR_MESSAGE_FOR_LANGUAGE_MISSING");

    @Test
    void ledgerMatchesTheKernelEnumsExactly() throws Exception {
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

        Set<String> ledgerCodes = new LinkedHashSet<>();
        Pattern row = Pattern.compile("^\\| `(MVK_[A-Z_0-9]+)` \\|");
        for (String line : Files.readAllLines(Path.of("docs/MVK-LEDGER.md"))) {
            Matcher m = row.matcher(line);
            if (m.find()) {
                ledgerCodes.add(m.group(1));
            }
        }

        Set<String> missing = new LinkedHashSet<>(kernelCodes);
        missing.removeAll(ledgerCodes);
        assertTrue(missing.isEmpty(),
                () -> "kernel codes missing from docs/MVK-LEDGER.md (classify them — new diagnostics?): " + missing);

        Set<String> stale = new LinkedHashSet<>(ledgerCodes);
        stale.removeAll(kernelCodes);
        stale.removeAll(OBSERVED_ONLY);
        assertTrue(stale.isEmpty(),
                () -> "ledger rows not declared by the kernel enums (typo or removed code?): " + stale);
    }
}

package io.github.mbackschat.a12.dm.adapter;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;

/**
 * Instrument 6 of the constraint-mining discipline: {@code docs/RT-SEMANTICS-LEDGER.md} must classify
 * <em>exactly</em> the top-level classes of the kernel's runtime evaluation core — the package
 * {@code com.mgmtp.a12.kernel.core.rt._X_Y.internal.core} of the {@code kernel-core-runtime-X-Y}
 * artifact (on the adapter's test classpath transitively, via {@code kernel-md-runtime-service →
 * kernel-md-document-service}). No unclassified class (a kernel upgrade adding an evaluation class
 * fails here, proactively) and no stale row (a removed class fails too).
 *
 * <p>The package and artifact embed the kernel's major.minor (<code>_30_8</code>), so the scan
 * matches the version segment by pattern — a version bump changes the package name, the scan still
 * finds it, and any class-set difference lands in the ledger queue instead of slipping through.
 */
class RtSemanticsLedgerTest {

    /** A top-level class of the runtime evaluation core, any kernel version: {@code _X_Y/internal/core/Name.class}. */
    private static final Pattern CORE_CLASS = Pattern.compile(
            "com/mgmtp/a12/kernel/core/rt/_[0-9_]+/internal/core/([A-Za-z0-9]+)\\.class");

    /** A ledger table row: {@code | `ClassName` | classification | …}. */
    private static final Pattern LEDGER_ROW = Pattern.compile("^\\| `([A-Za-z0-9]+)` \\|");

    @Test
    void ledgerCoversTheRuntimeEvaluationCoreExactly() throws IOException {
        Set<String> kernelClasses = scanRuntimeCoreClasses();
        assertFalse(kernelClasses.isEmpty(),
                "no runtime evaluation core classes found on the test classpath — is kernel-core-runtime missing?");

        Set<String> ledger = ledgerClasses();
        Set<String> missing = new LinkedHashSet<>(kernelClasses);
        missing.removeAll(ledger);
        Set<String> stale = new LinkedHashSet<>(ledger);
        stale.removeAll(kernelClasses);

        assertTrue(missing.isEmpty() && stale.isEmpty(),
                "RT-SEMANTICS-LEDGER drift — unclassified runtime classes (a kernel change?): " + missing
                        + "; stale ledger rows (class gone): " + stale);
    }

    @Test
    void rehearsal_aKernelAddedClassWouldFail() throws IOException {
        // negative control: simulate the kernel adding an evaluation class — the parity must break
        Set<String> withAddition = new LinkedHashSet<>(scanRuntimeCoreClasses());
        withAddition.add("BrandNewEvaluator");
        assertFalse(ledgerClasses().containsAll(withAddition),
                "the rehearsal class must NOT be covered — otherwise this guardian cannot fail");
    }

    private static Set<String> scanRuntimeCoreClasses() throws IOException {
        Set<String> classes = new LinkedHashSet<>();
        for (String entry : System.getProperty("java.class.path").split(System.getProperty("path.separator"))) {
            if (!entry.endsWith(".jar")) {
                continue;
            }
            try (ZipFile jar = new ZipFile(entry)) {
                var e = jar.entries();
                while (e.hasMoreElements()) {
                    ZipEntry ze = e.nextElement();
                    Matcher m = CORE_CLASS.matcher(ze.getName());
                    if (m.matches()) {
                        classes.add(m.group(1));   // top-level only: inner classes ($) don't match
                    }
                }
            }
        }
        return classes;
    }

    private static Set<String> ledgerClasses() throws IOException {
        Path doc = Path.of("../docs/RT-SEMANTICS-LEDGER.md");
        if (!Files.exists(doc)) {
            doc = Path.of("docs/RT-SEMANTICS-LEDGER.md");   // repo-root working dir
        }
        Set<String> classes = new LinkedHashSet<>();
        for (String line : Files.readAllLines(doc)) {
            Matcher m = LEDGER_ROW.matcher(line);
            if (m.find()) {
                classes.add(m.group(1));
            }
        }
        return classes;
    }
}

package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.KernelCode;
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

    /**
     * The CONFORMANCE vocabulary, closed. A bucket says what rulekit does about a code, and it is the column
     * a guard has to read to check the ledger against the implementation — so it must be a small set of
     * atoms, not prose. It had drifted to 14 spellings for these 6 (separator variants, and qualifiers like
     * "boundary" or "where known" that belong in the law text beside them).
     *
     * <p>Ordered by distance from the kernel, which is also the order a combination must be written in: what
     * we make unrepresentable, then what we refuse, then what we merely warn about, then what we preserve
     * for the kernel to judge, then what we leave to it entirely, then what is not a rule about conditions
     * at all.
     */
    private static final List<String> BUCKET_ATOMS = List.of(
            "erased", "pre-checked", "advisory", "renderer-preserved", "kernel-delegated", "n/a-structural");

    /**
     * Every bucket is atoms from {@link #BUCKET_ATOMS} joined by {@code " + "} in that order. A combination
     * is legitimate — a code can be erased on one carrier and pre-checked on another — but its SPELLING must
     * not be, or nothing downstream can key on it.
     */
    @Test
    void everyBucketIsAtomsFromTheClosedVocabularyInCanonicalOrder() throws IOException {
        Map<String, String> offenders = new LinkedHashMap<>();
        for (Map.Entry<String, String> row : ledgerBuckets().entrySet()) {
            List<String> atoms = List.of(row.getValue().split(" \\+ ", -1));
            if (!BUCKET_ATOMS.containsAll(atoms)) {
                offenders.put(row.getKey(), row.getValue() + "  (not in the closed vocabulary)");
            } else if (!isCanonicallyOrdered(atoms)) {
                offenders.put(row.getKey(), row.getValue() + "  (atoms out of order)");
            } else if (atoms.size() != Set.copyOf(atoms).size()) {
                offenders.put(row.getKey(), row.getValue() + "  (repeated atom)");
            }
        }
        assertEquals(Map.of(), offenders, () -> "bucket(s) outside the closed vocabulary " + BUCKET_ATOMS
                + " joined by \" + \" in that order. A qualifier belongs in the law text beside the bucket,"
                + " not in the bucket:\n  " + offenders);
    }

    /**
     * THE ANTI-DRIFT LOCK, and the reason the vocabulary above had to be closed first. The ledger's
     * conformance column and {@link KernelCode}'s buckets are one fact in two places, so they are asserted
     * equal in BOTH directions: a row whose bucket changes without the enum, or an enum constant whose
     * bucket changes without the row, fails here.
     *
     * <p>This is what a bucket saying {@code kernel-delegated} about a code the builder refuses on could not
     * hit before — the ledger was prose no guard read against the implementation.
     */
    @Test
    void theLedgersBucketsAndTheEnumsBucketsAreTheSameFact() throws IOException {
        Map<String, String> fromLedger = ledgerBuckets();
        Map<String, String> fromEnum = new LinkedHashMap<>();
        for (KernelCode code : KernelCode.values()) {
            fromEnum.put(code.name(), BUCKET_ATOMS.stream()
                    .filter(atom -> code.buckets().contains(atomBucket(atom)))
                    .collect(java.util.stream.Collectors.joining(" + ")));
        }
        assertEquals(fromLedger, fromEnum, "docs/MVK-LEDGER.md and KernelCode disagree. They are one fact:"
                + " change the row and the constant together, or the ledger stops describing the code.");
    }

    private static KernelCode.Bucket atomBucket(String atom) {
        return switch (atom) {
            case "erased" -> KernelCode.Bucket.ERASED;
            case "pre-checked" -> KernelCode.Bucket.PRE_CHECKED;
            case "advisory" -> KernelCode.Bucket.ADVISORY;
            case "renderer-preserved" -> KernelCode.Bucket.RENDERER_PRESERVED;
            case "kernel-delegated" -> KernelCode.Bucket.KERNEL_DELEGATED;
            case "n/a-structural" -> KernelCode.Bucket.NA_STRUCTURAL;
            default -> throw new IllegalArgumentException("unmapped bucket atom: " + atom);
        };
    }

    private static boolean isCanonicallyOrdered(List<String> atoms) {
        return atoms.stream().map(BUCKET_ATOMS::indexOf).sorted().toList()
                .equals(atoms.stream().map(BUCKET_ATOMS::indexOf).toList());
    }

    /** The guard's own liveness: it must actually be reading rows, or every assertion above is vacuous. */
    @Test
    void theLedgerRowScanFindsTheEstate() throws IOException {
        int rows = ledgerBuckets().size();
        assertTrue(rows >= 250,
                () -> "only " + rows + " ledger rows parsed — the row pattern has stopped "
                        + "matching the table, which would make the vocabulary assertion vacuous");
    }

    /** Each ledger row's code and its bucket column, in file order. */
    static Map<String, String> ledgerBuckets() throws IOException {
        Map<String, String> buckets = new LinkedHashMap<>();
        Pattern row = Pattern.compile("^\\| `(MVK_[A-Z_0-9]+)` \\| ([^|]+?) \\|");
        for (String line : Files.readAllLines(Path.of("docs/MVK-LEDGER.md"))) {
            Matcher m = row.matcher(line);
            if (m.find()) {
                buckets.put(m.group(1), m.group(2).trim());
            }
        }
        return buckets;
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

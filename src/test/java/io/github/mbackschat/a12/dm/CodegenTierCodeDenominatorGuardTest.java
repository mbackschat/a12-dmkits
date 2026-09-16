package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * The FIFTH source of A12 error codes, which MVK-LEDGER's four do not contain and nothing kept.
 *
 * <p><b>Why this exists.</b> {@code NoMetaModelCheckErrorType} (kernel-core-codegen) declares MVK codes that
 * fire inside the validation code generator, and {@code checkConsistency} re-runs that generator — so they
 * are reachable from a stored model rather than internal to a build tool. None of them appears among the
 * ledger's rows, so every "the denominator is classified" statement made from the ledger alone is short by
 * this whole tier. The ledger says as much and calls it an <i>unmeasured denominator</i>.
 *
 * <p><b>Why it had no keeper, and why it can have one now.</b> The recorded obstacle is that
 * {@code MvkLedgerTest} reaches its enums reflectively off the kernel classpath while this one lives in
 * {@code kernel-core-codegen}, which is not on it — so pinning it "would mean taking a build dependency on
 * the codegen tool". It needs no such dependency: the constant names are read from the consult-only sibling
 * checkout as SOURCE, which is this project's primary kernel oracle by standing rule. A count that had to be
 * re-derived by hand was incremented wrongly three times (the ledger records all three corrections), which is
 * the cost this removes.
 *
 * <p><b>Skipped rather than failed when the sibling is absent</b>, like every other external-corpus sweep
 * here: the checkout is machine-local and never committed. The floor below is what keeps the skip from
 * becoming a silent pass on a checkout that IS present but no longer holds the enum.
 */
class CodegenTierCodeDenominatorGuardTest {

    private static final Checkout CHECKOUT = Checkout.locate();

    private static final String ENUM_SOURCE =
            "kernel-tool/kernel-core-codegen/src/main/java/com/mgmtp/a12/kernel/core/codegen/internal"
                    + "/validation/noMetaModelChecks/NoMetaModelCheckErrorType.java";

    private static final Pattern DECLARED = Pattern.compile("\\bMVK_[A-Z0-9_]+");

    /** A ledger row's code, in the one-row-per-code table shape that file uses throughout. */
    private static final Pattern LEDGER_ROW = Pattern.compile("^\\| `([A-Z0-9_]+)`", Pattern.MULTILINE);

    /**
     * Every constant this tier declares, measured 2026-09-16 against the pinned kernel (30.8.1).
     *
     * <p>Pinned as a SET rather than a count on purpose: a count says the tier moved and a set says which
     * code moved, and the ledger's prose has to name the code. A kernel bump legitimately changes this, and
     * then this guard is the trigger that sends the reader to KERNEL-UPGRADE rather than a surprise.
     */
    private static final Set<String> DECLARED_CODES = new TreeSet<>(List.of(
            "MVK_DUPLICATE_DYNAMIC_ENUM",
            "MVK_ENUM_TABLE_MISSING",
            "MVK_ENUM_VALUE_WITH_INVALID_CHARACTER",
            "MVK_FIELD_NO_PARENT",
            "MVK_FIELD_WITH_CONTAINED_ENTITIES",
            "MVK_INDEX_FIELD_NO_TABLE_ENUM",
            "MVK_INDEX_FIELD_NO_VALUE_VALIDATION",
            "MVK_INVALID_CHAR_IN_RESTRICTED_CHARSET",
            "MVK_INVALID_INDEX_FIELD",
            "MVK_INVALID_NAME_4_GENERATED_CODE",
            "MVK_INVALID_PACKAGE_NAME_4_GENERATED_CODE",
            "MVK_INVALID_SORT_FIELD",
            "MVK_INVALID_TIME_ZONE",
            "MVK_MAX_VALUES_INCONSISTENT",
            "MVK_MAX_VALUES_INVALID",
            "MVK_NO_FORMAL_ERROR_RESOURCE_BUNDLE",
            "MVK_NO_PROJECT_LANGUAGE_SETTINGS",
            "MVK_PARENT_ENTITY_MISSING",
            "MVK_PATH_INVALID",
            "MVK_PATHS_INCONSISTENT",
            "MVK_SORT_FIELD_NO_VALUE_VALIDATION",
            "MVK_TABLE_ENUM_COLUMN_MISSING",
            "MVK_TABLE_ENUM_DEP_COLUMN_MISSING",
            "MVK_TABLE_ENUM_DIFF_CATEGORIES",
            "MVK_TABLE_ENUM_WITH_MULTILINGUAL_DISPLAY_VALUE",
            "MVK_TABLE_ENUMS_NOT_ALLOWED_FOR_PROGRAMMING_LANGUAGE"));

    /**
     * The subset the portable checker states, derived on both sides and pinned here.
     *
     * <p>This is the figure the ledger's prose quotes, so a family that gains or loses one of these must move
     * that sentence in the same change — which is the whole reason it is asserted rather than counted.
     */
    private static final Set<String> CHECKER_REPORTED = new TreeSet<>(List.of(
            "MVK_ENUM_VALUE_WITH_INVALID_CHARACTER",
            "MVK_INDEX_FIELD_NO_VALUE_VALIDATION",
            "MVK_INVALID_INDEX_FIELD",
            "MVK_INVALID_SORT_FIELD",
            // Arrived 2026-09-16: GROUP_REPETITION_COUNT gained the lower half of the repeatability law it
            // had only held the upper half of, closing a measured false-pass route (KF221, extended).
            "MVK_MAX_VALUES_INVALID",
            "MVK_PATH_INVALID",
            "MVK_PATHS_INCONSISTENT",
            "MVK_SORT_FIELD_NO_VALUE_VALIDATION"));

    /** Well below the live 26: it answers "did the scan read the enum", not "how big is the enum". */
    private static final int FLOOR = 15;

    @Test
    void theCodegenTierDeclaresTheCodesTheLedgerSaysItDoes() throws IOException {
        Set<String> declared = declaredCodes();
        assertTrue(declared.size() >= FLOOR,
                "read only " + declared.size() + " MVK constants from " + ENUM_SOURCE + ", so the enum's shape "
                        + "changed and this guard would be comparing almost nothing");
        assertEquals(DECLARED_CODES, declared,
                "the codegen tier's declared codes have moved. This is a KERNEL-UPGRADE trigger: MVK-LEDGER's "
                        + "scope-boundary paragraph states this tier's size and which of it is measured, and "
                        + "that prose must move with the set");
    }

    @Test
    void thePortableCheckerCoversExactlyTheCodegenTierCodesTheLedgerClaims() throws IOException {
        Set<String> declared = declaredCodes();
        Set<String> reported = new TreeSet<>(declared);
        reported.retainAll(CheckerReportedCodes.all());
        assertEquals(CHECKER_REPORTED, reported,
                "the portable checker's reach into the codegen tier has changed. A code ARRIVING is a family "
                        + "that gained reach and MVK-LEDGER's scope-boundary paragraph must say so; a code "
                        + "LEAVING is a family that lost it, which is a regression");
    }

    @Test
    void noneOfTheCodegenTierIsAlreadyALedgerRow() throws IOException {
        Set<String> declared = declaredCodes();
        Set<String> ledger = new TreeSet<>();
        Matcher matcher = LEDGER_ROW.matcher(Files.readString(CHECKOUT.requireFile("docs/MVK-LEDGER.md")));
        while (matcher.find()) {
            ledger.add(matcher.group(1));
        }
        assertTrue(ledger.size() >= 200,
                "read only " + ledger.size() + " code rows from MVK-LEDGER.md, so its table shape changed and "
                        + "the disjointness below would hold vacuously");
        Set<String> both = new TreeSet<>(declared);
        both.retainAll(ledger);
        assertEquals(Set.of(), both,
                "a codegen-tier code now has a ledger row of its own. That is good — but it stops being a "
                        + "SEPARATE denominator at that moment, so the scope-boundary paragraph's arithmetic "
                        + "and this guard's premise both have to be re-derived rather than left to agree by "
                        + "accident: " + both);
    }

    /** The constants the sibling checkout declares, or a skipped test when it is not on this machine. */
    private static Set<String> declaredCodes() throws IOException {
        Path source = kernelCheckout()
                .map(root -> root.resolve(ENUM_SOURCE))
                .filter(Files::isRegularFile)
                .orElse(null);
        Assumptions.assumeTrue(source != null,
                "the consult-only a12-kernel checkout is not on this machine, so the codegen tier cannot be "
                        + "read; set -Da12.kernel.dir=<path> to point at it");
        Set<String> found = new TreeSet<>();
        Matcher matcher = DECLARED.matcher(Files.readString(source));
        while (matcher.find()) {
            found.add(matcher.group());
        }
        return found;
    }

    /** The sibling kernel checkout — a property first, then the two conventional relative places. */
    private static Optional<Path> kernelCheckout() {
        return List.of(
                        Optional.ofNullable(System.getProperty("a12.kernel.dir")),
                        Optional.of("../a12-kernel"),
                        Optional.of("../../a12-kernel"))
                .stream()
                .flatMap(Optional::stream)
                .map(Path::of)
                .filter(Files::isDirectory)
                .findFirst();
    }
}

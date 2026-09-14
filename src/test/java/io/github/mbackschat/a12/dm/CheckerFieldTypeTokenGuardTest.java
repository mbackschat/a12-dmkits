package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Every {@code fieldType} discriminator the portable checker MATCHES ON is one A12 actually stores.
 *
 * <p><b>The defect this exists to stop, measured 2026-09-14.</b> Four of the checker's type sets spelled the
 * custom field type {@code CustomType}, while A12's stored discriminator is {@code CustomFieldType} — a
 * token no model carries. Nothing could see it: a set that matches nothing simply never matches, so the
 * families reading those sets went silent on every custom declaration, each family's own mirror stayed green
 * against the checker's own output, and no laws grid had put a custom field to the kernel. One of the four
 * was the opposite polarity and therefore the expensive one: {@code ValueInFieldsConstantKind}'s text arm
 * listed the custom type among the declarations it ADMITS, so the dead token turned an admission into an
 * over-rejection, the one direction this project forbids.
 *
 * <p><b>Why a scan rather than a shared constant.</b> A shared constant only helps the sites that reach for
 * it; the ones that bite are the literal sets a new family writes out, and there were seven such sets when
 * this was written. The scan needs no cooperation from a new family at all — it reads the sets as source
 * text, which is the only denominator that cannot go stale.
 *
 * <p><b>Both sides are derived.</b> The legal spellings come from {@code FieldTypeBinding.kt}'s own
 * {@code DECLARED_FIELD_TYPES}, the table that mirrors A12's deserializer switch, so a kernel upgrade that
 * adds a field type widens this guard by editing the checker rather than this test. The scanned side is every
 * checker source outside the {@code syntax} package — excluded because its subject is the CONDITION
 * vocabulary, where {@code ValidType} and {@code InvalidType} are operator ids and name no declaration at
 * all; no file there mentions a field-type discriminator (verified by this test). A family OUTSIDE that
 * package may legitimately name such an id too — one carries {@code "ValidType"} to look its keyword up —
 * so the operator ids themselves are subtracted, read from the vocabulary's own table rather than listed
 * here, and a positive control keeps that subtraction from quietly emptying the scan.
 *
 * <p><b>What it proves, and what it does not.</b> It proves that a token a family matches on CAN match a
 * stored model — never that matching it is the right law. A discriminator listed in the wrong set passes
 * here, which is the laws grid's job.
 */
class CheckerFieldTypeTokenGuardTest {

    private static final Path CHECKER = Path.of(
            "consistency/src/commonMain/kotlin/io/github/mbackschat/a12/dm/consistency");

    private static final Path BINDER = CHECKER.resolve("FieldTypeBinding.kt");

    /** The condition vocabulary, whose operator ids look like type names and are not declarations. */
    private static final String SYNTAX_PACKAGE = "syntax";

    /** A capitalized identifier ending in {@code Type}, which is the shape of every stored discriminator. */
    private static final Pattern TYPE_LITERAL = Pattern.compile("\"([A-Z][A-Za-z]*Type)\"");

    private static final Pattern DECLARED_ENTRY = Pattern.compile("\"([A-Za-z]+)\" to ");

    /** The condition vocabulary's own table, the only authority on which capitalized names are operator ids. */
    private static final Path VOCABULARY = CHECKER.resolve(SYNTAX_PACKAGE).resolve("ConditionKeywords.kt");

    /** An {@code "Id" to "Keyword"} entry of that table; the left-hand side is the catalog operator id. */
    private static final Pattern OPERATOR_ID = Pattern.compile("\"([A-Z][A-Za-z]*)\" to \"");

    @Test
    void everyFieldTypeTokenTheCheckerMatchesIsOneA12Stores() {
        Set<String> declared = declaredFieldTypes();
        Set<String> operatorIds = operatorIds();
        Map<String, Set<String>> undeclared = new TreeMap<>();
        for (Path source : checkerSources()) {
            Matcher m = TYPE_LITERAL.matcher(read(source));
            while (m.find()) {
                if (!declared.contains(m.group(1)) && !operatorIds.contains(m.group(1))) {
                    undeclared.computeIfAbsent(CHECKER.relativize(source).toString(), k -> new TreeSet<>())
                            .add(m.group(1));
                }
            }
        }
        assertEquals(Map.of(), undeclared,
                "these files match a `fieldType` discriminator A12 never stores, so the match is dead code "
                        + "and the law behind it is unenforced; the stored spellings are "
                        + declared + " (FieldTypeBinding.kt's DECLARED_FIELD_TYPES)");
    }

    /**
     * The positive control for both halves: a parse that silently returned nothing would pass the scan
     * vacuously, and a scan that matched nothing would pass whatever the parse returned.
     */
    @Test
    void bothDenominatorsAreNonEmpty() {
        Set<String> declared = declaredFieldTypes();
        assertTrue(declared.size() >= 13 && declared.contains("StringType")
                        && declared.contains("CustomFieldType"),
                "DECLARED_FIELD_TYPES no longer parses into the discriminator set this guard reads: " + declared);
        long literals = checkerSources().stream()
                .flatMap(source -> TYPE_LITERAL.matcher(read(source)).results())
                .count();
        assertTrue(literals > 50, "the scan found only " + literals + " type literals, so it is not reading "
                + "the checker's type sets any more");
        Set<String> operatorIds = operatorIds();
        assertTrue(operatorIds.size() > 50 && operatorIds.contains("ValidType")
                        && !operatorIds.contains("StringType"),
                "the operator-id subtraction no longer reads the vocabulary table, so it either hides every "
                        + "dead token or none: " + operatorIds.size() + " ids parsed");
    }

    /**
     * The exclusion is self-checking: the {@code syntax} package is skipped because its capitalized
     * {@code …Type} literals are operator ids, and that holds only while it names no declaration at all.
     */
    @Test
    void theExcludedSyntaxPackageNamesNoDeclaration() {
        Set<String> declared = declaredFieldTypes();
        Map<String, Set<String>> named = new TreeMap<>();
        for (Path source : syntaxSources()) {
            Matcher m = TYPE_LITERAL.matcher(read(source));
            while (m.find()) {
                if (declared.contains(m.group(1))) {
                    named.computeIfAbsent(CHECKER.relativize(source).toString(), k -> new TreeSet<>())
                            .add(m.group(1));
                }
            }
        }
        assertEquals(Map.of(), named,
                "the syntax package now matches a field-type discriminator, so skipping it hides real sets");
    }

    private static Set<String> declaredFieldTypes() {
        String binder = read(BINDER);
        int from = binder.indexOf("DECLARED_FIELD_TYPES = mapOf(");
        assertTrue(from >= 0, "FieldTypeBinding.kt no longer declares DECLARED_FIELD_TYPES");
        String block = binder.substring(from, binder.indexOf("\n)", from));
        Set<String> declared = new TreeSet<>();
        Matcher m = DECLARED_ENTRY.matcher(block);
        while (m.find()) {
            declared.add(m.group(1));
        }
        return declared;
    }

    /** Every catalog operator id the condition vocabulary spells, whatever language column it appears in. */
    private static Set<String> operatorIds() {
        Set<String> ids = new TreeSet<>();
        Matcher m = OPERATOR_ID.matcher(read(VOCABULARY));
        while (m.find()) {
            ids.add(m.group(1));
        }
        return ids;
    }

    private static List<Path> checkerSources() {
        return kotlinSources(false);
    }

    private static List<Path> syntaxSources() {
        return kotlinSources(true);
    }

    private static List<Path> kotlinSources(boolean inSyntaxPackage) {
        try (Stream<Path> tree = Files.walk(CHECKER)) {
            return tree.filter(p -> p.getFileName().toString().endsWith(".kt"))
                    .filter(p -> CHECKER.relativize(p).startsWith(SYNTAX_PACKAGE) == inSyntaxPackage)
                    .sorted()
                    .toList();
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

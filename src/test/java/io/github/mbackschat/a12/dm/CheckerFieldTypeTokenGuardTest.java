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
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * The portable checker spells {@code fieldType} discriminators only in its {@code FieldType} enum.
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
 * <p><b>Why the enum AND a scan.</b> The enum's readers return the type, so comparing one to a literal no
 * longer compiles. What the type system cannot see is a family that reads the raw {@code type} member and
 * writes its own set, which is how all four dead tokens were written. So outside the enum, any capitalized
 * {@code …Type} literal is refused, legal or not: a misspelled one is dead code, and a correct one is the
 * second copy the misspelled ones used to be. Comments are exempt, since prose names types as a model does.
 *
 * <p><b>Both sides are derived.</b> The vocabulary is read off the enum, the case list of A12's deserializer
 * switch, so a kernel upgrade that adds a field type widens this guard by editing the checker rather than
 * this test. The scanned side is every checker source outside the {@code syntax} package — excluded because
 * its subject is the CONDITION
 * vocabulary, where {@code ValidType} and {@code InvalidType} are operator ids and name no declaration at
 * all; no file there mentions a field-type discriminator (verified by this test). A family OUTSIDE that
 * package may legitimately name such an id too — one carries {@code "ValidType"} to look its keyword up —
 * so the operator ids themselves are subtracted, read from the vocabulary's own table rather than listed
 * here, and a positive control keeps that subtraction from quietly emptying the scan.
 *
 * <p><b>What it proves, and what it does not.</b> It proves that every type a family matches on is one A12
 * stores — never that matching it is the right law. A type listed in the wrong set passes here, which is the
 * laws grid's job.
 */
class CheckerFieldTypeTokenGuardTest {

    private static final Path CHECKER = Path.of(
            "consistency/src/commonMain/kotlin/io/github/mbackschat/a12/dm/consistency");

    private static final Path OWNER = CHECKER.resolve("FieldType.kt");

    /** The condition vocabulary, whose operator ids look like type names and are not declarations. */
    private static final String SYNTAX_PACKAGE = "syntax";

    /** A capitalized identifier ending in {@code Type}, which is the shape of every stored discriminator. */
    private static final Pattern TYPE_LITERAL = Pattern.compile("\"([A-Z][A-Za-z]*Type)\"");

    /** An enum entry, {@code NAME("Wire"),}, whose argument is A12's stored spelling. */
    private static final Pattern ENTRY = Pattern.compile("(?m)^    [A-Z_]+\\(\"([A-Za-z]+)\"\\),$");

    /** The condition vocabulary's own table, the only authority on which capitalized names are operator ids. */
    private static final Path VOCABULARY = CHECKER.resolve(SYNTAX_PACKAGE).resolve("ConditionKeywords.kt");

    /** An {@code "Id" to "Keyword"} entry of that table; the left-hand side is the catalog operator id. */
    private static final Pattern OPERATOR_ID = Pattern.compile("\"([A-Z][A-Za-z]*)\" to \"");

    @Test
    void noFieldTypeIsSpelledOutsideTheEnum() {
        Set<String> declared = declaredFieldTypes();
        Set<String> operatorIds = operatorIds();
        Map<String, Set<String>> spelled = new TreeMap<>();
        for (Path source : checkerSources()) {
            if (source.equals(OWNER)) {
                continue;
            }
            Matcher m = TYPE_LITERAL.matcher(withoutComments(read(source)));
            while (m.find()) {
                if (!operatorIds.contains(m.group(1))) {
                    spelled.computeIfAbsent(CHECKER.relativize(source).toString(), k -> new TreeSet<>())
                            .add(m.group(1));
                }
            }
        }
        assertEquals(Map.of(), spelled,
                "compare against FieldType instead: read a declaration through declaredFieldType() or "
                        + "JsonObject.namedFieldType(), and a type's body through FieldType.bodyIn. A12 stores "
                        + "exactly " + declared + ", so a spelling outside that set is also dead code");
    }

    /**
     * The positive control for both halves: a parse that silently returned nothing would pass the scan
     * vacuously, and a scan that matched nothing would pass whatever the parse returned.
     */
    @Test
    void bothDenominatorsAreNonEmpty() {
        Set<String> declared = declaredFieldTypes();
        // Twelve since kernel 31.1.1 stopped reading `UnspecifiedType` (KERNEL-FINDINGS KF654).
        assertTrue(declared.size() == 12 && declared.contains("StringType") && declared.contains("CustomFieldType"),
                "FieldType no longer parses into the twelve discriminators A12's switch has: " + declared);
        Set<String> seen = TYPE_LITERAL.matcher(withoutComments(read(OWNER))).results()
                .map(m -> m.group(1)).collect(Collectors.toSet());
        assertEquals(declared, seen, "the scan must see every literal of the enum itself, or it would pass on any "
                + "file");
        assertTrue(!TYPE_LITERAL.matcher(withoutComments("// \"NumberType\"\n/* \"DateType\" */")).find(),
                "comments must stay exempt");
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
        Set<String> declared = new TreeSet<>();
        Matcher m = ENTRY.matcher(read(OWNER));
        while (m.find()) {
            declared.add(m.group(1));
        }
        return declared;
    }

    /** Comments blanked, so prose naming a type is not read as a spelling. */
    private static String withoutComments(String text) {
        return Pattern.compile("/\\*.*?\\*/|//[^\\n]*", Pattern.DOTALL).matcher(text).replaceAll(" ");
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

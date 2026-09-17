package io.github.mbackschat.a12.dm.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

/**
 * Keeps the checker's two reviewed Kotlin ABI goldens MEMBER-LEVEL, in both carriers, which nothing did.
 *
 * <p>{@code :consistency:checkLegacyAbi} byte-compares each golden against the dump, so it catches any change
 * to what is there — and says nothing about what a dump is capable of omitting. IRG3 records the concrete
 * accident: a KLIB dump that collapsed to bare type headers while its JVM twin kept every member, which both
 * goldens then recorded faithfully. TESTING-SPEC advertises these goldens as pinning "the MEMBERS of the types
 * two published Java APIs expose"; the only control for that claim was a planted member deletion run by hand
 * once, on 2026-09-17. This is the enforcement locus that claim had been missing.
 *
 * <p><b>The invariant is cross-carrier member PRESENCE, deliberately not member-name equality.</b> Comparing
 * names would need a model of how Kotlin renders one declaration into two formats — data-class conveniences
 * ({@code component1}, {@code copy$default}, {@code equals}) exist only in the JVM dump, and a property is one
 * {@code val} line in KLIB against a {@code getX} method on the JVM. That is a second ABI model, which the
 * sibling {@code InterpreterAbiGoldenDepthTest} says in as many words it refuses to build, and it would rot on
 * every compiler release. Presence needs no such model and refuses the one failure actually observed.
 */
class CheckerAbiGoldenDepthTest {

    private static final Path JVM_GOLDEN = Path.of("consistency/api/consistency.api");

    private static final Path KLIB_GOLDEN = Path.of("consistency/api/consistency.klib.api");

    /**
     * A floor, answering "did the comparison happen at all" and never "how big is the surface".
     *
     * Deliberately far below the live intersection (~150 types at 2026-09-17) so corpus growth never edits it.
     * Without it, a parser that silently returned nothing would satisfy every assertion below by vacuity —
     * which is the failure mode of every guard built on a hand-rolled parse.
     */
    private static final int MINIMUM_SHARED_TYPES = 40;

    /** The types TESTING-SPEC names as the reason these goldens exist: what the published Java façades expose. */
    private static final Set<String> FACADE_TYPES = Set.of(
            "io.github.mbackschat.a12.dm.consistency.ModelSource",
            "io.github.mbackschat.a12.dm.consistency.ConsistencyReport",
            "io.github.mbackschat.a12.dm.consistency.CheckOutcome");

    @Test
    void everyTypeWithJvmMembersAlsoCarriesMembersInTheKlibGolden() throws IOException {
        var jvm = jvmMemberCounts(Files.readString(repoFile(JVM_GOLDEN)));
        var klib = klibMemberCounts(Files.readString(repoFile(KLIB_GOLDEN)));

        var shared = new TreeSet<>(jvm.keySet());
        shared.retainAll(klib.keySet());
        assertTrue(shared.size() >= MINIMUM_SHARED_TYPES,
                "only " + shared.size() + " types were matched across the two goldens, below the floor of "
                        + MINIMUM_SHARED_TYPES + " — the parse, not the goldens, is what to suspect first");

        var collapsed = new TreeSet<String>();
        for (String type : shared) {
            if (jvm.get(type) > 0 && klib.get(type) == 0) {
                collapsed.add(type);
            }
        }
        assertEquals(Set.of(), collapsed,
                "these types carry members in the JVM golden and none in the KLIB one, which is exactly the "
                        + "shape IRG3 records — a KLIB dump that lost its member depth while the JVM twin kept "
                        + "it, with checkLegacyAbi green because both goldens agree with their own dump");
    }

    /**
     * The claim TESTING-SPEC makes verbatim, on the three types it names, in both carriers.
     *
     * The general check above is a relation between the goldens and so is satisfied if BOTH collapse; this one
     * is absolute, and it names the exact member that paragraph uses as its example.
     */
    @Test
    void theFacadeTypesCarryTheirMembersInBothCarriers() throws IOException {
        var jvm = jvmMemberCounts(Files.readString(repoFile(JVM_GOLDEN)));
        var klib = klibMemberCounts(Files.readString(repoFile(KLIB_GOLDEN)));

        for (String type : new TreeSet<>(FACADE_TYPES)) {
            assertTrue(jvm.getOrDefault(type, 0) > 0, type + " has no members in the JVM golden");
            assertTrue(klib.getOrDefault(type, 0) > 0, type + " has no members in the KLIB golden");
        }

        // The exact member TESTING-SPEC uses as its worked example of what a by-reference signature alone
        // would not pin, asserted from each carrier's own PARSED member set rather than by substring: the
        // whole-file text contains "findings" on the accessor line too, so a dump that lost the property and
        // kept its getter would satisfy a `contains` and is precisely the collapse worth refusing.
        String report = "io.github.mbackschat.a12.dm.consistency.ConsistencyReport";
        assertTrue(jvmMemberNames(Files.readString(repoFile(JVM_GOLDEN)), report).contains("getFindings"),
                "the JVM golden's ConsistencyReport block must declare getFindings");
        assertTrue(klibMemberNames(Files.readString(repoFile(KLIB_GOLDEN)), report).contains("findings"),
                "the KLIB golden's ConsistencyReport block must declare the findings property");
    }

    /**
     * The parsers, on text written here, so a green run above cannot mean "found nothing".
     *
     * Both cases include a member-LESS declaration beside a member-bearing one, because a parser that counted
     * every line would report both as non-empty and the cross-carrier check would then never fire.
     */
    @Test
    void theParsersCountMembersRatherThanLines() {
        String jvm = """
                public abstract interface class io/example/Marker {
                }

                public final class io/example/Holder$Inner : io/example/Marker {
                \tpublic fun <init> ()V
                \tpublic final fun getValue ()Ljava/lang/String;
                }
                """;
        assertEquals(Map.of("io.example.Marker", 0, "io.example.Holder.Inner", 2), jvmMemberCounts(jvm));

        String klib = """
                // Klib ABI Dump
                abstract interface io.example/Marker // io.example/Marker|null[0]

                final class io.example/Holder : io.example/Marker { // io.example/Holder|null[0]
                    constructor <init>() // io.example/Holder.<init>|<init>(){}[0]
                    final val value // io.example/Holder.value|{}value[0]

                    final object Empty { // io.example/Holder.Empty|null[0]
                    }
                }
                """;
        var counts = klibMemberCounts(klib);
        assertEquals(0, counts.get("io.example.Marker"), "a declaration with no body carries no members");
        assertTrue(counts.get("io.example.Holder") > 0, "a class with a constructor and a val carries members");
        assertEquals(0, counts.get("io.example.Holder.Empty"), "nesting is tracked, and an empty object is empty");
        assertFalse(counts.containsKey("Klib ABI Dump"), "the dump's header comments are not declarations");
    }

    /** The member names declared directly inside one JVM type's block, by their bare identifier. */
    private static Set<String> jvmMemberNames(String dump, String type) {
        var names = new TreeSet<String>();
        boolean inside = false;
        for (String line : dump.lines().toList()) {
            if (line.endsWith("{")) {
                inside = jvmTypeName(line).equals(type);
            } else if (line.equals("}")) {
                inside = false;
            } else if (inside && line.startsWith("\t")) {
                var tokens = line.strip().split("\\s+");
                for (int i = 0; i < tokens.length - 1; i++) {
                    if (tokens[i].equals("fun") || tokens[i].equals("field")) {
                        names.add(tokens[i + 1]);
                    }
                }
            }
        }
        return names;
    }

    /** The member names declared directly inside one KLIB declaration's block, one nesting level only. */
    private static Set<String> klibMemberNames(String dump, String type) {
        var names = new TreeSet<String>();
        String simple = type.substring(type.lastIndexOf('.') + 1);
        boolean inside = false;
        int ownIndent = 0;
        for (String raw : dump.lines().toList()) {
            String line = raw.indexOf("//") >= 0 ? raw.substring(0, raw.indexOf("//")) : raw;
            String body = line.strip();
            if (body.isEmpty()) {
                continue;
            }
            int indent = line.length() - line.stripLeading().length();
            if (!inside) {
                String declared = klibDeclaredName(body);
                if (declared != null && declared.endsWith(simple) && body.endsWith("{")) {
                    inside = true;
                    ownIndent = indent;
                }
                continue;
            }
            if (indent <= ownIndent) {
                inside = false;
                continue;
            }
            var tokens = body.split("\\s+");
            for (int i = 0; i < tokens.length - 1; i++) {
                if (tokens[i].equals("val") || tokens[i].equals("var")) {
                    names.add(tokens[i + 1]);
                }
            }
        }
        return names;
    }

    /**
     * Member counts per fully qualified type in a JVM ABI dump.
     *
     * The format is flat — a nested class is its own top-level block with a {@code $} in its name — and every
     * member sits on a tab-indented line inside the braces.
     */
    private static Map<String, Integer> jvmMemberCounts(String dump) {
        var counts = new TreeMap<String, Integer>();
        String current = null;
        for (String line : dump.lines().toList()) {
            if (line.startsWith("\t") && current != null) {
                counts.merge(current, 1, Integer::sum);
            } else if (line.endsWith("{")) {
                current = jvmTypeName(line);
                counts.putIfAbsent(current, 0);
            } else if (line.equals("}")) {
                current = null;
            }
        }
        return counts;
    }

    private static String jvmTypeName(String header) {
        for (String token : header.split("\\s+")) {
            if (token.contains("/")) {
                return token.replace('/', '.').replace('$', '.');
            }
        }
        throw new IllegalStateException("no slash-separated type name in: " + header);
    }

    /**
     * Member counts per fully qualified declaration in a KLIB ABI dump.
     *
     * KLIB nests, so the owner is whatever declaration opened at the enclosing indentation. A nested
     * declaration counts as one member of its parent and gets its own entry, which is what lets an empty
     * nested object read as empty while its parent does not.
     */
    private static Map<String, Integer> klibMemberCounts(String dump) {
        var counts = new LinkedHashMap<String, Integer>();
        Deque<String> owners = new ArrayDeque<>();
        Deque<Integer> indents = new ArrayDeque<>();
        for (String raw : dump.lines().toList()) {
            String line = raw.indexOf("//") >= 0 ? raw.substring(0, raw.indexOf("//")) : raw;
            String body = line.strip();
            if (body.isEmpty()) {
                continue;
            }
            int indent = line.length() - line.stripLeading().length();
            while (!indents.isEmpty() && indent <= indents.peek()) {
                owners.pop();
                indents.pop();
            }
            if (body.equals("}")) {
                continue;
            }
            if (!owners.isEmpty()) {
                counts.merge(owners.peek(), 1, Integer::sum);
            }
            String declared = klibDeclaredName(body);
            if (declared == null) {
                continue;
            }
            String qualified = owners.isEmpty() ? declared : owners.peek() + "." + declared;
            counts.putIfAbsent(qualified, 0);
            if (body.endsWith("{")) {
                owners.push(qualified);
                indents.push(indent);
            }
        }
        return counts;
    }

    /**
     * The declared name on a KLIB line, or {@code null} when the line declares no type.
     *
     * A type's own line carries its package with a {@code /} before the simple name at the top level; a nested
     * one carries a bare simple name after its keyword. Members ({@code final val}, {@code constructor},
     * {@code enum entry}) are excluded by keyword, because a member line must not open an owner.
     */
    private static String klibDeclaredName(String body) {
        String withoutBraces = body.endsWith("{") ? body.substring(0, body.length() - 1).strip() : body;
        int keyword = -1;
        var tokens = withoutBraces.split("\\s+");
        for (int i = 0; i < tokens.length; i++) {
            if (Set.of("class", "interface", "object", "annotation").contains(tokens[i])) {
                keyword = i;
                break;
            }
        }
        if (keyword < 0 || keyword + 1 >= tokens.length) {
            return null;
        }
        String name = tokens[keyword + 1];
        int colon = name.indexOf(':');
        if (colon >= 0) {
            name = name.substring(0, colon);
        }
        int angle = name.indexOf('<');
        if (angle >= 0) {
            name = name.substring(0, angle);
        }
        return name.contains("/") ? name.replace('/', '.') : name;
    }

    private static Path repoFile(Path relative) {
        Path here = Path.of("").toAbsolutePath();
        for (Path candidate = here; candidate != null; candidate = candidate.getParent()) {
            Path resolved = candidate.resolve(relative);
            if (Files.isRegularFile(resolved)) {
                return resolved;
            }
        }
        throw new IllegalStateException("no " + relative + " above " + here);
    }
}

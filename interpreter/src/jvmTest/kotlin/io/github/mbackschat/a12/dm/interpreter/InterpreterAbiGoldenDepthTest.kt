package io.github.mbackschat.a12.dm.interpreter

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Locks the *depth* of the two reviewed Kotlin ABI goldens, not their content.
 *
 * Content is locked by `checkLegacyAbi`, which byte-compares each golden against a fresh dump — so a removed
 * function or a changed signature fails the build **only if the golden records members in the first place**.
 * It once did not: a root-package-only `byNames` include reduced `api/interpreter.klib.api` to bare type
 * headers, because the KLIB filter is applied to every declaration while the JVM filter is applied per class
 * with its members riding along. The Kotlin/JS surface therefore had a type-name inventory where the JVM
 * surface had a member-level lock, and nothing said so (IRG3).
 *
 * These tests are the counterweight to that fix, in both directions: the goldens must stay deep enough for
 * `checkLegacyAbi` to see a member-level break, and must stay narrow enough that "make it deeper" cannot be
 * satisfied by widening the filter into the engine packages.
 */
class InterpreterAbiGoldenDepthTest {
    private val jvmGolden = propertyPath("interpreter.abi.golden.jvm")
    private val klibGolden = propertyPath("interpreter.abi.golden.klib")

    @Test
    fun everySharedTypeWithJvmMembersDeclaresMembersInTheKlibGoldenToo() {
        val jvm = jvmTypeBlocks(Files.readAllLines(jvmGolden))
        val klib = klibTypeBlocks(Files.readAllLines(klibGolden))
        val shared = klib.keys.intersect(jvm.keys)

        assertTrue(
            shared.size >= DENOMINATOR_FLOOR,
            "only ${shared.size} types are shared by the two ABI goldens; the scan collapsed rather than " +
                "the surface shrinking below $DENOMINATOR_FLOOR types",
        )
        assertEquals(
            emptySet(),
            klib.keys - jvm.keys,
            "the KLIB golden declares types the JVM golden does not",
        )

        val shallow = shared
            .filter { jvm.getValue(it) > 0 && klib.getValue(it) == 0 }
            .sorted()
        assertTrue(
            shallow.isEmpty(),
            "${shallow.size} type(s) carry members in the JVM golden but none in the KLIB golden, so a " +
                "member-level break on the Kotlin/JS surface would pass checkLegacyAbi silently: $shallow",
        )
    }

    @Test
    fun neitherGoldenDeclaresATypeOutsideTheRootPackage() {
        val leaks = buildList {
            Files.readAllLines(jvmGolden)
                .filter { JVM_SUBPACKAGE_DECLARATION.containsMatchIn(it) }
                .forEach { add("interpreter.api: $it") }
            Files.readAllLines(klibGolden)
                .filter { KLIB_SUBPACKAGE_DECLARATION.containsMatchIn(it) }
                .forEach { add("interpreter.klib.api: $it") }
        }
        assertTrue(
            leaks.isEmpty(),
            "the published contract is the root-package façade, but a golden declares an engine-package " +
                "type — widening the ABI include filter is not a way to make a golden deeper:\n" +
                leaks.joinToString("\n"),
        )
    }

    @Test
    fun theInternalMessageComparisonProjectionIsNotPublished() {
        val jvmLines = Files.readAllLines(jvmGolden)
        val klibLines = Files.readAllLines(klibGolden)
        assertTrue(
            jvmLines.any { "A12PointerFormat" in it } && klibLines.any { "A12PointerFormat" in it },
            "positive control: the scan must see a deliberately published pointer formatter in both goldens",
        )
        val leaks = (jvmLines + klibLines).filter { "MessagePointers" in it || "messagePointerHouseForm" in it }
        assertTrue(
            leaks.isEmpty(),
            "the tool-owned message comparison projection is not a consumer format:\n" + leaks.joinToString("\n"),
        )
    }

    @Test
    fun theBlockScannersRecogniseAMemberlessDeclaration() {
        val jvmShallow = jvmTypeBlocks(
            listOf(
                "public final class io/github/mbackschat/a12/dm/interpreter/Deep {",
                "\tpublic final fun parse (Ljava/lang/String;)V",
                "}",
                "",
                "public final class io/github/mbackschat/a12/dm/interpreter/Shallow {",
                "}",
            ),
        )
        assertEquals(mapOf("Deep" to 1, "Shallow" to 0), jvmShallow)

        val klibShallow = klibTypeBlocks(
            listOf(
                "final object io.github.mbackschat.a12.dm.interpreter/Deep { // …|null[0]",
                "    final fun parse(kotlin/String) // …|parse(kotlin.String){}[0]",
                "}",
                "",
                "final class io.github.mbackschat.a12.dm.interpreter/Shallow // …|null[0]",
            ),
        )
        assertEquals(mapOf("Deep" to 1, "Shallow" to 0), klibShallow)
    }

    /**
     * Top-level JVM type blocks and their member-line counts. Nested types (`Outer$Nested`) are skipped
     * because the KLIB renderer nests them inside their owner, where they already count as its members.
     */
    private fun jvmTypeBlocks(lines: List<String>): Map<String, Int> =
        typeBlocks(lines, JVM_TYPE_HEADER) { it }
            .filterKeys { '$' !in it }

    /** Top-level KLIB type blocks and their member-line counts, comment tails removed so bodies are visible. */
    private fun klibTypeBlocks(lines: List<String>): Map<String, Int> =
        typeBlocks(lines, KLIB_TYPE_HEADER) { KLIB_COMMENT_TAIL.replace(it, "") }

    private fun typeBlocks(
        lines: List<String>,
        header: Regex,
        normalize: (String) -> String,
    ): Map<String, Int> {
        val counts = LinkedHashMap<String, Int>()
        var openType: String? = null
        var members = 0
        for (raw in lines) {
            val line = normalize(raw)
            val current = openType
            if (current == null) {
                val name = header.find(line)?.groupValues?.get(1) ?: continue
                if (line.trimEnd().endsWith("{")) {
                    openType = name
                    members = 0
                } else {
                    counts[name] = 0
                }
            } else if (line == "}") {
                counts[current] = members
                openType = null
            } else if (line.isNotBlank()) {
                members++
            }
        }
        return counts
    }

    private fun propertyPath(name: String): Path =
        Path.of(requireNotNull(System.getProperty(name)) { "missing system property $name" })

    private companion object {
        /** A scan that finds fewer shared types than this has broken, not measured a smaller surface. */
        private const val DENOMINATOR_FLOOR = 50

        /** The published contract's root package, in each dump's own spelling. */
        private const val JVM_PACKAGE = "io/github/mbackschat/a12/dm/interpreter"
        private const val KLIB_PACKAGE = """io\.github\.mbackschat\.a12\.dm\.interpreter"""

        /**
         * A top-level declaration header in either dump: modifier words, then the declared name. Anchoring on
         * that position is what keeps a *supertype* or *signature* reference to an engine type from reading as
         * a declaration of one.
         */
        private val JVM_TYPE_HEADER = Regex("""^public [\w ]*class $JVM_PACKAGE/([^\s:]+)""")
        private val KLIB_TYPE_HEADER = Regex("""^[\w ]+$KLIB_PACKAGE/([^\s:]+)""")
        private val JVM_SUBPACKAGE_DECLARATION = Regex("""^public [\w ]*class $JVM_PACKAGE/\w+/""")
        private val KLIB_SUBPACKAGE_DECLARATION = Regex("""^[\w ]+$KLIB_PACKAGE\.\w[\w.]*/""")

        private val KLIB_COMMENT_TAIL = Regex("""\s*//.*$""")
    }
}

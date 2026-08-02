package io.github.mbackschat.a12.dm.interpreter

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.ServiceLoader
import java.util.jar.JarFile
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.jetbrains.kotlin.abi.tools.api.AbiFilters
import org.jetbrains.kotlin.abi.tools.api.AbiToolsFactory
import org.jetbrains.kotlin.abi.tools.api.v2.AbiToolsV2
import org.jetbrains.kotlin.abi.tools.api.v2.KlibTarget

/**
 * Locks the scope of the reviewed Kotlin ABI goldens without implementing a second Kotlin ABI model.
 *
 * `checkLegacyAbi` compares each reviewed file with the configured, filtered dump. This guard asks the same
 * ABI engine for an unfiltered dump and projects the intended contract from package identity alone. A new
 * declaration kind therefore enters the denominator before this test has a name for it.
 */
class InterpreterAbiGoldenDepthTest {
    private val jvmGolden = propertyPath("interpreter.abi.golden.jvm")
    private val klibGolden = propertyPath("interpreter.abi.golden.klib")
    private val nonContractJvmFacades = propertyPath("interpreter.abi.non-contract-jvm-facades")
    private val compiledJvm = propertyPath("interpreter.abi.compiled.jvm")
    private val compiledKlib = propertyPath("interpreter.abi.compiled.klib")
    private val nonContractNames = requireNotNull(System.getProperty("interpreter.abi.non-contract-names"))
        .split(',')
        .filter(String::isNotEmpty)
        .toSet()
    private val nonContractFacadeNames = requireNotNull(
        System.getProperty("interpreter.abi.non-contract-facade-names"),
    ).split(',').filter(String::isNotEmpty).toSet()
    private val abiTools: AbiToolsV2 by lazy {
        ServiceLoader.load(AbiToolsFactory::class.java)
            .map { it.get().v2 }
            .single()
    }

    @Test
    fun theJvmGoldenIsTheExactRootPackageProjectionOfTheUnfilteredDump() {
        val expected = contractJvmBlocks(unfilteredJvmDump())
        val actual = jvmBlocks(Files.readString(jvmGolden))

        assertEquals(
            expected,
            actual,
            "the JVM golden must equal the whole exact-root-package projection of the ABI engine's own dump",
        )
    }

    @Test
    fun excludedJvmFileFacadesContainOnlyTheirExactlyReviewedMembers() {
        val expectedOwners = nonContractFacadeNames.mapTo(sortedSetOf()) { "$JVM_PACKAGE/$it" }
        val reviewedText = Files.readString(nonContractJvmFacades)
        val reviewed = jvmBlocks(reviewedText)
        val unfiltered = jvmBlocks(unfilteredJvmDump()).filterKeys { it in expectedOwners }.toSortedMap()

        assertEquals(
            expectedOwners,
            reviewed.keys,
            "every owner-wide JVM file-facade exclusion needs one exact reviewed block",
        )
        assertCanonicalJvmBlocks(
            expected = unfiltered,
            actualText = reviewedText,
        )
    }

    @Test
    fun theKlibGoldenIsTheExactRootPackageProjectionOfTheUnfilteredDump() {
        val expected = contractKlibDeclarations(unfilteredKlibDump())
        val actual = klibDeclarations(Files.readString(klibGolden))

        assertEquals(
            expected,
            actual,
            "the KLIB golden must equal the whole exact-root-package projection of the ABI engine's own dump",
        )
    }

    @Test
    fun theProjectionDoesNotNeedToKnowJvmMemberKinds() {
        val dump = """
            public final class $JVM_PACKAGE/Future${'$'}Companion {
                public static synthetic fun make${'$'}default ()V
                public mystery member whose syntax the guard does not know
            }

            public final class $JVM_PACKAGE/eval/EngineType {
            }

            public final class $JVM_PACKAGE/DmInterpreter {
            }
        """.trimIndent()

        assertEquals(
            mapOf(
                "$JVM_PACKAGE/Future\$Companion" to """
                    public final class $JVM_PACKAGE/Future${'$'}Companion {
                        public static synthetic fun make${'$'}default ()V
                        public mystery member whose syntax the guard does not know
                    }
                """.trimIndent(),
            ),
            contractJvmBlocks(dump),
        )
    }

    @Test
    fun theProjectionDoesNotNeedToKnowKlibDeclarationKinds() {
        val dump = """
            future declaration // $KLIB_PACKAGE/Future.answer|opaque-signature[0]
            another future declaration // $KLIB_PACKAGE/Future.answer.child|another-signature[0]
            engine declaration // $KLIB_PACKAGE.eval/EngineType.answer|engine-signature[0]
            excluded declaration // $KLIB_PACKAGE/DmInterpreter.answer|excluded-signature[0]
        """.trimIndent()

        assertEquals(
            listOf(
                "future declaration // $KLIB_PACKAGE/Future.answer|opaque-signature[0]",
                "another future declaration // $KLIB_PACKAGE/Future.answer.child|another-signature[0]",
            ),
            contractKlibDeclarations(dump),
        )
    }

    @Test
    fun theAuxiliaryFacadeComparisonRejectsUnparsedText() {
        val block = "public final class $JVM_PACKAGE/LegacyKt {\n}"
        val expected = mapOf("$JVM_PACKAGE/LegacyKt" to block)

        assertCanonicalJvmBlocks(expected, "$block\n")
        assertFailsWith<AssertionError> {
            assertCanonicalJvmBlocks(expected, "$block\npublic static final fun stale ()V\n")
        }
    }

    private fun unfilteredJvmDump(): String = withExtractedClasses(compiledJvm) { classFiles ->
        buildString {
            abiTools.printJvmDump(this, classFiles, AbiFilters.EMPTY)
        }
    }

    private fun unfilteredKlibDump(): String = buildString {
        abiTools.extractKlibAbi(
            compiledKlib.toFile(),
            KlibTarget.parse("js"),
            AbiFilters.EMPTY,
        ).print(this)
    }

    private fun contractJvmBlocks(dump: String): Map<String, String> =
        jvmBlocks(dump).filterKeys { internalName ->
            val relativeName = internalName.removePrefix("$JVM_PACKAGE/")
            '/' !in relativeName && relativeName.substringBefore('$') !in nonContractNames
        }

    private fun jvmBlocks(dump: String): Map<String, String> {
        val lines = dump.lines()
        val blocks = linkedMapOf<String, String>()
        var index = 0
        while (index < lines.size) {
            val internalName = JVM_TYPE_HEADER.find(lines[index])?.groupValues?.get(1)
            if (internalName == null) {
                index += 1
                continue
            }
            val end = (index + 1 until lines.size).firstOrNull { lines[it] == "}" }
            requireNotNull(end) { "unterminated JVM ABI block for $internalName" }
            val previous = blocks.put(internalName, lines.subList(index, end + 1).joinToString("\n"))
            require(previous == null) { "duplicate JVM ABI block for $internalName" }
            index = end + 1
        }
        return blocks
    }

    private fun contractKlibDeclarations(dump: String): List<String> =
        klibDeclarations(dump).filter { line ->
            val identity = requireNotNull(KLIB_IDENTITY.find(line)).groupValues[1]
            if (!identity.startsWith("$KLIB_PACKAGE/")) return@filter false
            identity.removePrefix("$KLIB_PACKAGE/").substringBefore('.') !in nonContractNames
        }

    private fun klibDeclarations(dump: String): List<String> = dump.lineSequence()
        .map(String::trim)
        .filter(KLIB_IDENTITY::containsMatchIn)
        .toList()

    private fun assertCanonicalJvmBlocks(expected: Map<String, String>, actualText: String) {
        assertEquals(
            expected.toSortedMap().values.joinToString(separator = "\n\n", postfix = "\n"),
            actualText,
            "the auxiliary facade golden must be the complete canonical blocks, with no ignored text",
        )
    }

    private fun <T> withExtractedClasses(jarPath: Path, action: (List<File>) -> T): T {
        assertTrue(Files.isRegularFile(jarPath), "compiled JVM denominator is not an assembled JAR: $jarPath")
        val temporary = Files.createTempDirectory("interpreter-abi-classes-")
        try {
            val classFiles = JarFile(jarPath.toFile()).use { jar ->
                jar.entries().asSequence()
                    .filter { !it.isDirectory && it.name.endsWith(".class") }
                    .map { entry ->
                        val destination = temporary.resolve(entry.name)
                        Files.createDirectories(destination.parent)
                        jar.getInputStream(entry).use { source -> Files.copy(source, destination) }
                        destination.toFile()
                    }
                    .toList()
            }
            assertTrue(classFiles.isNotEmpty(), "assembled JVM JAR contains no class files: ${jarPath.name}")
            return action(classFiles)
        } finally {
            Files.walk(temporary).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }

    private fun propertyPath(name: String): Path =
        Path.of(requireNotNull(System.getProperty(name)) { "missing system property $name" })

    private companion object {
        private const val JVM_PACKAGE = "io/github/mbackschat/a12/dm/interpreter"
        private const val KLIB_PACKAGE = "io.github.mbackschat.a12.dm.interpreter"
        private val JVM_TYPE_HEADER = Regex("""^public .* class ($JVM_PACKAGE/[^\s:]+)""")
        private val KLIB_IDENTITY = Regex("""// ([^|]+)\|""")
    }
}

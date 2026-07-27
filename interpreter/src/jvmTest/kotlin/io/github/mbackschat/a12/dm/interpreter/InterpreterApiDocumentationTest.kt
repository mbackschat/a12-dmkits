package io.github.mbackschat.a12.dm.interpreter

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies the versioned API-documentation payload against shipped declarations and reviewed ABI locks.
 *
 * Gradle runs this class only through `interpreterApiDocumentationTest`, whose declared inputs cover every
 * repository and generated file read here. Keeping parsing in a regular test makes each advertised refusal
 * executable instead of hiding it in an untestable build-script closure.
 */
class InterpreterApiDocumentationTest {
    private val version = requiredProperty("interpreter.api.docs.version")
    private val stageRoot = propertyPath("interpreter.api.docs.stage")
    private val versionPath = "api/v$version"
    private val versionRoot = stageRoot.resolve(versionPath)
    private val archive = propertyPath("interpreter.api.docs.archive")
    private val comparisonArchive = propertyPath("interpreter.api.docs.comparisonArchive")
    private val reviewedTypeScriptAbi = propertyPath("interpreter.api.docs.typescriptAbi")
    private val reviewedJvmAbi = propertyPath("interpreter.api.docs.jvmAbi")
    private val reviewedKlibAbi = propertyPath("interpreter.api.docs.klibAbi")
    private val publicGuide = propertyPath("interpreter.api.docs.guide")

    @Test
    fun archiveContainsOneCompleteVersionedDocumentationTree() {
        val required = setOf(
            "index.html",
            "README.md",
            "INTERPRETER-API-GUIDE.md",
            "llms.txt",
            "kotlin/index.html",
            "typescript/index.html",
        )
        required.forEach { relative ->
            val file = versionRoot.resolve(relative)
            assertTrue(file.isRegularFile(), "missing API documentation file $relative")
            assertTrue(Files.size(file) > 0, "empty API documentation file $relative")
        }

        val entries = ZipFile(archive.toFile()).use { zip ->
            zip.entries().asSequence()
                .filterNot { it.isDirectory }
                .map { it.name }
                .toList()
        }
        assertEquals(entries.distinct(), entries, "API documentation archive contains duplicate entries")
        assertTrue(
            entries.all { it.startsWith("$versionPath/") },
            "API documentation archive contains files outside $versionPath",
        )
        required.forEach { relative ->
            assertTrue("$versionPath/$relative" in entries, "archive misses $relative")
        }
    }

    @Test
    fun typeScriptReferenceContainsExactlyTheReviewedRootExports() {
        val expected = Files.readAllLines(reviewedTypeScriptAbi)
            .filter { it.startsWith("## ") }
            .map { it.removePrefix("## ") }
            .toSet()
        val html = Files.readString(versionRoot.resolve("typescript/index.html"))
        val actual = Regex("""<section id="([^"]+)">""")
            .findAll(html)
            .map { URLDecoder.decode(it.groupValues[1], StandardCharsets.UTF_8) }
            .toList()

        assertEquals(expected, actual.toSet(), "TypeScript reference exports differ from the reviewed ABI")
        assertEquals(expected.size, actual.size, "TypeScript reference repeats a root export")
        assertFalse(
            "private readonly" in html || "@internal" in html,
            "TypeScript reference exposes private implementation members",
        )
    }

    @Test
    fun kotlinReferenceContainsExactlyTheReviewedTypesAndNoBrokenLinks() {
        val expected = jvmTypeNames()
        val entries = dokkaTypeEntries()
        val duplicates = entries.groupingBy { it.name }.eachCount().filterValues { it != 1 }

        assertTrue(duplicates.isEmpty(), "Dokka package inventory repeats public types: $duplicates")
        assertEquals(expected, entries.map { it.name }.toSet(), "Kotlin reference differs from the reviewed JVM ABI")

        val kotlinRoot = versionRoot.resolve("kotlin")
        assertFalse(
            Files.exists(kotlinRoot.resolve("dm-interpreter/[root]")),
            "Kotlin reference exposes the private package-less JS bridge",
        )
        val undocumented = entries.filter { entry ->
            "<p class=\"paragraph\">" !in Files.readString(kotlinRoot.resolve(entry.relativePath))
        }.map(DokkaTypeEntry::name)
        assertTrue(undocumented.isEmpty(), "undocumented public Kotlin declarations: $undocumented")

        val unresolved = textFiles(kotlinRoot).flatMap { file ->
            unresolvedInterpreterLinks(Files.readString(file)).map { target ->
                "${kotlinRoot.relativize(file)} -> $target"
            }
        }
        assertTrue(unresolved.isEmpty(), "Kotlin reference contains unresolved interpreter links: $unresolved")
    }

    @Test
    fun primaryKotlinOperationsCarryMemberDocumentation() {
        val packageList = Files.readAllLines(versionRoot.resolve("kotlin/dm-interpreter/package-list"))
        val failures = PRIMARY_KOTLIN_OPERATIONS.mapNotNull { operation ->
            val marker = "\$dokka.location:$INTERPRETER_PACKAGE/$operation/"
            val locations = packageList
                .filter { it.startsWith(marker) }
                .map { it.substringAfter('\u001F') }
                .distinct()
            when {
                locations.size != 1 -> "$operation has ${locations.size} reference pages"
                "<p class=\"paragraph\">" !in Files.readString(versionRoot.resolve("kotlin/${locations.single()}")) ->
                    "$operation has no member documentation"
                else -> null
            }
        }
        assertTrue(
            failures.isEmpty(),
            "primary Kotlin operations need member documentation:\n${failures.joinToString("\n")}",
        )
    }

    @Test
    fun llmsReferenceContainsTheTestedGuideAndCompletePlatformInventories() {
        val jvm = jvmTypeNames()
        val common = klibTypeNames()
        val dokka = dokkaTypeEntries().map { it.name }.toSet()
        assertTrue(common.all(jvm::contains), "Kotlin/JS public types are not a subset of JVM public types")
        assertEquals(dokka, common + (jvm - common), "llms.txt type partitions do not cover the Dokka inventory")

        val expected = renderLlmReference(
            version = version,
            guide = Files.readString(publicGuide).trimEnd(),
            typeScriptAbi = Files.readString(reviewedTypeScriptAbi).trimEnd(),
            common = common,
            jvmOnly = jvm - common,
        )
        assertEquals(
            expected,
            Files.readString(versionRoot.resolve("llms.txt")),
            "llms.txt differs from the tested guide and reviewed ABI inventories",
        )
    }

    @Test
    fun documentationIsYearIndependentAndArchivePackagingIsReproducible() {
        val yearFooter = Regex("""©\s+\d{4}\s+Copyright""")
        val clockDependent = textFiles(versionRoot.resolve("kotlin"))
            .filter { yearFooter.containsMatchIn(Files.readString(it)) }
            .map { versionRoot.relativize(it).toString() }
        assertTrue(clockDependent.isEmpty(), "Kotlin reference contains clock-derived footers: $clockDependent")
        assertEquals(sha256(archive), sha256(comparisonArchive), "two API documentation archives differ")
    }

    @Test
    fun publicDocumentationContainsNoPrivateOrSensitiveSourceMarkers() {
        val forbidden = mapOf(
            "corporate email/domain" to Regex("""(?i)mgm-tp\.com"""),
            "machine-local Unix path" to Regex("""(?:/Users/|/home/[^/\s]+/)"""),
            "machine-local Windows path" to Regex("""(?i)[A-Z]:\\Users\\""""),
            "machine-local file URL" to Regex("""(?i)file:(?://)?(?:/Users/|/home/|[A-Z]:)"""),
            "private repository URL" to Regex("""(?i)https?://[^\s"<>]*(?:artifactory|nexus|mgmtp)"""),
            "GitHub/npm token" to Regex("""(?i)(?:gh[pousr]|github_pat|npm)_[A-Za-z0-9_]{20,}"""),
            "private key" to Regex("""-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"""),
            "private bridge" to Regex("""\b(?:InterpreterBridge|DmInterpreter)\b"""),
            "private eval package" to Regex("""io\.github\.mbackschat\.a12\.dm\.interpreter\.eval\."""),
            "private load package" to Regex("""io\.github\.mbackschat\.a12\.dm\.interpreter\.load\."""),
            "private model package" to Regex("""io\.github\.mbackschat\.a12\.dm\.interpreter\.model\."""),
            "private workspace package" to Regex("""io\.github\.mbackschat\.a12\.dm\.interpreter\.workspace\."""),
            "JVM constructor implementation" to Regex("""kotlin\.jvm\.internal\.DefaultConstructorMarker"""),
        )
        val violations = textFiles(versionRoot).flatMap { file ->
            val text = Files.readString(file)
            forbidden.filterValues { it.containsMatchIn(text) }.keys.map { marker ->
                "${versionRoot.relativize(file)}: $marker"
            }
        }
        assertTrue(violations.isEmpty(), "public API documentation exposes private data:\n${violations.joinToString("\n")}")
    }

    private fun dokkaTypeEntries(): List<DokkaTypeEntry> {
        val packageList = versionRoot.resolve("kotlin/dm-interpreter/package-list")
        val entry = Regex(
            """^\${'$'}dokka\.location:io\.github\.mbackschat\.a12\.dm\.interpreter/([^/.]+)///PointingToDeclaration/[^\u001F]*\u001F(.+/index\.html)$""",
        )
        return Files.readAllLines(packageList).mapNotNull { line ->
            entry.matchEntire(line)?.let { match ->
                DokkaTypeEntry(match.groupValues[1], match.groupValues[2])
            }
        }
    }

    private fun jvmTypeNames(): Set<String> =
        Regex(
            """(?m)^public .* class io/github/mbackschat/a12/dm/interpreter/([^\s$:]+)(?::|\s|$)""",
        ).findAll(Files.readString(reviewedJvmAbi)).map { it.groupValues[1] }.toSet()

    private fun klibTypeNames(): Set<String> =
        Regex(
            """(?m)^(?:final |abstract |sealed )*(?:enum class|class|fun interface|interface|object) io\.github\.mbackschat\.a12\.dm\.interpreter/([^\s:]+)""",
        ).findAll(Files.readString(reviewedKlibAbi)).map { it.groupValues[1] }.toSet()

    private fun textFiles(root: Path): List<Path> =
        Files.walk(root).use { paths ->
            paths.filter(Path::isRegularFile)
                .filter { it.extension.lowercase() in TEXT_EXTENSIONS }
                .toList()
        }

    private fun unresolvedInterpreterLinks(text: String): List<String> =
        UNRESOLVED_LINK.findAll(text)
            .map { it.groupValues[1] }
            .filter { it.startsWith(INTERPRETER_PACKAGE) }
            .toList()

    private fun sha256(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun renderLlmReference(
        version: String,
        guide: String,
        typeScriptAbi: String,
        common: Set<String>,
        jvmOnly: Set<String>,
    ): String = buildString {
        appendLine("# A12 Interpreter API $version")
        appendLine()
        appendLine("> Public signatures and examples for the version-matched interpreter release.")
        appendLine()
        appendLine("## Tested developer guide")
        appendLine()
        appendLine(guide)
        appendLine()
        appendLine("## TypeScript public ABI")
        appendLine()
        appendLine("```text")
        appendLine(typeScriptAbi)
        appendLine("```")
        appendLine()
        appendLine("## Kotlin common public types")
        appendLine()
        common.sorted().forEach { appendLine("- `$it`") }
        appendLine()
        appendLine("## JVM-only public types")
        appendLine()
        jvmOnly.sorted().forEach { appendLine("- `$it`") }
    }

    private fun propertyPath(name: String): Path = Path.of(requiredProperty(name))

    private fun requiredProperty(name: String): String =
        requireNotNull(System.getProperty(name)) { "missing system property $name" }

    private data class DokkaTypeEntry(
        val name: String,
        val relativePath: String,
    )

    companion object {
        private const val INTERPRETER_PACKAGE = "io.github.mbackschat.a12.dm.interpreter"
        private val UNRESOLVED_LINK = Regex("""data-unresolved-link="([^"]+)"""")
        private val PRIMARY_KOTLIN_OPERATIONS = setOf(
            "Interpreter/compute",
            "Interpreter/supportReport",
            "Interpreter/validateFull",
            "Interpreter/validatePart",
            "ModelMetadata/pointer",
            "ModelMetadata/relevantPointer",
            "ModelWorkspace.Companion/collect",
            "ModelWorkspace.Companion/fromSources",
            "ModelWorkspace/expand",
            "ModelWorkspace/prepare",
            "ModelWorkspace/tryExpand",
            "ModelWorkspace/tryPrepare",
            "PreparedDocumentApi/encodeDocument",
            "PreparedDocumentApi/readDocument",
            "PreparedModel.Companion/loadExpandedJson",
            "PreparedModel.Companion/tryLoadExpandedJson",
            "PreparedModel/createInterpreter",
        )
        private val TEXT_EXTENSIONS = setOf("html", "json", "js", "md", "txt")
    }
}

package io.github.mbackschat.a12.dm.interpreter

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Keeps the public interpreter examples attached to standalone consumers of the packed artifacts.
 *
 * The guide is authored prose, but its code is not: every locked fence is copied from a tagged source
 * region that the external Maven or npm consumer compiles and executes.
 */
class InterpreterDocumentationTest {
    private data class SnippetLock(
        val id: String,
        val document: String,
        val source: String,
        val language: String,
    )

    private val locks = listOf(
        SnippetLock(
            "interpreter-typescript-start",
            "interpreter/README.md",
            "interpreter/consumer-tests/typescript/src/guide-finite.ts",
            "ts",
        ),
        SnippetLock(
            "interpreter-typescript-start",
            "docs/INTERPRETER-API-GUIDE.md",
            "interpreter/consumer-tests/typescript/src/guide-finite.ts",
            "ts",
        ),
        SnippetLock(
            "interpreter-typescript-provider",
            "docs/INTERPRETER-API-GUIDE.md",
            "interpreter/consumer-tests/typescript/src/guide-provider.ts",
            "ts",
        ),
        SnippetLock(
            "interpreter-typescript-customization",
            "docs/INTERPRETER-API-GUIDE.md",
            "interpreter/consumer-tests/typescript/src/guide-customization.ts",
            "ts",
        ),
        SnippetLock(
            "interpreter-kotlin-start",
            "interpreter/README.md",
            "interpreter/consumer-tests/kotlin-jvm/src/main/kotlin/GuideExample.kt",
            "kotlin",
        ),
        SnippetLock(
            "interpreter-kotlin-start",
            "docs/INTERPRETER-API-GUIDE.md",
            "interpreter/consumer-tests/kotlin-jvm/src/main/kotlin/GuideExample.kt",
            "kotlin",
        ),
        SnippetLock(
            "interpreter-java-start",
            "docs/INTERPRETER-API-GUIDE.md",
            "interpreter/consumer-tests/kotlin-jvm/src/main/java/JavaGuideExample.java",
            "java",
        ),
    )

    @Test
    fun documentedExamplesMatchTheirExecutedConsumerSources() {
        locks.forEach { lock ->
            val expected = taggedRegion(repoFile(lock.source), lock.id)
            val actual = documentedFence(repoFile(lock.document), lock.id, lock.language)
            assertEquals(
                normalize(expected),
                normalize(actual),
                "snippet '${lock.id}' in ${lock.document} drifted from ${lock.source}",
            )
        }
    }

    @Test
    fun everyPublicLanguageExampleIsLockedToAConsumerSource() {
        val locksByDocument = locks.groupBy(SnippetLock::document)
        val failures = locksByDocument.flatMap { (document, documentLocks) ->
            val lines = Files.readAllLines(repoFile(document))
            lines.mapIndexedNotNull { index, line ->
                val language = line.removePrefix("```").takeIf { line.startsWith("```") }
                    ?: return@mapIndexedNotNull null
                if (language !in setOf("ts", "kotlin", "java")) return@mapIndexedNotNull null
                val marker = lines.subList(0, index).asReversed().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
                val id = marker.removePrefix("<!-- snippet: ").removeSuffix(" -->")
                if (marker.startsWith("<!-- snippet: ") &&
                    documentLocks.any { it.id == id && it.language == language }
                ) {
                    null
                } else {
                    "$document:${index + 1} ($language)"
                }
            }
        }
        assertTrue(failures.isEmpty(), "unlocked public interpreter examples:\n${failures.joinToString("\n")}")
    }

    @Test
    fun publicReadmeUsesTheRepositoryVersion() {
        val build = Files.readString(repoFile("build.gradle"))
        val version = requireNotNull(Regex("""(?m)^version = '([^']+)'$""").find(build)).groupValues[1]
        val readme = Files.readString(repoFile("interpreter/README.md"))
        assertTrue(
            listOf(
                "Version `$version`",
                "/v$version/a12-interpreter-$version.tgz",
                "dm-interpreter:$version",
            ).all(readme::contains),
            "interpreter/README.md does not consistently use repository version $version",
        )
    }

    @Test
    fun interpreterScriptsDeriveTheRepositoryVersion() {
        val build = Files.readString(repoFile("build.gradle"))
        val version = requireNotNull(Regex("""(?m)^version = '([^']+)'$""").find(build)).groupValues[1]
        val sourceRoots = listOf(
            repoFile("interpreter/typescript/scripts"),
            repoFile("interpreter/consumer-tests/browser/scripts"),
            repoFile("interpreter/consumer-tests/browser/src"),
        )
        val failures = sourceRoots.flatMap { root ->
            Files.walk(root).use { paths ->
                paths.filter(Files::isRegularFile)
                    .filter { it.fileName.toString().substringAfterLast('.', "") in setOf("ts", "mjs") }
                    .filter { Files.readString(it).contains(version) }
                    .map { repoRoot().relativize(it).toString() }
                    .toList()
            }
        }
        assertTrue(
            failures.isEmpty(),
            "interpreter scripts repeat repository version $version instead of deriving it:\n${failures.joinToString("\n")}",
        )
    }

    @Test
    fun relativeLinksInPublicInterpreterDocumentsResolve() {
        val documents = listOf(
            repoFile("interpreter/README.md") to repoRoot(),
            repoFile("docs/INTERPRETER-API-GUIDE.md") to repoFile("docs"),
        )
        val failures = documents.flatMap { (document, linkBase) ->
            markdownLinks(Files.readString(document))
                .filterNot(::isExternalOrAnchored)
                .map { it.substringBefore('#') }
                .filter { it.isNotBlank() }
                .filterNot { linkBase.resolve(it).normalize().exists() }
                .map { "${repoRoot().relativize(document)} -> $it" }
        }
        assertTrue(failures.isEmpty(), "unresolved interpreter documentation links:\n${failures.joinToString("\n")}")
    }

    private fun taggedRegion(source: Path, id: String): String {
        val lines = Files.readAllLines(source)
        val begin = lines.indexOfFirst { it.trim() == "// tag::$id[]" }
        val end = lines.indexOfFirst { it.trim() == "// end::$id[]" }
        assertTrue(begin >= 0 && end > begin, "missing tagged region '$id' in $source")
        return lines.subList(begin + 1, end).joinToString("\n")
    }

    private fun documentedFence(document: Path, id: String, language: String): String {
        val text = Files.readString(document)
        val marker = "<!-- snippet: $id -->"
        val markerIndex = text.indexOf(marker)
        assertTrue(markerIndex >= 0, "missing marker '$marker' in $document")
        val fence = Regex("```$language\\R(.*?)\\R```", RegexOption.DOT_MATCHES_ALL)
            .find(text, markerIndex + marker.length)
        assertTrue(fence != null, "missing $language fence after '$marker' in $document")
        return fence.groupValues[1]
    }

    private fun markdownLinks(text: String): List<String> =
        Regex("""(?<!!)\[[^]]*]\(([^)]+)\)""")
            .findAll(text)
            .map { it.groupValues[1].substringBefore(' ').removeSurrounding("<", ">") }
            .toList()

    private fun isExternalOrAnchored(link: String): Boolean =
        link.startsWith("#") ||
            link.startsWith("https://") ||
            link.startsWith("http://") ||
            link.startsWith("mailto:")

    private fun normalize(block: String): String {
        val lines = block.lines().toMutableList()
        while (lines.firstOrNull()?.isBlank() == true) lines.removeFirst()
        while (lines.lastOrNull()?.isBlank() == true) lines.removeLast()
        val indent = lines.filterNot(String::isBlank).minOfOrNull(::leadingSpaces) ?: 0
        return lines.joinToString("\n") { line ->
            if (line.isBlank()) "" else line.drop(indent).trimEnd()
        }
    }

    private fun leadingSpaces(value: String): Int = value.indexOfFirst { it != ' ' }.let {
        if (it < 0) value.length else it
    }

    private fun repoFile(relative: String): Path = repoRoot().resolve(relative)

    private fun repoRoot(): Path {
        var candidate = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        while (!candidate.resolve("settings.gradle").exists()) {
            candidate = candidate.parent ?: error("repository root not found")
        }
        return candidate
    }
}

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
            "interpreter-typescript-form-pass",
            "docs/INTERPRETER-API-GUIDE.md",
            "interpreter/consumer-tests/typescript/src/guide-finite.ts",
            "ts",
        ),
        SnippetLock(
            "interpreter-typescript-pointer",
            "docs/INTERPRETER-API-GUIDE.md",
            "interpreter/consumer-tests/typescript/src/index.ts",
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
    fun publicReleaseDocumentsUseTheRepositoryVersion() {
        val build = Files.readString(repoFile("build.gradle"))
        val version = requireNotNull(Regex("""(?m)^version = '([^']+)'$""").find(build)).groupValues[1]
        val readme = Files.readString(repoFile("interpreter/README.md"))
        assertOnlyRepositoryVersion("interpreter/README.md", readme, version)
        assertTrue(
            listOf(
                "Version `$version`",
                "/v$version/a12-interpreter-$version.tgz",
                "dm-interpreter:$version",
            ).all(readme::contains),
            "interpreter/README.md does not consistently use repository version $version",
        )
        val guide = Files.readString(repoFile("docs/INTERPRETER-API-GUIDE.md"))
        assertOnlyRepositoryVersion("docs/INTERPRETER-API-GUIDE.md", guide, version)
        assertTrue(
            listOf(
                "dm-interpreter-$version-api-docs.zip",
                "api/v$version/",
            ).all(guide::contains),
            "docs/INTERPRETER-API-GUIDE.md does not consistently use repository version $version",
        )
    }

    @Test
    fun interpreterSourcesDoNotRepeatTheRepositoryVersion() {
        val build = Files.readString(repoFile("build.gradle"))
        val version = requireNotNull(Regex("""(?m)^version = '([^']+)'$""").find(build)).groupValues[1]
        val literalVersion = Regex("""(?<!\d)${Regex.escape(version)}(?!\d)""")
        val interpreterRoot = repoFile("interpreter")
        // The changelog is the ONE file whose literal version numbers are correct: the release mirror renders
        // it straight through (no accumulation), so the source carries the released history. Its own rule is
        // the sibling test below — the topmost entry must still be the derived placeholder.
        val allowed = setOf(repoFile("interpreter/README.md"), repoFile("interpreter/CHANGELOG.md"))
        val failures = Files.walk(interpreterRoot).use { paths ->
            paths.filter(Files::isRegularFile)
                .filter { !interpreterRoot.relativize(it).startsWith("build") }
                .filter { it.fileName.toString().substringAfterLast('.', "") in TEXT_EXTENSIONS }
                .filter { it !in allowed }
                .filter { literalVersion.containsMatchIn(Files.readString(it)) }
                .map { repoRoot().relativize(it).toString() }
                .toList()
        }
        assertTrue(
            failures.isEmpty(),
            "interpreter sources repeat repository version $version instead of deriving it:\n${failures.joinToString("\n")}",
        )
    }

    @Test
    fun theChangelogDerivesItsUpcomingEntryAndKeepsReleasedOnesLiteral() {
        val changelog = Files.readString(repoFile("interpreter/CHANGELOG.md"))
        val headings = Regex("""(?m)^## \[([^]]+)]$""").findAll(changelog).map { it.groupValues[1] }.toList()
        assertTrue(headings.isNotEmpty(), "interpreter/CHANGELOG.md has no '## [version]' entry")
        assertEquals(
            "@VERSION@",
            headings.first(),
            "the topmost interpreter/CHANGELOG.md entry must derive its version from the build, not repeat it",
        )
        assertTrue(
            headings.drop(1).none { it.contains('@') },
            "a released interpreter/CHANGELOG.md entry must carry its literal version: ${headings.drop(1)}",
        )
    }

    @Test
    fun publicReleaseSurfacesCarryTheMeasuredPerformanceStory() {
        val publicReadme = Files.readString(repoFile("interpreter/public-release/README.md"))
        assertTrue(
            publicReadme.indexOf("## Performance") > publicReadme.indexOf("## What ships"),
            "the public repository README must present performance immediately after what ships",
        )
        assertTrue(
            publicReadme.indexOf("**Semantics target.**") in
                publicReadme.indexOf("## What ships") until publicReadme.indexOf("## Performance"),
            "the semantics target must close what ships before the performance section",
        )
        val changelog = Files.readString(repoFile("interpreter/CHANGELOG.md"))
        val releases = Regex("(?m)^## \\[[^]]+]$").findAll(changelog).toList()
        val currentReleaseNotes = changelog.substring(
            releases.first().range.first,
            releases.getOrNull(1)?.range?.first ?: changelog.length,
        )
        for ((surface, text) in listOf(
            "main repository README" to Files.readString(repoFile("README.md")),
            "interpreter package README" to Files.readString(repoFile("interpreter/README.md")),
            "public repository README" to publicReadme,
            "current release notes" to currentReleaseNotes,
        )) {
            assertTrue(text.contains("232 kB gzip"), "$surface omits the measured browser footprint")
            assertTrue(text.contains("10.5 ms median"), "$surface omits the representative browser timing")
            assertTrue(text.contains("203.6 ms median"), "$surface omits the stress browser timing")
            assertTrue(text.contains("2026-08-03"), "$surface omits the measurement date")
            assertTrue(text.contains("Fast, and measured"), "$surface omits the measured-performance statement")
            assertTrue(
                text.contains("generated validator compiled as Java"),
                "$surface omits the fair Kernel-codegen comparison basis",
            )
            assertTrue(text.contains("INTERPRETER-PERFORMANCE.md"), "$surface does not link to the full method")
        }
    }

    @Test
    fun multiplyPackagedPublicDocumentsUsePortableLinks() {
        val documents = listOf(
            repoFile("interpreter/README.md"),
            repoFile("docs/INTERPRETER-API-GUIDE.md"),
        )
        val failures = documents.flatMap { document ->
            markdownLinks(Files.readString(document))
                .filterNot(::isExternalOrAnchored)
                .map { "${repoRoot().relativize(document)} -> $it" }
        }
        assertTrue(
            failures.isEmpty(),
            "multiply-packaged interpreter documents contain context-dependent links:\n${failures.joinToString("\n")}",
        )
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

    private fun assertOnlyRepositoryVersion(document: String, text: String, version: String) {
        val foreign = Regex("""\b\d+\.\d+\.\d+\b""")
            .findAll(text)
            .map { it.value }
            .filter { it != version }
            .toSet()
        assertTrue(foreign.isEmpty(), "$document contains stale or unrelated versions: $foreign")
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

    companion object {
        private val TEXT_EXTENSIONS = setOf(
            "gradle",
            "html",
            "java",
            "json",
            "kt",
            "md",
            "mjs",
            "properties",
            "sh",
            "toml",
            "ts",
            "yaml",
            "yml",
        )
    }
}

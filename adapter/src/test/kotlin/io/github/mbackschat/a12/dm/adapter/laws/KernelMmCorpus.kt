package io.github.mbackschat.a12.dm.adapter.laws

import com.mgmtp.a12.kernel.md.facade.DocumentModelServiceFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * The `a12-kernel-mm` metamodels as kernel 31.1.1 reads them: put through A12's own forward migrator.
 *
 * The 2026.06-ext0 metamodel release (`a12-kernel-mm` `f7ea179`) still stores its models at 28.3.0 and 28.4.0,
 * and kernel 31.1.1 refuses to build anything older than 29.4.0 ("Version mismatch: Document model version is
 * 28.4.0 and application version is 29.4.0", measured 2026-11-02). Read raw, every model fails to build, and a
 * sweep that skips the models the Kernel does not build passes vacuously. Migrating through
 * `IDocumentModelMigrator` is A12's own path to the version it reads, so the corpus stays A12-written; any
 * shape we produced ourselves would be the invented-shape defect.
 *
 * Each file is migrated alone, because the variants of one metamodel (`a12-`, `codegen-`, `-previous`) share
 * a `header.id` and none references another model (both read from the 75 files at `f7ea179`). The migrated
 * tree mirrors the original's relative paths under a temporary directory and is built once per test JVM;
 * a file the migrator refuses is left out, and [KernelMmCorpusTest] fails naming it, since the sweeps' own vacuity
 * assertions only see an empty corpus.
 */
object KernelMmCorpus {

    /** The sibling checkout's `documentModels` as stored, or null when it is absent. */
    @JvmStatic
    fun raw(): Path? = listOfNotNull(
        System.getProperty("a12.kernel.mm.dir"), "../a12-kernel-mm/documentModels", "../../a12-kernel-mm/documentModels",
    ).map { Path.of(it) }.firstOrNull { Files.isDirectory(it) }

    private val migratedRoot: Path? by lazy { raw()?.let(::migrate) }

    /** The same tree at the kernel's document-model version, or null when the checkout is absent. */
    @JvmStatic
    fun migrated(): Path? = migratedRoot

    private fun migrate(root: Path): Path {
        val migrator = DocumentModelServiceFactory().createDocumentModelMigrator(null)
        val target = Files.createTempDirectory("kernel-mm-migrated")
        Files.walk(root).use { paths ->
            paths.filter { it.extension == "json" }.sorted().forEach { file ->
                val migrated = migrator.migrate(listOf(file.readText()), null).singleOrNull()
                    ?.migratedDocumentModel?.orElse(null) ?: return@forEach
                val out = target.resolve(root.relativize(file).toString())
                Files.createDirectories(out.parent)
                out.writeText(migrated)
            }
        }
        return target
    }
}

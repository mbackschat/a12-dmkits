package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.conformance.CaseJson
import java.nio.file.Files
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * The corpus **generation / re-capture tool** — writes every [CorpusScenarios] case to `corpus/cases/<family>/` and
 * every referenced model to `corpus/models/<name>.json`, tri-verified by [CorpusCapture] as a side effect (a blocked
 * interpreter split throws, so a bad case is never written). It is the KERNEL-UPGRADE re-capture entry point
 * (CONFORMANCE-CORPUS-SPEC §13): on a kernel bump, re-run it and the committed diff **is** the behavior-change
 * characterization; `meta.kernelVersion` moves with it.
 *
 * Model files are **deduped by name**: cases that share a model (same domain name → same pretty-printed DM-JSON, e.g.
 * the two comparison cases) write a single `models/<name>.json`. A name that maps to two different model texts is an
 * authoring bug and fails the run (never silently overwritten).
 *
 * Opt-in only (`@Tag("corpus-capture")`, excluded from the default suite) since it mutates the working tree — run
 * with `-PrunCorpusCapture`. The everyday guard is [CorpusReplayTest], which replays the committed files.
 */
@Tag("corpus-capture")
class CorpusGenerator {

    @Test
    fun generateCorpus() {
        val tree = CorpusTree.render()
        val root = CorpusFiles.corpusRoot()
        for ((relative, text) in tree) {
            val file = root.resolve(relative)
            Files.createDirectories(file.parent)
            Files.writeString(file, text)
        }
        println("corpus: wrote ${tree.size} file(s) under $root")
    }
}

/**
 * What [CorpusGenerator] writes, by corpus-root-relative path: one case file per [CorpusScenarios] family and one model
 * file per distinct model name. [CorpusSchemaGuardTest] compares it with the committed tree, so a generator
 * change that would move the corpus fails where it is made rather than at the next kernel upgrade's re-capture.
 */
object CorpusTree {

    fun render(): Map<String, String> {
        val families = CorpusScenarios.all()
        val tree = sortedMapOf<String, String>()
        for (family in families) {
            tree["cases/${family.dir}/${family.case.meta.id}.json"] = CaseJson.write(family.case) + "\n"
        }
        val models = LinkedHashMap<String, String>()
        for (family in families) {
            val existing = models.put(family.model.name, family.model.json)
            require(existing == null || existing == family.model.json) {
                "two different models share the name '${family.model.name}' — a model name must map to one content"
            }
        }
        models.forEach { (name, json) -> tree["models/$name.json"] = json + "\n" }
        return tree
    }
}

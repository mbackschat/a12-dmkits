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
        val families = CorpusScenarios.all()

        val casesDir = CorpusFiles.casesDir()
        for (family in families) {
            val file = casesDir.resolve(family.dir).resolve("${family.case.meta.id}.json")
            Files.createDirectories(file.parent)
            Files.writeString(file, CaseJson.write(family.case) + "\n")
        }

        // Dedupe the referenced models by name → one file per distinct model, referenced by all its cases.
        val models = LinkedHashMap<String, String>()
        for (family in families) {
            val existing = models.put(family.model.name, family.model.json)
            require(existing == null || existing == family.model.json) {
                "two different models share the name '${family.model.name}' — a model name must map to one content"
            }
        }
        val modelsDir = CorpusFiles.modelsDir()
        Files.createDirectories(modelsDir)
        for ((name, json) in models) {
            Files.writeString(modelsDir.resolve("$name.json"), json + "\n")
        }

        println("corpus: wrote ${families.size} case(s) under $casesDir and ${models.size} model(s) under $modelsDir")
    }
}

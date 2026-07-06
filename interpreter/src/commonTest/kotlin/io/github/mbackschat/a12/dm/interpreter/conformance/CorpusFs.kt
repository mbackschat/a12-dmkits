package io.github.mbackschat.a12.dm.interpreter.conformance

/**
 * One committed corpus file as the platform FS shim reads it: its [family] (parent-directory name), [id] (file
 * stem), and raw JSON [text]. Parsing to a [ConformanceCase] is common ([CaseJson.parse]); only the file
 * listing + reading is platform-specific ([readCorpusFiles]).
 */
class RawCorpusFile(val family: String, val id: String, val text: String)

/**
 * Read every committed conformance case in the `corpus/cases` tree — the multiplatform FS shim (WI-6). The corpus
 * directory is located from the **`CORPUS_DIR` environment variable Gradle sets on the test tasks** (the robust,
 * runtime-independent path — not a fragile relative guess from the Node/JVM working directory); the JVM actual
 * additionally falls back to a repo-root walk-up for an IDE run. Entries are sorted by `family/id` (stable test
 * order); the list is empty when the corpus directory is absent.
 */
expect fun readCorpusFiles(): List<RawCorpusFile>

/**
 * Resolve a case's [ConformanceCase.modelRef] (corpus-root-relative, e.g. `"models/order-basic.json"`) against the
 * corpus **root** — the parent of the `corpus/cases` directory located above — and read the standalone model file
 * (§12 step 1). Throws with a corrective message naming the expected form when the ref does not resolve: an
 * unresolvable `modelRef` is a corpus defect, not a runner verdict.
 */
expect fun readCorpusModel(modelRef: String): String

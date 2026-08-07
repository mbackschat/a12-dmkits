# A12 Interpreter changelog

This changelog covers the independently consumable interpreter packages, API documentation, and browser showcase published through `a12-interpreter-releases`.

**Headline topics only.** An entry names the capabilities and breaking changes a consumer would notice, not every fix behind them; the [main project's](https://github.com/mbackschat/a12-dmkits) git history and findings docs are the complete record.

## [@VERSION@]

### Added

- **One prepared model serves concurrent requests.** Model-static preparation is owned by the `PreparedModel` rather than by each `Interpreter`, so a bounded server-side cache holds one entry per model instead of one per (model × configuration), and a retained interpreter adds only 2–5% over the prepared model. Sharing a single `Interpreter` across threads is now refused (`CONCURRENT_USE`) instead of letting a custom condition observe another thread's Document — a `PreparedModel` is what may be shared, an interpreter is per request.
- **Computation reports explain unsuccessful computation** — eager operand diagnostics, a target-owned `diagnostic` on every ERRORED result, and a Kernel-matching `noErrorOccurred` predicate — and prepared Documents can produce a model-shaped starter through `seedDocument()`.
- **The browser showcase is a complete interpreter workflow consumer**: inspect models, generate and edit Documents, choose full or partial validation, compute, apply retained actions, and run compute-then-validate, including repeated computed fields.

### Changed — BREAKING

- **A12's pointer types are matched exactly, per entry point.** `A12Pointer` is A12's exact `DocumentPointer` and now carries its shape; `A12PartiallyKnownMultiPointer` publishes A12's message-channel address and has no string form, because A12 defines none; `ValidationFinding` carries that pointer instead of a rendered string, since the house rendering dropped exactly A12's two message sentinels; and `format` now refuses everything `parse` refuses. Each domain is measured against the real kernel factories rather than read from its source.
  - *Migration:* `A12Pointer` gains `pathParts` (a list of `A12PathPart`) and loses `names`/`repetitions` — read `repetitionIndexes` or the parts; construct with **`A12Pointer.of`**, since the public constructor and the generated `copy`/`componentN` are gone. **`A12PartiallyKnownMultiPointerFormat` is removed** — use `A12PartiallyKnownMultiPointer.of(fullName, repetitionIndexes)`, with `WILDCARD`/`UNKNOWN`/`toExactPointer` on the type. **`UNKNOWN_REPETITION` is removed** from `A12PointerFormat` (it is a message-channel value: use `A12PartiallyKnownMultiPointer.UNKNOWN`); `LAST_PART_ZERO` is unchanged. New: `A12PointerFormat.render(pointer)`. `toExactPointer()` now raises on the root instead of silently returning it. A consumer that printed `finding.errorPath` now prints an object.
- **`UnsupportedProgram` says which kind of address it carries** — a model path or a document cell instance — which a consumer previously could not tell apart. *Migration:* Kotlin callers constructing one positionally must supply the third argument (`subject`); consumers that only read reports gain a field and break nothing.

### Performance

- **Measured, never assumed.** The production browser JavaScript entry is about **233 kB gzip**; on the fixed Apple M1/Chrome runner a 200-row Document validated in a **10.5 ms median** and a 10,000-row stress Document with 4,900 findings in **203.6 ms**. Against the A12 Kernel's generated Java with code generation outside the clock, the interpreter led by **2.1×–12.5×** across the default inventory and **10×–16.7×** on repeated-document evaluation, with the closest hostile point still ahead by 1.32×. These are bounded same-machine measurements after exact result preflight, not universal promises; see [the benchmark method and complete tables](https://github.com/mbackschat/a12-dmkits/blob/main/docs/INTERPRETER-PERFORMANCE.md).

### Fixed

- Row-invariant unfiltered starred aggregates fold once per validation call rather than once per iterated row (the release guard measured 17.12× growth for 4× rows before, 4.51× after).
- Temporal semantics corrections: DATE and DATETIME share A12's instant timeline for ordering, `DateRange` evaluates as an equality operand and computation value, `FirstFilledValue` preserves the owner's entity selectors, and `BaseYear` observes A12's year bounds.

## [0.10.0]

### Changed — BREAKING

- **The Document boundary speaks A12's own Document JSON** — the shape the A12 Kernel's serializer produces and consumes — replacing the placement envelope this project had invented for a concept A12 already defines. Reading is also value-normalizing, matching A12's ingestion: a number becomes its plain form with its scale intact, a boolean normalizes case-insensitively, and a value A12's converter cannot parse stays verbatim.
  - *Migration:* removed `DocumentInputV1`, `GroupPlacementInput`, `FieldPlacementInput`, `DocumentPlacementKind` (use `ModelEntityKind`), TypeScript `decodeDocument`/`tryDecodeDocument`, and `DocumentDecodeLimits.maxCoordinatesPerPlacement`. Added `A12Document`/`A12DocumentGroup`/`A12DocumentField`, `DocumentSnapshot.documentId`, `DocumentEncodeError`, `DocumentJsonNodeType`, and `DocumentDecodeLimits.maxDocumentCodeUnits`. Kotlin/Java/native pass and receive JSON text (`readDocument(json)`, `encodeDocument(snapshot)`); TypeScript `readDocument` also accepts an object and `encodeDocumentJson` returns the text.

### Added

- **Computation results are retained and can be applied to another immutable Document snapshot**, with `actions` exposing the source-relative subset and `applyTo(destination)` consuming it without recomputing.
- **All A12 partial-Date precisions are supported**, and temporal constructors match the complete Kernel operand surface.

### Fixed

- A malformed BOOLEAN or CONFIRM value is a formal error, as it is in A12, instead of silently evaluating as `false`.
- JSON comments are accepted in a model source and in a document, as A12 accepts them; numbers are written in A12's plain form.

## [0.9.1]

Initial public release.

### Added

- Kotlin Multiplatform and TypeScript packages for JVM, Node.js, and browser consumers
- Immutable finite and on-demand workspaces with include and imported-type-definition resolution
- Self-contained model expansion, Document transport, full and partial validation, and computations
- Custom condition and custom field-type extension points
- Versioned Kotlin and TypeScript API references, tested developer guide, and `llms.txt`
- Browser showcase with local workspace/Document loading, operation timing, provider failures, and opt-in offline support

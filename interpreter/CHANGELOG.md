# A12 Interpreter changelog

This changelog covers the independently consumable interpreter packages, API documentation, and browser showcase published through `a12-interpreter-releases`.

## [@VERSION@]

### Added

- **Computation reports now explain unsuccessful computation.** `formalErrorsInOperands` carries eager operand diagnostics, each ERRORED result carries its target-owned `diagnostic`, and `noErrorOccurred` matches the Kernel predicate while remaining independent of `fullySupported`. Kotlin, Java, Kotlin/JS, packed TypeScript, and the browser showcase consume the same detached `ValidationFinding` projection.
- **Prepared Documents can produce a model-shaped starter.** `prepared.documents.seedDocument()` includes every declared group and field and one row per repeatable group, using the same deterministic generator as `dmtool model seed`.

## [0.10.0]

### Changed — BREAKING

- **The Document boundary now speaks A12's own Document JSON**, the shape the A12 Kernel's document serializer produces and consumes: a nested object tree mirroring the model, a repeating group as a JSON array whose index is the repetition, `{}` for an unfilled or empty group row, a missing key for an omission, `null` for a present field with no value, and native JSON for numbers and booleans. The previous placement envelope was a shape this project had invented for a concept A12 already defines, so it is removed rather than deprecated.
  - Removed: `DocumentInputV1`, `GroupPlacementInput`, `FieldPlacementInput` (all targets); `DocumentPlacementKind` (use the existing `ModelEntityKind`); TypeScript `decodeDocument`/`tryDecodeDocument`.
  - Added: `A12Document`/`A12DocumentGroup`/`A12DocumentField` (TypeScript), `DocumentSnapshot.documentId` (A12's root-level `id`), `DocumentEncodeError`, `DocumentJsonNodeType`.
  - Kotlin, Java, and native callers pass and receive the JSON text: `readDocument(json: String)`, `encodeDocument(snapshot): String`. TypeScript `readDocument` accepts a plain object or its text; `encodeDocument` returns an object and `encodeDocumentJson` the text.
  - `DocumentInputError` diagnostics now locate each defect by its canonical A12 `DocumentPointer` string with the model path, declared entity kind, and JSON node type found, instead of an envelope index.
  - `DocumentDecodeLimits`: added `maxDocumentCodeUnits` (checked before parsing); removed `maxCoordinatesPerPlacement`, which duplicated `maxModelNestingDepth` once coordinates became structural; raised the group-row ceilings to match the field-cell ones (default 100,000, hard maximum 1,000,000) because every group row is now explicit in the document rather than materialized after validation.

- **Reading a document is now value-normalizing, matching A12's own ingestion.** A12 stores `String | Boolean | BigDecimal | Instant | InstantRange` and re-renders every text from the stored value, so an authored spelling never survives it. `readDocument` reproduces that: a number becomes its plain form (`1e5` → `100000`, `"+5"` → `5`, `"007"` → `7`, `-0.0` → `0.0`, with `250.00` keeping its scale), a boolean becomes `true`/`false` case-insensitively, `""` on a typed field becomes the empty cell (written back as `null`), and a `leadingZerosAllowed` number, the date family, strings, and enumerations are left exactly as authored. A value A12's converter cannot parse stays verbatim and is written back as a string. Previously the authored text was stored unchanged, so a document could round-trip into a spelling A12 would never write.

### Fixed

- **A malformed BOOLEAN or CONFIRM value is now a formal error**, as it is in A12: a boolean must be exactly `true` or `false` (`feldJaNeinFalsch` otherwise) and a confirm exactly `true` (`feldJaFalsch` otherwise, including a well-formed `false`). The check is **case-sensitive** on a stored value — `"TRUE"` is invalid — while a document read from JSON still normalizes `"TRUE"` to `true` before storage, so this is reachable by a caller that fills a `Document` directly. Previously no such check existed and a malformed token evaluated as `false`, silently, instead of making the field invalid and suppressing the rules that read it.
- **JSON comments are accepted in a model source and in a document**, as they are by A12: both of the Kernel's mappers enable `ALLOW_COMMENTS`, so a commented file is a legal A12 artifact and was previously refused here. A trailing comma remains a parse error, which A12 also refuses.
- Numbers are written in A12's **plain** form, matching the Kernel's `WRITE_BIGDECIMAL_AS_PLAIN` generator: never scientific notation, with the authored scale intact (`250.00` stays `250.00`, `1e5` becomes `100000`, `1.2e-3` becomes `0.0012`).
- A confirm field's `false` is written back as the string `"false"`, not as a JSON boolean: A12's converter accepts only `true` for a confirm, so `false` is a value it could not convert.
- The canonical pointer utility (`A12PointerFormat`) constructed an invalid regular expression on Kotlin/JS, so parsing a pointer failed on that target.

### Added

- **Computation results are retained and can be applied to another immutable Document snapshot.** `ComputationReport.results` remains report-all, while `actions` exposes the source-relative application subset and `applyTo(destination)` consumes it without recomputing. VALUE and CLEARED create an addressed missing target and ancestry; ERRORED clears only an existing target. Kotlin, Java, and TypeScript also expose `Interpreter.applyComputations(source)` as the same-document convenience. A destination from another prepared owner fails with `ModelOwnerMismatchError`.
- **All A12 partial-Date precisions are supported.** `DAY_OPTIONAL`, `MONTH_OPTIONAL`, and `YEAR_OPTIONAL` retain their monotone omitted-component domain; `ValueAsDate` resolves known-year boundaries while an unknown year stays non-relevant. The opt-in `youngerThan1900Check` is honored for stored and authorable computed targets.
- **Temporal constructors match the complete Kernel operand surface.** `Time()` through `Time(hour, minute, second)` default omitted trailing components to zero, and Date/Time components accept the measured String, Number, Date, and extractor sources with exact no-value provenance and model-zone wall-clock projection.
- **The browser showcase names the exact A12 Kernel and A12 Tools semantics target** beside the interpreter package version.

### Changed

- Field-owned String-pattern and requiredness messages now use their producer-specific token grammars. Pattern templates accept only `$field$` and `$field.value$` with one-pass insertion; requiredness preserves its measured doubled-dollar text.
- Calendar Date shifts, constructed-Date comparisons, starred-group polarity, multi-computation selection, dependency poison, and source-value change classification now match the maintained dual-Kernel matrices.

## [0.9.1]

Initial public release.

### Added

- Kotlin Multiplatform and TypeScript packages for JVM, Node.js, and browser consumers
- Immutable finite and on-demand workspaces with include and imported-type-definition resolution
- Self-contained model expansion, Document transport, full and partial validation, and computations
- Custom condition and custom field-type extension points
- Versioned Kotlin and TypeScript API references, tested developer guide, and `llms.txt`
- Browser showcase with local workspace/Document loading, operation timing, provider failures, and opt-in offline support

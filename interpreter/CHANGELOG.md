# A12 Interpreter changelog

This changelog covers the independently consumable interpreter packages, API documentation, and browser showcase published through `a12-interpreter-releases`.

## [@VERSION@]

### Added

- **Both A12 pointer types are now on the npm surface too** — `A12PointerFormat` with `A12Pointer`/`A12PathPart`, and `A12PartiallyKnownMultiPointerFormat` with `A12PartiallyKnownMultiPointer` — so a TypeScript consumer can join a `pointer.path` and its `coordinates` into one canonical A12 pointer, and read back a report path in the type A12 gives it. This matters most for a computation inside a repeatable group: every row reports the same `pointer.path`, so without the conversion the rows are indistinguishable. They are classes with static members mirroring the Kotlin objects; a malformed argument raises a `TypeError`, since a caller contract violation is not a model diagnostic and gets no named error type.
- **The browser showcase demonstrates a repeated computed field.** The built-in permit model now charges every site-section row its own survey fee, and each computation result is shown with its rendered canonical pointer beside the raw `pointer` object.
- **`A12PartiallyKnownMultiPointerFormat`** publishes A12's message-channel addressing type, `PartiallyKnownDocumentMultiPointer` — the type `IMessage.getErrorFieldPointer()` and its two siblings actually return, and therefore the type of `ValidationFinding.errorPath`, `referenced` and `fillToFix`. It carries A12's own shape (`fullName` plus `repetitionIndexes`), admits `WILDCARD` (`0`) at every part and `UNKNOWN` (`-5`, `IIdentifier.UNKNOWN`) for a level a parallel iteration left unresolved, and converts to an exact pointer through `toExactPointer` when the value really names one cell. Published on Kotlin/JVM, Kotlin/JS and npm.
- **Computation reports now explain unsuccessful computation.** `formalErrorsInOperands` carries eager operand diagnostics, each ERRORED result carries its target-owned `diagnostic`, and `noErrorOccurred` matches the Kernel predicate while remaining independent of `fullySupported`. Kotlin, Java, Kotlin/JS, packed TypeScript, and the browser showcase consume the same detached `ValidationFinding` projection.
- **Prepared Documents can produce a model-shaped starter.** `prepared.documents.seedDocument()` includes every declared group and field and one row per repeatable group, using the same deterministic generator as `dmtool model seed`.
- **The browser showcase is now a complete interpreter workflow consumer.** It can inspect the loaded Document Models, generate and edit model-shaped Documents, choose full or partial validation, compute, apply retained computation actions, and run compute-then-validate. The built-in permit model demonstrates required fields, computations, and repeating groups with prepared rows.

### Changed — BREAKING

- **`UnsupportedProgram` now says which kind of address it carries.** The new `subject` (`UnsupportedProgramSubject.ELEMENT` · `CELL`; `"element"` · `"cell"` in TypeScript) distinguishes a model path — a rule, a computation, or a field declaration — from a document cell instance pointer. Both kinds always arrived in the same list: a support audit reports only elements, while validation and computation report both, and a consumer had no way to tell them apart, so it could render neither correctly. Kotlin callers that construct an `UnsupportedProgram` positionally must supply the third argument; consumers that only read the reports gain a field and break nothing.
- **`A12Pointer` is A12's *exact* `DocumentPointer` and nothing else, and it now carries A12's shape.** It had been a hybrid: two parallel `names`/`repetitions` lists (a shape A12 uses only as *factory arguments*, never as a pointer's state) admitting the union of two A12 domains — `UNKNOWN_REPETITION` (`-5`) on a pointer documented as exact, which A12's own part pattern (`\d+`, unsigned) cannot even read, while still enforcing the exact type's last-part-only `0`. The union is a shape A12 has nowhere, so it matched neither channel.
  - `A12Pointer` now holds `pathParts: List<A12PathPart>` — A12's `PathPart`, a `name` plus a `repetitionIndex` — with `fullName`, `repetitionIndexes`, `size` and `partAtLevel` derived from it, exactly as `DocumentPointer` does. `names`/`repetitions` are gone; read `repetitionIndexes`, or the parts.
  - Construction is `A12Pointer.of(pathParts)`, which validates and snapshots. The public constructor and the generated `copy`/`componentN` are gone: a `data class` cannot validate *and* snapshot, since `init` cannot reassign a `val` constructor property, so its `copy` would have handed back an unchecked value.
  - `UNKNOWN_REPETITION` is removed from `A12PointerFormat`. `-5` is a message-channel value: use `A12PartiallyKnownMultiPointerFormat.UNKNOWN`. `LAST_PART_ZERO` is unchanged.
  - `A12PointerFormat.render(pointer)` is new — the canonical string of an already-built pointer, without a round trip through `fullName`.
  - On TypeScript, `A12Pointer` gains `pathParts`/`fullName`/`repetitionIndexes` and loses `names`/`repetitions`; `A12PointerFormat.UNKNOWN_REPETITION` is gone.
  - Both domains are now measured against the real kernel factories rather than read from its source (`adapter … laws/A12PointerKernelDomainDiffTest`), which is also how the two places we are deliberately stricter than A12 stay recorded — see [KERNEL-FINDINGS kf171](../docs/KERNEL-FINDINGS.md#kf171).
  - Fixed in passing: a bare `"/"` was refused. A12's `parseFullName` excludes exactly that string from its trailing-slash refusal and reads it as the root pointer, so refusing it made the partially-known type's own `fullName()` for the root unreadable by the function that produced it.
- **`A12PointerFormat.format` now refuses what `parse` refuses**, instead of emitting a string its own parser rejects. It had validated only the arity, so it produced `Order[0]/Count` (a `[0]` before the last part), `Order[-7]/Count` (a negative that is not A12's `-5`), `Order[2][3]/Count` (an already-indexed name growing a second bracket), and names outside the part grammar — **4,321** such strings over the bounded test domain. The domain now lives in one place both entry points call, so neither can admit what the other refuses. A caller that passed an illegal repetition or a pre-indexed name gets an `IllegalArgumentException` (a `TypeError` from TypeScript) where it previously received a malformed pointer; pass an indexed part as the bare name plus its repetition. A12's **root pointer** (`format("", [])` → `""`) stays legal and is pinned by its own test — see [KERNEL-FINDINGS kf170](../docs/KERNEL-FINDINGS.md#kf170).

### Changed

- The showcase separates runtime actions from workspace utilities, keeps the model/result panels collapsible, and expands with the browser width so model and Document JSON remain usable on wide screens.

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

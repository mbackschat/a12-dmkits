# A12 Interpreter changelog

This changelog covers the independently consumable interpreter packages, API documentation, and browser showcase published through `a12-interpreter-releases`.

## [@VERSION@]

### Changed — BREAKING

- **The Document boundary now speaks A12's own Document JSON**, the shape the A12 Kernel's document serializer produces and consumes: a nested object tree mirroring the model, a repeating group as a JSON array whose index is the repetition, `{}` for an unfilled or empty group row, a missing key for an omission, `null` for a present field with no value, and native JSON for numbers and booleans. The previous placement envelope was a shape this project had invented for a concept A12 already defines, so it is removed rather than deprecated.
  - Removed: `DocumentInputV1`, `GroupPlacementInput`, `FieldPlacementInput` (all targets); `DocumentPlacementKind` (use the existing `ModelEntityKind`); TypeScript `decodeDocument`/`tryDecodeDocument`.
  - Added: `A12Document`/`A12DocumentGroup`/`A12DocumentField` (TypeScript), `DocumentSnapshot.documentId` (A12's root-level `id`), `DocumentEncodeError`, `DocumentJsonNodeType`.
  - Kotlin, Java, and native callers pass and receive the JSON text: `readDocument(json: String)`, `encodeDocument(snapshot): String`. TypeScript `readDocument` accepts a plain object or its text; `encodeDocument` returns an object and `encodeDocumentJson` the text.
  - `DocumentInputError` diagnostics now locate each defect by its canonical A12 `DocumentPointer` string with the model path, declared entity kind, and JSON node type found, instead of an envelope index.
  - `DocumentDecodeLimits`: added `maxDocumentCodeUnits` (checked before parsing); removed `maxCoordinatesPerPlacement`, which duplicated `maxModelNestingDepth` once coordinates became structural; raised the group-row ceilings to match the field-cell ones (default 100,000, hard maximum 1,000,000) because every group row is now explicit in the document rather than materialized after validation.

- **Reading a document is now value-normalizing, matching A12's own ingestion.** A12 stores `String | Boolean | BigDecimal | Instant | InstantRange` and re-renders every text from the stored value, so an authored spelling never survives it. `readDocument` reproduces that: a number becomes its plain form (`1e5` → `100000`, `"+5"` → `5`, `"007"` → `7`, `-0.0` → `0.0`, with `250.00` keeping its scale), a boolean becomes `true`/`false` case-insensitively, `""` on a typed field becomes the empty cell (written back as `null`), and a `leadingZerosAllowed` number, the date family, strings, and enumerations are left exactly as authored. A value A12's converter cannot parse stays verbatim and is written back as a string. Previously the authored text was stored unchanged, so a document could round-trip into a spelling A12 would never write.

### Fixed

- Numbers are written in A12's **plain** form, matching the Kernel's `WRITE_BIGDECIMAL_AS_PLAIN` generator: never scientific notation, with the authored scale intact (`250.00` stays `250.00`, `1e5` becomes `100000`, `1.2e-3` becomes `0.0012`).
- A confirm field's `false` is written back as the string `"false"`, not as a JSON boolean: A12's converter accepts only `true` for a confirm, so `false` is a value it could not convert.
- The canonical pointer utility (`A12PointerFormat`) constructed an invalid regular expression on Kotlin/JS, so parsing a pointer failed on that target.

## [0.9.1]

Initial public release.

### Added

- Kotlin Multiplatform and TypeScript packages for JVM, Node.js, and browser consumers
- Immutable finite and on-demand workspaces with include and imported-type-definition resolution
- Self-contained model expansion, Document transport, full and partial validation, and computations
- Custom condition and custom field-type extension points
- Versioned Kotlin and TypeScript API references, tested developer guide, and `llms.txt`
- Browser showcase with local workspace/Document loading, operation timing, provider failures, and opt-in offline support

# A12 Interpreter changelog

This changelog covers the independently consumable interpreter packages, API documentation, and browser showcase published through `a12-interpreter-releases`.

**Headline topics only.** An entry names the capabilities and breaking changes a consumer would notice, not every fix behind them; the [main project's](https://github.com/mbackschat/a12-dmkits) git history and findings docs are the complete record.

## [@VERSION@]

### Added

- **`Today` and `Now` evaluate through the published API.** `InterpreterConfiguration` takes an optional evaluation instant (`evaluationInstant`, a `Date` in TypeScript), read in the model's time zone; without one, each call reads the current instant, as the Kernel does. In TypeScript a `Date` from another realm is accepted. Rules using either constant used to be reported unsupported.
- **Rules and computations that join repeatable groups by their index field now evaluate starred aggregates, filters and keyed reads** instead of reporting them unsupported. As in the Kernel, each ranges over the rows under the joined groups' common parent, whichever rows the join paired. A starred read below one of the joined groups reads that group's joined row, empty where the group has no row for the key, and joins the group even when nothing else reads it, a rule reading every group only through starred reads included.

### Fixed

- **`RepetitionNotUnique` keys in sibling fixed groups of one repeatable row now evaluate**, compared per that row in either key order, as in the Kernel; the model was refused as unsupported.
- **A computation copying an empty Confirm field now leaves its Confirm target empty**, as the Kernel does; it used to store `false` there and report the target erroneous.
- **A rule condition that opens with the scale-gate waiver now loads and evaluates**, in the model's condition language (`@SuppressWarning(MVK_INVALID_COMPARE_DEC_PLACES)` or `@UnterdrueckeWarnung(…)`); the model was refused at its first waived rule. The waived comparison is evaluated exactly, as in the Kernel.
- **A required field's own message renders every token the Kernel substitutes**: `$field$`, `$Feld$`, and the empty `$<fieldName>.Value$`, `$field.value$` and `$Feld.Wert$`, in either condition language. A value token used to make the document unsupported.
- Keyed group row numbers and index value/category tokens now render in rule messages, including an unmatched parallel row’s joined key.
- **Model `boolean` members are read as the Kernel binds them**: a padded `" true"` or `" TRUE"` and the integer `1` set `fieldRefByShortNameAllowed`, `Field.global` and every field type's flag (line breaks, value validation, zero, sign, leading zeros, the 1900 check), and a blank or `"null"` text reads as off.
- **A dated literal compared with `FirstFilledValue` of dates is read as a date**, as beside a date field or `MinValue`; it was compared as text, so `FirstFilledValue(RevisedBy, PromisedBy) < "01.01.2026"` answered wrongly whenever the text order and the date order disagreed.

- **A `DateRange(start, finish)` construction now equals the stored range it was built to match**, wherever the declared profile omits the day (`MM`, `yyyy`, `yyyy-MM`). Both endpoints were completed to the earliest date they denote; the Kernel completes the START to the earliest and the FINISH to the latest, exactly as it does for a stored range.
- **A stored date range that visibly carries its separator is no longer reported as missing one.** An extra separator or an empty endpoint is a format error, which is what the Kernel reports; only a value that does not contain the separator at all is the missing-separator error.
- The two date-range formal messages were reworded to match the narrowed classification: the missing-separator one no longer says "or misplaced", and the format one now says the value is not a pair of two valid dates.
- **A month-pair range declared with an empty separator is read as two months.** That declaration (`MM` with no separator, the one the Kernel admits) halves the stored value; it was previously split per character and every value reported malformed.

### Performance

- A filtered starred read over nested rows, where one row holds far more children than the others, no longer holds a placeholder for every child slot of every row in memory, and skips the empty slots instead of visiting each.
- Partial validation over a relevant set that names every row now decides each row's relevance by index, so its cost grows linearly with the rows rather than with their square.
- A per-row rule that counts filled fields or groups across a starred column, or asks a fill quantifier over one, now reads the column once per scope instead of once per row, so its cost grows linearly with the rows rather than with their square.
- **Fast, and measured — never assumed.** On the fixed Apple M1/Chrome runner on 2026-08-17, a 200-row Document validated in a **11.55 ms median** and the 10,000-row stress Document with 4,900 findings in a **166.1 ms median**. Against the A12 Kernel's generated validator compiled as Java, with code generation outside the clock, the interpreter led by **2.8×–17.1×** across the default inventory and **12.2×–16.8×** on repeated-document evaluation, with the closest hostile point still ahead by 1.73×. These are bounded same-machine measurements after exact result preflight, not universal promises; see [the benchmark method and complete tables](https://github.com/mbackschat/a12-dmkits/blob/main/docs/INTERPRETER-PERFORMANCE.md).

## [0.13.0]

### Added

- **An operand may name a GROUP, everywhere the A12 Kernel accepts one.** Where a condition names a repeatable or non-repeatable group instead of a field — `Sum(/Order/Billing)`, `NumberOfFilledFields(/Order/Lines*)`, `FieldValuesNotUnique(/Shipment/Consignments*)`, `FirstFilledValue`, `NumberOfValueInFields`, the value-list quantifiers, the date and number extrema, `AtLeastOneDateRangeOverlaps` — the operand now expands to every field the group contains, recursively through nested subgroups and across their rows, bound at the operand's own level rather than at the rule's. The same expansion runs on the polarity channel, so an error's message type matches the Kernel's too, and on the computation fill scan. Previously these read a value at the group's own path, where no field is declared, and quietly answered from an empty selection — a `Sum` of zero, a count of zero, a duplicate never found.
- **A fill quantifier can be asked over two filtered stars**, each carrying its own filter.

### Performance

- **Fast, and measured — never assumed.** On the fixed Apple M1/Chrome runner on 2026-08-03, a 200-row Document validated in a **10.5 ms median** and the 10,000-row stress Document with 4,900 findings in a **203.6 ms median**. Against the A12 Kernel's generated validator compiled as Java, with code generation outside the clock, the interpreter led by **2.1×–12.5×** across the default inventory and **10×–16.7×** on repeated-document evaluation, with the closest hostile point still ahead by 1.32×. These are bounded same-machine measurements after exact result preflight, not universal promises; see [the benchmark method and complete tables](https://github.com/mbackschat/a12-dmkits/blob/main/docs/INTERPRETER-PERFORMANCE.md).
- Repeated evaluation over one prepared model got cheaper again: the relevance query no longer rebuilds path strings on every consultation, the cascade overlay holds one entry per cell rather than one per lookup, and the per-cell over-repetition gate is skipped entirely on a Document that cannot need it.

### Fixed

- A `DATE_TIME` field declared with the Kernel-legal **time-only** format is read as a value instead of being reported malformed and then suppressed.
- An **undeclared field or group reference** in one rule is reported against that rule instead of taking the whole model's evaluation down with it.
- A group operand under `Min`/`Max` in a condition evaluated without a model pre-flight degrades to its own rule's error rather than aborting the evaluation.

## [0.12.1]

### Added

- **The showcase states its own independence.** The published landing page and the showcase itself now carry, visibly rather than only in a README, that this is not affiliated with, endorsed by, or supported by mgm technology partners and is not an A12 product or service, that it is provided as is with no warranty or commitment as to correctness, and a link to the full disclaimer. Nothing about how the showcase handles your files changed: models and Documents are still read and processed locally and are never uploaded.
- **One prepared model serves concurrent requests.** Model-static preparation is owned by the `PreparedModel` rather than by each `Interpreter`, so a bounded server-side cache holds one entry per model instead of one per (model × configuration), and a retained interpreter adds only 2–5% over the prepared model. Sharing a single `Interpreter` across threads is now refused (`CONCURRENT_USE`) instead of letting a custom condition observe another thread's Document — a `PreparedModel` is what may be shared, an interpreter is per request.

### Performance

- **Fast, and measured — never assumed.** On the fixed Apple M1/Chrome runner on 2026-08-03, a 200-row Document validated in a **10.5 ms median** and the 10,000-row stress Document with 4,900 findings in a **203.6 ms median**. Against the A12 Kernel's generated validator compiled as Java, with code generation outside the clock, the interpreter led by **2.1×–12.5×** across the default inventory and **10×–16.7×** on repeated-document evaluation, with the closest hostile point still ahead by 1.32×. These are bounded same-machine measurements after exact result preflight, not universal promises; see [the benchmark method and complete tables](https://github.com/mbackschat/a12-dmkits/blob/main/docs/INTERPRETER-PERFORMANCE.md).

### Fixed

- Row-invariant unfiltered starred aggregates fold once per validation call rather than once per iterated row (the release guard measured 17.12× growth for 4× rows before, 4.51× after).
- Partial validation gates starred aggregates exactly as the Kernel does across nested repetitions: the question is asked per iteration row, `SumOfProducts` requires the full cross-product of the row's starred extent taken over the union of the relevant entities, a multi-star operand is judged over every level its star iterates, a component-incomplete DATE format is completed, `FieldValuesNotUnique` scans its operands in authored order, and aggregate message references are retained.
- Validation findings no longer copy the pointer list the engine shares across the findings of one duplicate-key cluster, so a densely duplicated Document costs linear rather than quadratic memory at the API boundary.

## [0.11.0]

### Added

- **Both A12 pointer types reach the npm surface**, so a TypeScript consumer can join a `pointer.path` and its `coordinates` into one canonical A12 pointer — the thing that tells apart the rows of a computation inside a repeatable group.
- **Computation reports explain unsuccessful computation** — eager operand diagnostics, a target-owned `diagnostic` on every ERRORED result, and a Kernel-matching `noErrorOccurred` predicate — and prepared Documents can produce a model-shaped starter through `seedDocument()`.
- **The browser showcase is a complete interpreter workflow consumer**: inspect models, generate and edit Documents, choose full or partial validation, compute, apply retained actions, and run compute-then-validate, including repeated computed fields.

### Changed — BREAKING

- **A12's pointer types are matched exactly, per entry point.** `A12Pointer` is A12's exact `DocumentPointer` and now carries its shape; `A12PartiallyKnownMultiPointer` publishes A12's message-channel address and has no string form, because A12 defines none; `ValidationFinding` carries that pointer instead of a rendered string, since the house rendering dropped exactly A12's two message sentinels; and `format` now refuses everything `parse` refuses. Each domain is measured against the real kernel factories rather than read from its source.
  - *Migration:* `A12Pointer` gains `pathParts` (a list of `A12PathPart`) and loses `names`/`repetitions` — read `repetitionIndexes` or the parts; construct with **`A12Pointer.of`**, since the public constructor and the generated `copy`/`componentN` are gone. **`A12PartiallyKnownMultiPointerFormat` is removed** — use `A12PartiallyKnownMultiPointer.of(fullName, repetitionIndexes)`, with `WILDCARD`/`UNKNOWN`/`toExactPointer` on the type. **`UNKNOWN_REPETITION` is removed** from `A12PointerFormat` (it is a message-channel value: use `A12PartiallyKnownMultiPointer.UNKNOWN`); `LAST_PART_ZERO` is unchanged. New: `A12PointerFormat.render(pointer)`. `toExactPointer()` now raises on the root instead of silently returning it. A consumer that printed `finding.errorPath` now prints an object.
- **`UnsupportedProgram` says which kind of address it carries** — a model path or a document cell instance — which a consumer previously could not tell apart. *Migration:* Kotlin callers constructing one positionally must supply the third argument (`subject`); consumers that only read reports gain a field and break nothing.

### Performance

- The production browser bundle measured a 200-row Document at a **10.0 ms median** and the 10,000-row stress Document at **187.7 ms**, on the fixed Apple M1/Chrome runner.

### Fixed

- Temporal semantics corrections: DATE and DATETIME share A12's instant timeline for ordering, `DateRange` evaluates as an equality operand and computation value, `FirstFilledValue` preserves the owner's entity selectors, and `BaseYear` observes A12's year bounds.

### Changed

- The showcase separates runtime actions from workspace utilities, keeps the model and result panels collapsible, and expands with the browser width.

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

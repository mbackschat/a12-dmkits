# Grammar-production ledger — the condition grammar, classified

The classified inventory of every **parser production** in the kernel's condition grammar (`GrammarOnly_ConditionParser.g4`, kernel-tool source tree — consult-only). This is instrument 7 of the anti-whack-a-mole discipline: keywords already have two-way parity (`KeywordParityTest` / `KeywordReverseParityTest`) and constructs have reverse parity (`KernelConstructParityTest`), but a **syntax shape** — an optional clause, an alternative form, a new production — can appear without any new keyword or enum member. This ledger closes that blind spot at the production level.

**CI lock (opt-in):** `GrammarProductionLedgerTest` reads the grammar from the sibling kernel checkout (`../a12-kernel`, overridable via `-Da12.kernel.src.dir=…`; **skips when absent**, like the other sibling-gated harnesses), extracts every production (comment-stripped, so doc prose can't fake a rule), and fails on any production this table misses or any stale row. A kernel bump that adds a syntax form lands in the queue loudly.

Classifications:

| classification | meaning |
|---|---|
| **rendered** | the production's constructs are authorable via the typed builder and readable by the adapter (catalog operators named where useful) |
| **plumbing** | a sub-production serving a rendered family (operands, separators, name/path parts) — no independent operator identity |
| **entry** | a parser entry point / snippet wrapper |
| **legacy** | H-model / multi-form (Vordruck/USB) / multi-row / bounded-iteration constructs — same exclusion reasons as the construct-parity exclusions; unreachable from rulekit-authored new-world models |
| **directive** | a raw-DSL form rulekit never authors but whose laws are probe-verified and locked at the validate tier |
| **queued** | classification or read-tolerance not yet verified — the work-list |

## The ledger (kernel 31.1.1 — 122 productions)

### Conditions (the `bedingung` tier)

| production | classification | notes |
|---|---|---|
| `bedingung` | rendered | `And`/`Or` composition; ANY number of parts per bracket level, no mixed `And`/`Or` there (the explicit-parentheses law). *(Corrected 2026-09-13: this read "max three parts per bracket level", which the production's two labels suggest and its starred inner group refutes — measured in [KF301](KERNEL-FINDINGS.md#kf301).)* |
| `teilBedingung` | rendered | one branch, optional surrounding `;;` comments — read-tolerated and **authored via the `Comments` side-channel** (`before`/`after`); the leading comment is preserved on write-back (KERNEL-FINDINGS "Condition comments") |
| `geklammerteBedingung` | rendered | parenthesized sub-condition |
| `einfacheBedingung` | rendered | the dispatch over all condition families |
| `logOp` | rendered | `And`/`Or` (case variants read-only) |
| `vergleichsBedingung` | rendered | the comparison condition (operand `vglOp` operand) |
| `vglOp` | rendered | dispatch: equality / ordering / pattern / tolerance |
| `equalityVgl` | rendered | `==` / `!=` |
| `orderingVgl` | rendered | `<` / `<=` / `>` / `>=` |
| `patternVgl` | rendered | `PatternMatched` / `PatternViolated` |
| `toleranceVgl` | rendered | `DiffersWithToleranceRange{1,2,5,10}` |
| `feldListenBedingung` / `feldListenBedingungName` | rendered | the field-list quantifier family (`AllFieldsFilled`, …, `FieldValuesNotUnique`) |
| `kontextListenBedingung` / `kontextListenBedingungName` | rendered | the group quantifier family (`GroupFilled`, …) |
| `feldUndWerteListenBedingung` / `feldUndWerteListenBedingungName` | rendered | single-field value-list membership (`FieldValueIncludedInValueList`, …) |
| `feldListeUndWerteListenBedingung` / `feldListeUndWerteListenBedingungName` | rendered | field-list value-list quantifiers (`AtLeastOneFieldValueIncludedInValueList`, …) |
| `fieldAndValueListCondition` / `fieldAndValueListConditionName` | rendered | the EN-named value-list condition variant |
| `wiederholungBedingung` / `wiederholungBedingungName` | rendered | `RepetitionNotUnique` (+ `In` keys, `@From` scope) |
| `applikationsBedingung` / `applikationsBedingungName` | rendered | `CustomCondition` |
| `dateValidityCondition` / `validityConditionName` | rendered | `Valid`/`Invalid` over a `Date(…)` construction |
| `predefinedTypeValidityCondition` | rendered | `Valid(Field, "Type")` — the custom-field-type validity (`ValidType`/`InvalidType`) |

### Operations (the expression tier)

| production | classification | notes |
|---|---|---|
| `operation` | rendered | arithmetic expression (`+ - * /`, parentheses) |
| `einfacheOperation` | rendered | one arithmetic operand |
| `operationListe` | rendered | operand lists (e.g. for `Min`/`Max`/`RoundDown`) |
| `feldWertOp` | rendered | the bracketed field value `[path]` |
| `feldOp` / `feldOpName` | rendered | `AbsValue` (`AbsWert`), `Length`, `FieldValueAsNumber`, `FieldValueAsString` |
| `feldOperOp` / `feldOperOpName` | rendered | field-level rounding (`RoundDownValue`, `RoundUpValue`, `RoundAccountingValue`) |
| `feldListenOp` / `feldListenOpName` | rendered | list aggregates: `MaxValue`, `MinValue`, `Sum`, `SumOfProducts`, `FirstFilledValue` |
| `setOperOp` / `setOperOpName` / `setOperation` | queued | kernel 31.1.1's term sum `SumOfTerms` (`SummeVonTermen`): an iteration entity, then one arithmetic term evaluated per repetition; admission classes in [KF656](KERNEL-FINDINGS.md#kf656). The renderer quotes both spellings in entity paths |
| `feldListenOperOp` / `feldListenOperOpName` | rendered | operand-list math (`Min`/`Max`/`Abs` over expressions) |
| `feldListenAnzahlOp` / `feldListenAnzahlOpName` | rendered | `NumberOfFilledFields`, `NumberOfDifferentValues` |
| `anzahlWertOp` / `anzahlWertOpName` | rendered | `NumberOfValueInFields` (`AnzahlWertInFeldliste`) |
| `groupOp` / `groupOpName` | rendered | `CurrentRepetition`, `NumberOfFilledGroups` |
| `rundenOp` / `rundenOpName` | rendered | `RoundDown`/`RoundUp`/`RoundAccounting` (expr, digits) |
| `rundenOperOp` / `rundenOperOpName` | rendered | the rounding variants over operand lists |
| `datumAddOp` / `datumAddOpName` | rendered | `AddDays/-Months/-Years/-Hours/-Minutes/-Seconds` |
| `datumDiffOp` / `datumDiffOpName` | rendered | `DifferenceIn{Days,Months,Years,Hours,Minutes,Seconds}` |
| `datumExtractOp` / `datumExtractOpName` | rendered | `DayFromDate`, …, `HoursFromTime`, `TimeFromDateTime`, … |
| `datumBereichOp` / `datumBereichOpName` | rendered | `DateRangesOverlap`, `AtLeastOneDateRangeOverlaps`, `StartOfDateRange`/`EndOfDateRange` |
| `datumKonstrukt` | rendered | `Date(d, m, y[, …])` construction |
| `dateTimeKonstrukt` | rendered | `DateTime(…)` construction |
| `timeKonstrukt` | rendered | `Time(…)` construction |
| `datumBereichKonstrukt` | rendered | `DateRange(start, end)` — DATE_RANGE computation value and `==`/`!=` condition operand; ordering and overlap nesting rejected |
| `feldOpMitParams` / `feldOpMitParamsName` | rendered | `RangeAsNumber`/`RangeAsString` (`BereichAlsZahl`/`BereichAlsString`, 1-based interval law pre-checked) |
| `feldOpMitDatumSpecial` | rendered | `ValueAsDate(field, FirstDay\|LastDay)` — partially-known-date coercion |
| `tagSpec` | plumbing | `FirstDay` / `LastDay` (`ErsterTag`/`LetzterTag`) argument of `ValueAsDate` |
| `konstanteReferenzjahr` | rendered | `BaseYear` (`Referenzjahr`) |

### Values, paths & specifiers

| production | classification | notes |
|---|---|---|
| `konstante` | plumbing | constant operand (number/string/date literal dispatch) |
| `commonKonstante` | plumbing | shared constant forms |
| `number` | plumbing | numeric literal |
| `numberListe` | plumbing | numeric value list |
| `stringListe` | plumbing | string value list |
| `feldOderString` | plumbing | field-or-string operand (pattern comparisons) |
| `wertListenSpezifier` | plumbing | value-list source: entity list or string list (field-valued lists ride here) |
| `wertListenSpezifier2` | plumbing | the second value-list form |
| `feldUndStringListenSpezifier` | plumbing | mixed field/string list specifier |
| `feldWertOpListe` | plumbing | bracketed-field list |
| `inOp` | plumbing | the `In` separator (case variants) |
| `toOp` | plumbing | the `Zu` (to) separator |
| `idName` | plumbing | dotted name part |
| `nameOhnePunkt` | plumbing | name segment (incl. quoted form) |
| `idNameRef` | plumbing | path segment with optional `*` (the per-segment star law, KERNEL-FINDINGS §9) |
| `entity` | plumbing | entity path |
| `entityListe` | plumbing | entity list (quantifier/aggregate scopes) |
| `entitySpec` | plumbing | entity + specifiers (`Having` filter, semantic index, `$` correlation ride here) |
| `semanticIndex` | rendered | `For "value"` (constant form authored; entity/`$` forms read — kernel-delegated rest) |
| `relativeUp` / `relUp` / `oneUp` | rendered | relative `../` path segments (renderer emits plain `../`; a starred `..` is unrepresentable) |
| `datumParam` | plumbing | `Date(…)` component (field / constant / extraction) |
| `datumAngabe` / `datumAngabe2` | plumbing | date argument forms (bare entity vs literal) |
| `datumBereichAngabe` | plumbing | date-range argument |
| `regelKontextEntity` | directive | `RuleGroup` (`RegelKontext`) — the rule's containing group as an entity; works in group predicates (counts as referencing the error field), a star on it draws `MVK_INVALID_WILDCARD_FOR_RULEGROUP`. rulekit renders qualified paths instead (probe-verified 2026-06-10; locked: `validate.laws.DirectiveLawsTest`) |
| `preamble` | directive | `@SuppressWarning(NAME)` (`@UnterdrueckeWarnung`) — suppresses exactly one warning, `MVK_INVALID_COMPARE_DEC_PLACES` (the equality scale gate's escape hatch); any other name rejected (locked: `validate.laws.DirectiveLawsTest`; MVK ledger row `MVK_INVALID_SUPPRESSED_WARNING`) |

### Entry points

| production | classification | notes |
|---|---|---|
| `bedingung_start` | entry | full-condition parse (preamble + condition + EOF) |
| `bedingungSnippet` | entry | condition snippet parse |
| `operationSnippet` | entry | operation snippet parse |

### Legacy / out-of-scope (parity-excluded reasons)

| production | classification | notes |
|---|---|---|
| `anzahlAngegebenOpHModel` / `anzahlAngegebenOpHModelName` | legacy | H-model count op |
| `entityHModel` | legacy | H-model entity path |
| `feldListenOpHModel` / `feldListenOpHModelName` | legacy | H-model list ops |
| `feldOpHModel` / `feldOpHModelName` | legacy | H-model field ops |
| `idNameRefHModel` | legacy | H-model path segment |
| `spezifierHModel` | legacy | H-model specifier |
| `formularBedingung` / `formularBedingungName` | legacy | multi-form (Vordruck) conditions — parity-excluded |
| `formularSpec` | legacy | form specifier |
| `formularEbeneLfdNr` | legacy | form-level sequence number |
| `formularEbenenSpecHModel` | legacy | H-model form-level spec |
| `lfdNrSpezifier` | legacy | sequence-number specifier (multi-form) |
| `usbSpezifier` | legacy | `@*` USB specifier (multi-form) |
| `extmpKonstanteUSB` | legacy | `AnzahlAngegebeneUsb` constant (multi-form) |
| `zeilenBedingung` / `zeilenBedingungName` | legacy | multi-row-field conditions (rows are legacy-only, `MVK_ROW_INVALID`) |
| `zeileSpezifier` | legacy | row specifier |
| `einschraenkung` | legacy | bounded iteration range `(1-3)` — unrepresentable by design (`MVK_ITERATION_RESTRICTION_IN_A12_MODEL` erased) |

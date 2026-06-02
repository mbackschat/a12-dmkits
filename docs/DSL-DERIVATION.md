# How the builder DSL is derived (and the grammar's role)

A precise answer to "where does rulekit's builder structure come from — the ANTLR grammar?": **No, not by code-generation from the grammar.** The builder is **hand-curated**, jOOQ-style, with the **operator catalog as its spine**. The kernel's ANTLR grammar is involved, but **indirectly** and in three specific roles. This is a deliberate decision (proposal D7, §18.3, §6), not an accident of expedience.

## The short version

```
   ANTLR grammar (.g4)                 ← the kernel's INPUT language definition
        │  (the kernel build generates from it)
        ├──> LexerTerminals_{en,de}     ──┐  exact keyword strings + token constants
        ├──> the kernel PARSER           │  │
        └──> (analysed by humans into)   │  │
             KERNEL-GRAMMAR.md, BA guide,│  │
             KERNEL-SEMANTICS.md         │  │
                                         ▼  ▼
   rulekit OPERATOR CATALOG  ◄───────────────  hand-curated from those, keyword-parity-checked
   (operators.json: id, en/de keywords, operand/return kinds, meaning, constraints, examples)
        │ drives
        ├──> typed AST nodes (sealed Condition/Calculation — mirror §5 families + §5b kinds)
        ├──> fluent handles (BoundModel.num/str/… — typed by ValueKind read from the model)
        └──> renderer (AST → DSL text; worded keywords looked up from the catalog by id+language)
                                         │ emits text
                                         ▼
                          the kernel PARSER (built from the grammar) ── checkConsistency ──► ground truth
```

The grammar is **upstream of rulekit's inputs** (it shapes the catalog and the docs we curate from) and **downstream of rulekit's outputs** (the parser judges what we emit). rulekit never parses the `.g4` itself.

## What rulekit's structure is actually derived from

1. **The operator catalog (`src/main/resources/catalog/operators.json`)** is the single source of operator data: each entry's `id`, EN/DE `keywords`, `operands`/`returns` kinds, `meaning`, `constraints`, `gotchas`, and CI-checked examples. Everything operator-specific lives here as data, nothing hard-coded in Java (so a future TS twin re-reads the same catalog rather than porting Java logic).
2. **The typed AST** (`sealed interface Condition` / `Calculation` + records) mirrors the kernel's **construct families** from proposal §5 (predicates, operators, functions, …) and the **value kinds** from §5b (number-with-scale, string, enum, date-family, …). A node exists per construct shape; the renderer dispatches over the closed set with an exhaustive `switch`.
3. **The fluent handles** (`BoundModel.num/str/…` → `NumberRef`/`StringRef`) are typed by the `ValueKind` **read from the bound `DocumentModel`** (the model index, F0), so only type-legal operations compile/build — e.g. there is no `<` on a `StringRef`.

So the *shape* is: catalog (the operator vocabulary) × model index (the field types) → typed handles → AST → rendered text.

## The grammar's three (indirect) roles

1. **Keyword parity — lexer terminals.** The kernel build generates `LexerTerminals_en.java` / `_de.java` (in an internal parser package) from the grammar: each maps a keyword string to a shared `Base_ConditionLexer` token constant. rulekit's catalog keywords are taken from / checked against these (e.g. verified `And→Und`, `Length→Laenge`, `FieldFilled→FeldAngegeben`). The planned **coverage test** (proposal §9/N3 — not yet built; see KERNEL-INTEGRATION-NOTES) reconciles catalog ↔ terminals *per token constant*, so the catalog can't silently omit or misspell a keyword in either language.
2. **Semantics — the analysis docs.** `a12-kernel/KERNEL-GRAMMAR.md`, the BA validation-language guide, and `a12-kernel-demo/KERNEL-SEMANTICS.md` are human distillations of the grammar **and the parser's behaviour** (the `MVK_*` failure→fix taxonomy, the type-comparability matrix, null/relevance semantics). The grammar alone gives *syntax*; these give the *type legality and gotchas* that the grammar does not express (the kernel has **no readable type tag** — §5b — so the comparability rules can only be learned from the docs + `MVK_*` catalog, never from the grammar productions). The catalog structures that prose into per-operator data.
3. **Ground truth — the parser as validator.** The kernel parser (built from the grammar) is the final arbiter. rulekit renders DSL **text** and feeds it to `IDocumentModelService.checkConsistency` (F4, §11); N7 runs every catalog example through it. So the grammar **validates** rulekit's output rather than **generating** rulekit's input — exactly the jOOQ relationship to a SQL dialect.

## Why hand-curated, not generated from the grammar (D §18.3)

- **Ergonomics.** A builder mechanically generated from grammar productions is low-level and awkward; the fluent, combinator-friendly surface needs human design.
- **Semantics aren't in the grammar.** Operand-type legality, decimal-scale discipline, the error-scenario inversion (`true` ⇒ invalid), null handling, "no `Not`" — these are parser/BA semantics, not syntactic productions. The whole value of a typed front-end (make illegal states unrepresentable) lives in exactly the layer the grammar omits.
- **Drift is controlled by tests, not by generation.** Instead of generating-to-stay-in-sync, rulekit stays honest with two checks against the real grammar/engine: the **keyword-parity coverage test** (catalog ↔ lexer terminals) and **N7** (every catalog example run through the real kernel). Generation would couple us to grammar internals; testing keeps the public, polyglot contract.

## The one place the grammar *would* be consumed directly (deferred)

The **text → AST round-trip** (#3, proposal §12, Phase 5) — reading existing DSL strings back into rulekit's AST — is the only feature that would consume the kernel's ANTLR **parse tree** programmatically (map parse-tree nodes → AST nodes). It is deliberately deferred and gated on a concrete legacy-editing need. Authoring/rendering (the current direction) never needs it.

## Worked example: `<=` and `Length`, end to end

- **Catalog:** `LessThanOrEqual` (`{en:"<=", de:"<="}`, operands `orderable×orderable`, returns `boolean`, constraint "ordering only for numbers/amounts/dates"); `Length` (`{en:"Length", de:"Laenge"}`, operand `string`, returns `number`).
- **AST:** `ComparisonCondition(left, CompareOp.LE, right)`; `LengthOf(FieldRef)` — a numeric `Calculation`.
- **Handles:** `m.num(path)` yields a `NumberRef` (so `<=` is offered); `m.str(path).length()` yields a `NumberExpr` over `LengthOf` (so `Length(...)` is only reachable from a string field). The model's `ValueKind` gates which handle you get — `m.num(stringPath)` fails fast.
- **Render:** comparison symbols are intrinsic (`<=`, identical in every language); the worded `Length`/`Laenge` is looked up from the catalog by id for the chosen `ConditionLanguage`.
- **Validate:** the rendered text (e.g. `Length(/Order/CustomerName) > 10`) is embedded as a rule and confirmed by the kernel parser (N7 proved this exact `Length(...)` form is accepted; the scale-mismatch `[Quantity] == [UnitPrice]` is rejected with `MVK_INVALID_COMPARE_DEC_PLACES`).

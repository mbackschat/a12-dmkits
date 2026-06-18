# a12-dmkits

Author, read, and safely edit the **rules** and **structure** of A12 Kernel document models — as typed Java code, or through a self-describing command-line tool. Every result is checked by the **real A12 kernel**, so what you produce is *accepted*, not merely plausible. JSON in, JSON out.

> **Not an official A12 artifact.** A personal exploration, not affiliated with or endorsed by mgm — currently a private project, built using GPT 5.5. Shipped code binds only to the kernel's **public** OSS surface — no internal systems. (An opt-in, Java-only adapter module may read the kernel's internal AST tier under strict guardrails.)

---

## The idea — typed, model-bound, kernel-confirmed

An A12 document model carries **validation rules** and **computations** whose logic is an expression written in the kernel's condition language. For example, *"a delivery date must not be before the order date"* is stored as a condition string:

```
AllFieldsFilled(OrderDate, DeliveryDate) And DifferenceInDays(OrderDate, DeliveryDate) < 0
```

Writing those strings by hand is fragile. A mistyped field path, a wrong operator, the wrong decimal scale, or — most insidious — **inverted polarity** (a rule's condition is true on the *violation*, not on the requirement) slips through unnoticed until the kernel runs, if it's caught at all.

**rulekit builds the condition as typed code, bound to your model** — so whole classes of mistake become impossible or caught immediately:

```java
BoundModel m = BoundModel.ofJson(Files.readString(modelPath));   // bind a model to the real kernel

GroupRef  addons = m.group("/Subscription/Addons");              // resolve each element ONCE …
NumberRef fee    = m.num("/Subscription/Addons/MonthlyFee");     // … then reuse the typed ref — no repeated paths

Rule rule = m.rule(addons)
    .name("FeeCapRule")
    .errorField(fee)                                            // fields come OFF the model …
    .when(fee.gt(1000))                                         // … a wrong path can't compile
    .code("FEE_CAP").severity(Severity.ERROR)
    .message(en("Monthly fee is too high."), de("Monatsgebühr ist zu hoch."))
    .build();

String dmJson = m.add(rule).toJson();   // re-validated through the kernel; ready to write back
```

Or hold an **editor** on the group and navigate relatively — the model path is typed once, and the rule authors in place against it:

```java
EditSession s      = EditSession.read(dmJson);
GroupEditor addons = s.group("/Subscription/Addons");           // a session-bound editor on the group
NumberRef   fee    = addons.num("MonthlyFee");                  // a typed ref off the editor — relative, no full path
addons.addRule(r -> r                                           // pre-scoped to /Subscription/Addons
        .name("FeeCapRule").errorField(fee)
        .when(fee.gt(1000))                                     // same typed condition, same kernel gate
        .code("FEE_CAP").severity(Severity.ERROR)
        .message(en("Monthly fee is too high."), de("Monatsgebühr ist zu hoch.")).build());
String updated = s.validate().toJson();                         // one kernel gate
```

Three things make this more than a string builder:

- **The model is the type system.** `m.num(...)`, `m.str(...)`, `m.date(...)`, `m.group(...)` return handles bound to real fields, so a path that doesn't exist (or a number operator on a date) fails *before* you ever reach the kernel. (That's a **path lookup** — one of two ways to name a field; the other is a session **editor** for the read→edit→reference flow, below.)
- **It renders to the exact DSL the kernel expects** — English keywords by default, **German** on demand (`And`/`Und`, `Today`/`Heute`, …) — and the kernel confirms the rendered rule, so "valid" means *the engine accepts it*.
- **It runs in reverse.** rulekit can **read an existing condition string back** into the same typed form — to explain it, analyze it, or re-express it with a reviewable diff. Anything it doesn't yet model is preserved *verbatim*, never dropped.

**modelkit** applies the identical idea to the model's **structure** — fields, groups, type definitions, includes, document config — emitting DM-JSON the kernel accepts.

---

## Two ways to use it

| You are… | Use | Why |
|---|---|---|
| a **developer**, editing models in your own JVM project | **rulekit / modelkit** as a library | typed builders, kernel-checked, in your own code & compiler |
| a **modeller / BA**, or an **LLM agent** | **`dmtool`**, the CLI | verbs + JSON I/O, single native binary, self-describing — no Java toolchain to install |

*(Java first; a TypeScript twin may follow.)*

---

## rulekit — the library

### Referencing a field — by path, or through an editor

Two ways to name the field a rule talks about; they return the **same pure refs**, so you blend them as fits:

| Style | Looks like | Reach for it when |
|---|---|---|
| **By path** | `m.num("/Order/Discount")` | referencing an **existing** element while authoring against a loaded model — the common case |
| **Through an editor** | `s.group("/Order").num("Discount")` | the read→**edit**→reference flow: a ref to a field you *just created*, or relative navigation, without re-typing paths — see *Both kits together* below |

Neither is "older": a path lookup is the foundation; the editor adds relative navigation, refs that flow out of edits, and `m.rule(editor)` for when structure and rules move together. The examples below use whichever fits — and [mix both](#both-kits-together--editsession-one-model-one-gate) when a rule spans a just-added field and an existing one.

### Author (a quick start)

The rule above is the whole pattern: pick the **scope group**, the **error field**, the **condition** (true on the violation), a **code** and per-locale **messages**, then `build()`. A **computation** (a field whose value is derived from others) reads the same way:

```java
Computation comp = m.computation(m.num("/Subscription/Billing/EffectiveFee"))
    .name("EffectiveFeeComp")
    .calc(m.num("/Subscription/Billing/BaseFee").value())     // EffectiveFee := BaseFee
    .message(en("Effective fee equals the base fee."), de("Effektivgebühr = Basisgebühr."))
    .build();
```

### Interpolated error messages

An error message can splice in the **runtime value**, the **label**, or the **row number** of a field/group through typed helpers — each takes a *ref*, so it can't name a field that doesn't exist. The kernel substitutes the token when it renders the message for a failing document:

| Helper | Renders the token | Substituted with… |
|---|---|---|
| `fieldValue(ref)` | `$/Order/Items/Count.value$` | the field's runtime **value** |
| `fieldLabel(ref)` | `$/Order/Items/Count$` | the field's localized **label** |
| `repetitionIndex(groupRef)` | `$#/Order/Items$` | the repeatable group's 1-based **row number** |

```java
import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.*;   // en, de, fieldValue, fieldLabel, repetitionIndex

NumberRef count = m.num("/Order/Items/Count");
GroupRef  items = m.group("/Order/Items");
// renders, e.g., "Row #2: Count (now 0) must be positive."
m.rule(items).name("CountPositive").errorField(count)
    .when(count.filled().and(count.le(0))).code("COUNT_POSITIVE").severity(Severity.ERROR)
    .message(en("Row #" + repetitionIndex(items) + ": " + fieldLabel(count.field())
            + " (now " + fieldValue(count.field()) + ") must be positive."))
    .build();
```

The CLI `rule add` / `apply` message path accepts the same `$…$` tokens as plain text — the helpers are just the typed, ref-checked way to write them.

### What you can express

The condition language is rich; a flavour of what the typed builder covers (each renders to kernel-valid DSL):

| Want to say… | DSL it produces |
|---|---|
| a field is present / absent | `FieldFilled(OrderDate)` · `FieldNotFilled(DeliveryDate)` |
| compare a field to a value **or another field** | `[Quantity] > 0` · `[MinQty] <= [MaxQty]` |
| enums by their stored value (incl. category navigation) | `[/Subscription/Tier] == "PRO"` · `[Tier -> Pricing] == "PAID"` |
| dates & arithmetic | `DifferenceInDays(OrderDate, DeliveryDate) < 0` · `[OrderDate] >= Today` |
| aggregates over a repeating group | `Sum(Items*/Amount) > 500` · `MaxValue(Items*/Price)` |
| string patterns (portable regex) | `PatternViolated(PostalCode, "[0-9]{5}")` |
| "for every add-on row, the fee is present" | `GroupFilled(/Subscription/Addons) And FieldNotFilled(MonthlyFee)` |

Browse the full, self-verifying catalogue (every operator with its meaning, examples, and **gotchas**) via the CLI's `operators` verb, or in the [API guide](docs/API-GUIDE.md).

### Read a foreign rule back

```java
KernelAdapter adapter = KernelAdapter.of(m);
ReadResult read = adapter.read("/Order/DeliveryNotBeforeOrder");   // text → typed AST, tolerant
// → ReadResult.Converted(condition)  … or  ReadResult.Opaque(text, reason)  (preserved verbatim)
```

### Explain, navigate, find

```java
ModelContext ctx = new ModelContext(m);
ModelCard card  = ctx.describe();                 // every field + kind + enum domain + group repeatability
RuleExplanation why = ctx.explain(condition);     // what it checks, and WHEN it fires (polarity made explicit)
List<FieldMatch> hits = ctx.find("birthdate");    // resolve a fuzzy name to model fields
Set<String> users = adapter.whereUsed("/Customer/BirthDate");   // which rules reference a field
m.fields().filter(f -> f.annotation("audited").isPresent());    // find fields by annotation (GroupInfo too)
```

### Modify by re-expression, with a diff — and render to German

```java
new DslRenderer(ConditionLanguage.DE).render(condition);   // German keywords: Und, Heute, AlleFelderGefüllt …
```

Re-expressing an existing rule produces a **`RenderedDiff`** that discloses any lossiness *up front* — you review before you commit. The rule's **leading `;;` comment is preserved by default** across the loop (only inline/trailing comments are re-rendered away, and the diff says so).

### Comments — author once, preserved across the loop

Conditions can carry `;;` line comments (concrete syntax the kernel tolerates). Author them with the typed builder, and a comment-less `modify` keeps them:

```java
NumberRef fee = m.num("/Subscription/Addons/MonthlyFee");
m.rule(m.group("/Subscription/Addons")).name("FeeCap").errorField(fee)
        .when(fee.filled().and(fee.gt(1000)))
        .comment("cap the monthly fee per policy")     // ← rendered as a leading ;; line, kernel-tolerated
        .code("FEE_CAP").severity(Severity.ERROR)
        .message(en("Monthly fee is too high."), de("Monatsgebühr ist zu hoch."))
        .build();
```

The CLI mirrors it: a `comment` key on `rule add` and `computation add`, plus `rule modify --comment`. In the typed library, computations carry comments on operations and preconditions too.

### Validation-pattern catalogue

The recurring idioms are a **catalogue** — `PatternCatalog.load().byId("date-order")` — across three kinds: **rule** idioms (date-order, required-when, mutually-exclusive, at-least-one-of, sum-of-line-items), the **computation** idiom `tiered-amount`, and **field-constraint** idioms (bounded-number, formatted-string, value-set-enum — the field-level alternatives the `seeAlso` bridge names). Each builds its artifact through the **typed DSL**, so it's correct by construction (a rule's right polarity + referenced error field, a computation's mutually-exclusive precondition table, or a typed field config). The library returns a sealed `RuleInstantiation` | `ComputationInstantiation` | `FieldInstantiation`; the CLI `patterns` verb scaffolds + `--apply`s them. Each entry's example is kernel-validated in CI, so the catalogue can't go stale. Full API + the typed-vs-data design: [API guide](docs/API-GUIDE.md).

More rulekit idioms and the design rationale are in the [API guide](docs/API-GUIDE.md).

---

## modelkit — the library

modelkit authors a model's **structure** the same way rulekit authors rules — typed, fluent, kernel-confirmed. Build a model from scratch:

```java
DocumentModelDraft order = DocumentModelDraft.named("order")
    .locale("en_US")
    .rootGroup("Order", g -> g
        .field("OrderDate", FieldKind.DATE, Requiredness.REQUIRED)
        .numberField("Quantity", n -> n.fractionDigits(0).min("1"))   // a whole number ≥ 1
        .enumField("Priority", e -> e.value("LOW").value("HIGH"))
        .group("Items", items -> items
            .repeatable(99)                                           // a repeating line-item group
            .field("Sku", FieldKind.STRING)
            .numberField("Amount", n -> n.fractionDigits(2))))        // a money amount, scale 2
    .build();

String dmJson = order.toJson();   // DM-JSON the kernel accepts
```

**What it covers** — field kinds (string with length/pattern/suggestions/line-breaks/sorting, number with fraction-digit range/min/max/zero-not-allowed/leading-zeros/unit (amount·percent·permille), date / time / date-range, boolean, confirm, enum with values + categories + sorting + invalid-value message, and a **custom** field type), **element metadata** (per-locale internal/external **descriptions** + name/value **annotations** on any field/group/rule/type-def, field global/transient flags + helper text, and a model-level **comment**), **repeatable groups** (with index fields), the **Multi-Select** and **Attachment group templates** (`multiSelectGroup(...)` — a repeatable enum/string selection list; `attachmentGroup(...)` — the kernel's canonical file-attachment composition, generated whole), reusable **type definitions** (including a **type-def-only model** other models *import* via `typeDefModel(...)` + `importTypeDefs(...)`), **includes** (mount another model under a group, optionally repeatable or excluding the included model's rules/computations), and **document config** (locales, decimal separator, time zone, base year, model-level labels + annotations, plus a runtime-enforced supported-character set). To edit the structure of an *existing* model — add/remove fields & groups, declare type definitions, change config — use `EditSession` (below).

**Illegal states are unrepresentable.** The builder rejects the mistakes the kernel would — a repeatable group with `max < 2`, a field referencing an undeclared type definition — *as you build*, not after a round-trip; and the kernel still has the final say (every result re-binds through `checkConsistency`).

## Both kits together — `EditSession`: one model, one gate

This is where modelkit and rulekit meet — **structure edits and rule edits on one in-memory model, behind a single kernel gate**. The north-star flow: read a model, make a series of structure *and* rule edits (a rule may reference a field added earlier in the same session), validate **once**, write back (intermediate invalid states are fine — only the final result must satisfy the kernel):

The idiom is the **editor**: you hold an editor on a group rather than re-typing string paths — structure edits chain, a typed ref to a just-created field comes from relative navigation, and rules author in place against it.

```java
EditSession s = EditSession.read(dmJson);
GroupEditor order = s.group("/Order");                       // a session-bound editor on /Order
order.addField("Discount", FieldKind.NUMBER);                // structure edit (modelkit) — chains
NumberRef discount = order.num("Discount");                  // a typed ref to the field just added
order.addRule(r -> r                                         // a TYPED rule (rulekit), pre-scoped to /Order
        .name("DiscountNotNegative").errorField(discount)
        .when(discount.filled().and(discount.lt(0)))         // the violation: a discount below zero
        .code("DISCOUNT_NEGATIVE").severity(Severity.ERROR)
        .message(en("Discount must not be negative.")).build());
String updated = s.validate().toJson();                      // one kernel gate
```

**Refs are the currency.** `order.num("Discount")` resolves *only because* `addField` ran first — and it returns a typed ref, not a string path you re-type. Rules author in place (`order.addRule(r -> …)`, the builder pre-scoped to the group) or take the editor straight as a scope (`m.rule(order)`); either way the immutable AST holds **only the pure ref, never the session**, so a rule can't leak an effect. `GroupEditor` also nests (`addGroup(name, sub -> …)`) and carries the same per-type field builders as the from-scratch builder (`order.numberField("Surcharge", n -> n.fractionDigits(2))`, `enumField`, `dateField`, … — a *configured* field mid-edit, byte-identical to one built from scratch); `FieldEditor` re-types/labels/annotates a field; `RuleEditor` / `ComputationEditor` modify or remove by held reference — a rule's condition, **or its severity / messages in place** (`s.rule(name).severity(WARNING).messages(en("…")))`). `EditSession` also carries the **reference-preserving refactors** — `renameField`/`renameGroup`/`moveField`/`moveGroup` rewrite every reference to the element (via the kernel's `MoveSupportDM`) and re-validate, so a rename or relocation never dangles a referrer. Design + rationale: [API guide §5c](docs/API-GUIDE.md).

**Blend both as a developer would** — the editor for a field you just added, a path lookup for one that already exists, in the same condition (they are the same pure ref):

```java
GroupEditor billing = s.group("/Subscription/Billing");
billing.addField("Surcharge", FieldKind.NUMBER);                       // NEW field — via the editor
NumberRef surcharge = billing.num("Surcharge");                        // editor relative ref
s.addRule(m -> m.rule(billing.ref()).name("SurchargeUnderBase").errorField(surcharge)
        .when(surcharge.filled().and(surcharge.gt(m.num("/Subscription/Billing/BaseFee"))))  // ← path lookup, existing field
        .code("SURCHARGE_OVER_BASE").severity(Severity.ERROR)
        .message(en("Surcharge must not exceed the base fee.")).build());
```

The flat session verbs are still there for a quick one-off — `s.addField(path, …)`, `s.addRule(m -> m.rule(m.group(path)) …)` — and a raw-string condition overload backs the CLI's `apply`/spec path (conditions arrive as text), but it's not the idiom for library code.

More library idioms and the design rationale live in the [API guide](docs/API-GUIDE.md); every doc is indexed in [`docs/`](docs/README.md).

---

## `dmtool` — the CLI

Same engine, no Java required. Every command is `dmtool -m <model> <target> <op> [args]` and prints a uniform JSON **result envelope** (`{ok, valid, outcome, data, diagnostics[], …}`). It is **self-describing**, so a cold agent (or you) can discover the whole surface from the binary alone.

### Use it inside your coding agent (Claude Code, Codex)

`dmtool` is **LLM-agent-first**. The primary way to use it is to install it as a **plugin** for your coding agent: the plugin downloads the right native binary for your OS (anonymous, checksum-verified) and loads a **rule-authoring skill** that teaches the judgment the tool's help can't — condition *polarity*, error-field paths, iteration scope. The same canonical skill backs both agents.

```text
# Claude Code
/plugin marketplace add mbackschat/a12-dmtool-releases
/plugin install dmtool

# OpenAI Codex
codex plugin marketplace add mbackschat/a12-dmtool-releases
codex plugin add dmtool
```

The plugins, the per-OS binaries, and full install/licensing details live in the public release repo — **[`a12-dmtool-releases`](https://github.com/mbackschat/a12-dmtool-releases)**. (This source repo holds the plugin *source* under [`plugin/`](plugin/) (Claude Code) and [`plugin-codex/`](plugin-codex/) (Codex); they ship to the mirror at release.)

### Install the binary directly (no agent)

For terminal use without an agent, grab the native binary from the [`a12-dmtool-releases`](https://github.com/mbackschat/a12-dmtool-releases) Releases and verify its checksum:

```sh
# macOS (Apple Silicon)
base=https://github.com/mbackschat/a12-dmtool-releases/releases/latest/download
curl -fsSLO "$base/dmtool-macos-arm64"
curl -fsSLO "$base/SHA256SUMS"
shasum -a 256 -c SHA256SUMS --ignore-missing             # verify integrity
chmod +x dmtool-macos-arm64
xattr -d com.apple.quarantine dmtool-macos-arm64 2>/dev/null || true   # only if a browser download was quarantined
sudo mv dmtool-macos-arm64 /usr/local/bin/dmtool         # put it on PATH as `dmtool`
dmtool --version
```

Linux/Windows binaries and Homebrew/Scoop come later; the binary is **EUPL-1.2** (it embeds the A12 Kernel — see the mirror's `LICENSE`/`NOTICE`). The runtime-evaluation verbs (`model eval`, `rule test`, `model compute`) need a JVM and are **not** in the native binary.

### Discover the tool itself

```sh
dmtool manifest                       # every verb × parameter + a worked example per verb, in one call
dmtool operators                      # the DSL operator catalogue …
dmtool operators GroupFilled          #   … or one operator in full (meaning, examples, gotchas, OPPOSITE)
dmtool patterns                       # the validation-idiom catalogue (date-order, required-when, …) …
dmtool patterns date-order            #   … or one idiom in full (params, polarity, skeleton, example)
dmtool diagnostics                    # the diagnostic-code catalogue …
dmtool diagnostics RK_NO_SUCH_FIELD   #   … or one code in full (meaning + fix) — so a `code` you got back is explorable
dmtool schema rule add                # the exact JSON shape a verb consumes / returns
dmtool --version                      # this build + the kernel it targets (see "compatibility" below)
```

### Start a model from scratch

```sh
dmtool model new --id orders --locale en_US --root Order > orders.dm.json   # a new, empty, kernel-valid model
dmtool -m orders.dm.json field add --group /Order --name Reference --kind STRING   # …then populate it
dmtool -m orders.dm.json field modify postal.spec.json   # re-type a field in place (e.g. add a STRING pattern) — no delete-and-recreate
```

`model new` is the one model-*originating* verb (no `-m` — it makes one); every other verb edits an existing `-m` model. What it emits is kernel-checked, so the next `-m` edit binds.

### Read & understand a model

```sh
dmtool -m order.dm.json model info              # the header dashboard: identity, super/subtypes, includes/imports (resolved to files), counts
dmtool -m order.dm.json model describe          # the model card: fields, kinds, enum domains, groups
dmtool -m order.dm.json model validate          # run it through the real kernel → {valid, diagnostics[]}
dmtool -m order.dm.json rule read  /Order/DeliveryNotBeforeOrder    # a rule's condition, as typed JSON
dmtool -m order.dm.json rule explain /Order/DeliveryNotBeforeOrder  # what it checks + WHEN it fires
dmtool -m order.dm.json rule deps  /Order/DeliveryNotBeforeOrder    # its forward deps: the fields it references (+ iteration)
dmtool -m order.dm.json rule format /Order/DeliveryNotBeforeOrder --lang DE  # the kernel's canonical text, switched EN↔DE
dmtool -m order.dm.json where-used /Order/DeliveryDate             # the inverse: which rules/computations use a field
dmtool -m order.dm.json model usage                                # whole-model audit: every field's referrers + the unreferenced set
```

### Explore a whole folder of models

When you're dropped into a *directory* of models — not one file — `workspace list` is the agent-friendly "ls" that goes *into* them: a per-model index with the sub/supertype graph and every `include` / type-def import **resolved to its file**, so you know what to wire (which `-w/--workspace`, which file provides a needed type-def) without reading raw JSON.

```sh
dmtool workspace list ./models                       # the index as JSON (id, type, super/subtypes, resolved refs, counts)
dmtool workspace list ./models --recursive           # descend sub-folders (widens reference resolution)
dmtool workspace list ./models --format table        # the same facts as an aligned human listing
dmtool workspace list ./models --validate            # also flag which models are kernel-valid
dmtool workspace graph ./models                      # the sub/supertype inheritance hierarchy (nodes + edges)
dmtool workspace graph ./models --format tree        # …as an indented hierarchy (or --format dot for Graphviz)
dmtool workspace validate ./models                   # the project GATE: kernel-validate every model, exit 1 if any invalid
dmtool workspace roles ./models                      # the access-control lint: resolve each model's `roles` vs roles.yaml
```

`workspace validate` is the **gate** form — it runs the real kernel over every model in the directory (references resolved across the tree), reports per-model `{valid, diagnostics[]}` (the same rich `MVK_*` diagnostics as `model validate`), and **exits non-zero if any model is invalid**. That makes it a one-line pre-commit / CI check over a whole project:

```sh
dmtool workspace validate ./models || exit 1         # fail the commit/build on any invalid model
```

(`workspace list --validate` differs: it only flags a per-model boolean; `validate` surfaces *why* and fails the build.)

`workspace roles` is a second lint, on **access control** — but **advisory, never a gate**: A12 models carry a `roles` header annotation naming who may access them, a workspace declares those roles in `auth/roles.yaml`, and its users (with the roles each holds) in `auth/users.yaml`. The lint resolves the whole **RBAC triangle** and flags an **undefined role** (a model gating to a role the file doesn't declare — something even the A12 model editor permits silently), an **undefined authority** (a user holding an undeclared role), an **unreachable model** (gated to roles no user holds), plus ungated models, a missing/duplicate roles file, and orphan roles. Every finding is a **warning** (or a softer INFO) — never an error — and the verb **always exits 0**: access-control config is often a dev seed or owned by an external IdP, so the lint informs without ever blocking model work. Each finding carries a `fix` with the concrete remedy (see the [`cli-tour`](examples/cli-tour.md) walkthrough).

For multi-file models (one model `include`s or imports another *by id*), set the **workspace root once** so every verb resolves references with no per-call flag:

```sh
dmtool -w ./models -m app.dm.json model validate     # -w/--workspace: the dir to resolve includes/imports from
export DMTOOL_WORKSPACE=./models                      # …or set it once per shell session (the env fallback)
```

Precedence: `-w/--workspace` > `$DMTOOL_WORKSPACE` > the model's own folder (see [CLI-SPEC §3a](docs/CLI-SPEC.md)). There's no separate `-w/--workspace` — one workspace concept resolves both includes and type-def imports.

### Scaffold from a validation pattern — polarity & error field baked in

A BA's recurring tasks — *required-when*, *date-order*, *mutually-exclusive*, *at-least-one-of*, *sum-of-line-items* — are exactly where an agent re-risks the two hardest **rule** traps every time: getting the **polarity** right (an A12 rule fires on the *violation*) and picking a **valid error field** (it must be referenced + in scope, else `MVK_ERROR_FIELD_NOT_REFERENCED`). The `patterns` verb removes the re-derivation: each idiom is a **typed-DSL-backed** template — correct by construction — that scaffolds a kernel-valid artifact from a few parameters. Beyond the rule idioms, **`tiered-amount`** is a **computation** idiom — it scaffolds a `kind:computation` artifact whose preconditions form a **mutually-exclusive, exhaustive table** (`< threshold` / `>= threshold`), the trap on the BA's *computation* side; and the **field-constraint** idioms (`bounded-number`, `formatted-string`, `value-set-enum`) scaffold the **field-level alternative** to a rule — the constraint lives in the field config (`number.min/max`, `string.pattern`, `enum.values`) the `seeAlso` bridge points at.

```sh
dmtool patterns                                  # list the idioms
dmtool patterns date-order                        # one idiom: its params, polarity note, skeleton, example

# Scaffold a rule-spec from an idiom and AUTO-CHECK it against the kernel (read-only):
dmtool -m order.dm.json patterns date-order \
  --arg earlier=/Order/OrderDate --arg later=/Order/DeliveryDate
# → { "pattern":"date-order",
#     "spec": { "field":"/Order/DeliveryDate",
#               "condition":"AllFieldsFilled(/Order/OrderDate, /Order/DeliveryDate) And DifferenceInDays(…) < 0", … },
#     "valid": true, "diagnostics": [] }     ← right polarity + a referenced error field, ready for `rule add`

dmtool -m order.dm.json patterns date-order \
  --arg earlier=/Order/OrderDate --arg later=/Order/DeliveryDate --apply   # …or persist it directly (--dry-run previews)

# A COMPUTATION idiom — a mutually-exclusive, exhaustive precondition table (scaffolds a computation-spec):
dmtool -m order.dm.json patterns tiered-amount \
  --arg computed=/Order/BackorderQuantity --arg driver=/Order/Quantity \
  --arg threshold=10 --arg below=0 --arg atOrAbove=5      # --apply persists via `computation add`
```

The catalogue is **self-verifying**: every idiom's example is CI-instantiated and run through the real kernel, so a pattern that no longer scaffolds a valid rule fails the build. See [CLI-SPEC](docs/CLI-SPEC.md) and the [API guide](docs/API-GUIDE.md).

### Author & check — confirmed by the kernel

```sh
# Validate a candidate rule WITHOUT touching the model (returns {valid, diagnostics[]}):
dmtool -m order.dm.json rule check \
  --field /Order/DeliveryDate \
  --condition 'AllFieldsFilled(OrderDate, DeliveryDate) And [DeliveryDate] < [OrderDate]' \
  --code DELIVERY_BEFORE_ORDER

# …and let the tool name the LEGAL error fields up front (the condition's referenced fields, in scope):
dmtool -m order.dm.json rule check --field /Order/DeliveryDate \
  --condition '…' --code DELIVERY_BEFORE_ORDER --suggest-error-field   # → data.candidates[]

dmtool -m order.dm.json rule add <rule-spec.json>          # persist it (kernel-checked before + after)
dmtool -m sub.dm.json   computation add <comp-spec.json>   # a computed field, likewise (with author-time lints)
```

Diagnostics are **corrective**: each carries a stable `code`, a `where`, and a `fix` — and a built-in lint flags the classic **inverted-polarity** mistake automatically (and, on `computation add`, forbidden constructs + overlapping preconditions).

### Run the rules against real data (not just static checks)

```sh
dmtool -m order.dm.json model eval    --doc order-instance.json   # which rules actually FIRE on this document
dmtool -m order.dm.json rule test /Order/DeliveryNotBeforeOrder --doc order-instance.json  # one rule: fired / passed / suppressed
dmtool -m sub.dm.json   model compute --doc sub-instance.json     # what each computed field evaluates to
```

This is the empirical check a static "is it valid?" can't give — it's how you confirm a rule's *polarity* on concrete data. `rule test` scopes it to one rule with a three-way verdict, distinguishing a genuine **pass** from a rule the kernel **suppressed** because a field it references is formally invalid.

### Edit, with a preview diff

```sh
# Re-express an existing rule; --dry-run PREVIEWS the change (writes nothing):
dmtool -m order.dm.json rule modify /Order/DeliveryNotBeforeOrder \
  --condition 'AllFieldsFilled(OrderDate, DeliveryDate) And DifferenceInDays(OrderDate, DeliveryDate) < 0' --dry-run
```

### Edit structure — with a safe-delete gate

```sh
# simple, via flags
dmtool -m order.dm.json field add --group /Order --name LoyaltyPoints --kind NUMBER

# rich: per-kind config + metadata (unit/pattern/suggestions/…) — see `schema field add`
dmtool -m order.dm.json field add fee-spec.json

dmtool -m order.dm.json group add --parent /Order --name Shipping
dmtool -m order.dm.json typedef add …

dmtool -m order.dm.json config modify \
  --decimal-separator "," --label en_US="Customer Order"

# restrict the allowed-character set (runtime-enforced)
dmtool -m order.dm.json config modify \
  --supported-character A-Z --supported-character 0-9

# mount another model under /Order/Prices, dropping the included rules
dmtool -m order.dm.json include add \
  --alias prices --reference pricelist \
  --parent /Order --name Prices --exclude-rules -w ./models

# import another model's type-defs (merged by id, not mounted)
dmtool -m order.dm.json typedef import --reference catalogtypes -w ./models

# a multi-select group template
dmtool -m order.dm.json group multiselect \
  --parent /Order --name Conditions \
  --field Condition --values NEW,USED,REFURB --max-rows 3

# the canonical attachment template (8 fields + 4 rules), generated whole
dmtool -m order.dm.json group attachment \
  --parent /Order --name Invoice \
  --max-size-bytes 10000000 --allowed-mime application/pdf

# describe/annotate any element (field/group/rule by path, type-def by id); `meta /Order/Total` reads it back
dmtool -m order.dm.json meta /Order/Total \
  --internal en_US="the order total" --annotate owner=orders-team

# severity/message edit, condition untouched
dmtool -m order.dm.json rule modify /Order/SomeRule \
  --severity WARNING --message en_US="…"

# Refactor — structural & safety-gated. typedef rename repoints every using field; extract factors shared-type fields out:
dmtool -m order.dm.json typedef rename Currency --to Money

# inverse: typedef inline Money
dmtool -m order.dm.json typedef extract --from /Order/Price,/Order/Cost --id Money

# field/group/rule rename rewrites every reference to the element (via the kernel's MoveSupportDM), then re-validates:
dmtool -m order.dm.json field rename /Order/DeliveryDate --to ShipDate

# field/group move relocates an element to a new parent and rewrites references to its new path (same engine);
# the gate refuses a missing / colliding / self destination:
dmtool -m order.dm.json field move /Order/DeliveryDate --to /Order/Shipping

# extract a subtree into a reusable sub-model, re-mounted at the same name (paths preserved → references survive):
dmtool -m order.dm.json group extract /Order/BillingAddress --reference billing-address
dmtool -m order.dm.json include inline billing-address   # the inverse: fold the sub-model back in

# Deleting a referenced field is REFUSED with a clear diagnostic that names the referrers …
dmtool -m order.dm.json field remove /Order/DeliveryDate
# … resolve it explicitly with --cascade (removes the dependent rules too):
dmtool -m order.dm.json field remove /Order/DeliveryDate --cascade
```

### Both kits at once, atomically — the `apply` session

The CLI face of the unified `EditSession` above — **modelkit structure edits and rulekit rule edits in one transaction**:

```sh
# Read → ordered surgery (structure via modelkit + rules via rulekit) → ONE gate → ONE write, rollback on failure:
dmtool -m order.dm.json apply <ops.json>
```

`apply` runs a list of op-records (e.g. *add field `Discount`*, then *re-type `Plz` to add a pattern*, then *rename `Note`→`Memo`* rewriting its references, then *add a rule that references `Discount`*) on a single in-memory session, tolerates transient-invalid intermediate states, gates once at the end, and **rolls back atomically** if any op fails — reporting which op, and why (with did-you-mean correctives for typos). The op-set spans add·**modify (incl. `field` re-type)**·remove·read **plus the reference-rewriting refactors** (field/group rename·move, rule/typedef rename — each runs its safety gate at its turn, so a refused refactor rolls the whole sequence back). See [`cli-apply`](examples/cli-apply.md) and the gate contract in [CLI-SPEC §6/§7](docs/CLI-SPEC.md).

### Works on real, multi-file apps

```sh
dmtool -m app/storefront.dm.json model validate -w lib   # resolves include'd / typedef'd models
```

### Version & compatibility — tolerant, but explicit

This build targets **A12 Kernel 30.8.1** (A12 Tools distribution **2025.06-ext5**). `dmtool --version` (and `manifest.version`) report that — alongside the kernel actually on the classpath, the operator-catalog floor, and the document-model reference version. Loading a model authored for a different format is handled with the kernel as the authority — an older, compatible model loads with an informational `RK_MODEL_VERSION_SKEW`, an incompatible one fails fast with `RK_MODEL_VERSION_INCOMPATIBLE` (and writing a model back never bumps its version).

### See it all, runnable

The [`examples/`](examples/) directory has **verifiable** walkthroughs of every verb — each is documentation *and* a regression test (re-run against the live CLI):

| Demo | Shows |
|---|---|
| [`cli-tour`](examples/cli-tour.md) | discover + read (`manifest` · `operators` · `diagnostics` · `schema` · `model describe`/`usage`/`validate` · `rule read`/`explain`) |
| [`cli-edit-loop`](examples/cli-edit-loop.md) | author/modify rules & computations, `batch`, the re-express loop |
| [`cli-structure-edit`](examples/cli-structure-edit.md) | fields/groups/typedefs/includes/config + the safe-delete gate |
| [`cli-apply`](examples/cli-apply.md) | the atomic multi-op session + rollback + correctives + in-session refactors |
| [`cli-runtime`](examples/cli-runtime.md) | `model eval` / `model compute` against document instances |
| [`cli-version`](examples/cli-version.md) | `--version`, `manifest.version`, the model-version policy |

The full CLI contract: [`docs/CLI-SPEC.md`](docs/CLI-SPEC.md).

---

## Documentation

Start at the **[docs index](docs/README.md)**; the rest of the map:

| To find… | See |
|---|---|
| Vision, requirements, the decision log | [`docs/PROJECT-PROPOSAL.md`](docs/PROJECT-PROPOSAL.md) |
| Per-feature implementation status | [`docs/API-STATUS.md`](docs/API-STATUS.md) |
| The CLI contract | [`docs/CLI-SPEC.md`](docs/CLI-SPEC.md) |
| Verified kernel facts | [`docs/KERNEL-FINDINGS.md`](docs/KERNEL-FINDINGS.md) · [`docs/KERNEL-INTEGRATION-NOTES.md`](docs/KERNEL-INTEGRATION-NOTES.md) |

## Prerequisites

The A12 Kernel is resolved from your **local Maven repository** (`~/.m2`) — no private registry. Publish it from public sources once with the sibling [`a12-kernel-bootstrap`](https://github.com/mbackschat/a12-kernel-bootstrap) kit:

```sh
# in the a12-kernel-bootstrap checkout (a sibling of this repo)
./bootstrap.sh
```

This publishes kernel `30.8.1` (Java only) to `mavenLocal`. You also need **JDK 21** — the build toolchain is pinned to 21 (a newer JDK isn't used even if installed; [why](docs/KERNEL-INTEGRATION-NOTES.md) — Groovy 3.0.25 + the validated baseline). GraalVM 25 is used only for the native build.

## Build & test

Like the sibling repos, build with a **clean/isolated Gradle home** so the machine's global config (which may point at a private registry) is bypassed — `build.gradle` declares only `mavenLocal()` + `mavenCentral()`:

```sh
ROOT="$(cd .. && pwd)"                               # the directory holding the A12 repos
export GRADLE_USER_HOME="$ROOT/.gradle-isolated"     # public-repos-only Gradle home
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"   # macOS; or point at any JDK 21
./gradlew test
```

A green `test` means the kit's output is confirmed by the engine, not just plausible. The suite covers:

- kernel wiring and the model index
- the renderer (EN/DE) and the validator
- the self-verifying catalog — every example checked against the real kernel
- the constraint-mining instruments (slot parity, the MVK / RT-semantics / grammar-production ledgers)

With a sibling `a12-kernel-mm` clone present, an opt-in harness also loads all 15 of the kernel's own metamodels through the index (skipped when absent).

Other useful tasks:

```sh
./gradlew :adapter:test                              # the opt-in internal-tier module
./gradlew :cli:run --args="-m model.json model validate"   # run the standalone CLI in dev (no packaging)
```

`./gradlew :test` keeps the core build free of the internal tier; see [`docs/CLI-SPEC.md`](docs/CLI-SPEC.md) for the CLI.

## Commit hooks

A committed `commit-msg` hook (`.githooks/commit-msg`) enforces the repo's commit convention — Conventional Commits subject, subject-only by default, no bullet lists or test counts. Enable it once per clone:

```sh
git config core.hooksPath .githooks
```

It hard-rejects the mechanical violations and reminds on the judgment rule; bypass a single commit with `git commit --no-verify`. To apply the same check across all your repos, point your global git template at it — copy the hook into `~/.git-template/hooks` and set `git config --global init.templateDir ~/.git-template`.

## License

**MIT** — see [`LICENSE`](LICENSE).

- **Source: MIT.** The kits' own original work, shipping no kernel binary, so they carry no copyleft entanglement — consistent with the sibling `a12-kernel-demo` / `a12-kernel-bootstrap` (both MIT).
- **The kernel: dual `EUPL-1.2 OR commercial`** (upstream, not ours).
- **The future standalone CLI distributable: EUPL-1.2**, because it *bundles* the kernel — the source stays MIT.

# a12-dmkits

Author, read, and safely edit the **rules** and **structure** of A12 Kernel document models — as typed Java code, or through a self-describing command-line tool. Every result is checked by the **real A12 kernel**, so what you produce is *accepted*, not merely plausible. JSON in, JSON out.

> **Not an official A12 artifact.** A personal exploration, not affiliated with or endorsed by mgm. Shipped code binds only to the kernel's **public** OSS surface — no internal systems. (An opt-in, Java-only adapter module may read the kernel's internal AST tier under strict guardrails.)

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

Rule rule = m.rule(m.group("/Subscription/Addons"))
    .name("FeeCapRule")
    .errorField(m.num("/Subscription/Addons/MonthlyFee"))         // fields come OFF the model …
    .when(m.num("/Subscription/Addons/MonthlyFee").gt(1000))      // … a wrong path can't compile
    .code("FEE_CAP").severity(Severity.ERROR)
    .message(en("Monthly fee is too high."), de("Monatsgebühr ist zu hoch."))
    .build();

String dmJson = m.add(rule).toJson();   // re-validated through the kernel; ready to write back
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
| a **modeller / BA**, or an **LLM agent** | **`dmtool`**, the CLI | verbs + JSON I/O, JVM bundled, self-describing — no Java toolchain to install |

*(Java first; a TypeScript twin is a deferred, design-informing milestone.)*

---

## rulekit — the library

### Referencing a field — by path, or through an editor

Two ways to name the field a rule talks about; they return the **same pure refs**, so you blend them as fits:

| Style | Looks like | Reach for it when |
|---|---|---|
| **By path** (L2a) | `m.num("/Order/Discount")` | referencing an **existing** element while authoring against a loaded model — the common case |
| **Through an editor** (L2c) | `s.group("/Order").num("Discount")` | the read→**edit**→reference flow: a ref to a field you *just created*, or relative navigation, without re-typing paths — see *Both kits together* below |

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

Browse the full, self-verifying catalogue (every operator with its meaning, examples, and **gotchas**) via the CLI's `operators` verb, or in the [API study](docs/API-POC-STUDY.md).

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

More rulekit idioms and the design rationale are in the [API study](docs/API-POC-STUDY.md).

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

**What it covers** — field kinds (string with length/pattern, number with fraction-digits/min/max, date / time / date-range, boolean, confirm, enum with values + categories), **repeatable groups** (with index fields), reusable **type definitions**, **includes** (mount another model under a group), and **document config** (locales, decimal separator, time zone, base year). To edit the structure of an *existing* model — add/remove fields & groups, declare type definitions, change config — use `EditSession` (below).

**Illegal states are unrepresentable.** The builder rejects the mistakes the kernel would — a repeatable group with `max < 2`, a field referencing an undeclared type definition — *as you build*, not after a round-trip; and the kernel still has the final say (every result re-binds through `checkConsistency`).

## Both kits together — `EditSession`: one model, one gate

This is where modelkit and rulekit meet — **structure edits and rule edits on one in-memory model, behind a single kernel gate**. The north-star flow: read a model, make a series of structure *and* rule edits (a rule may reference a field added earlier in the same session), validate **once**, write back (intermediate invalid states are fine — only the final result must satisfy the kernel):

The idiom is the **editor** (L2c): you hold an editor on a group rather than re-typing string paths — structure edits chain, a typed ref to a just-created field comes from relative navigation, and rules author in place against it.

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

**Refs are the currency.** `order.num("Discount")` resolves *only because* `addField` ran first — and it returns a typed ref, not a string path you re-type. Rules author in place (`order.addRule(r -> …)`, the builder pre-scoped to the group) or take the editor straight as a scope (`m.rule(order)`); either way the immutable AST holds **only the pure ref, never the session**, so a rule can't leak an effect. `GroupEditor` also nests (`addGroup(name, sub -> …)`); `FieldEditor` re-types/labels/annotates a field; `RuleEditor` / `ComputationEditor` modify or remove by held reference. `EditSession` also carries the **reference-preserving refactors** — `renameField`/`renameGroup`/`moveField`/`moveGroup` rewrite every reference to the element (via the kernel's `MoveSupportDM`) and re-validate, so a rename or relocation never dangles a referrer. Design + rationale: [API study §5c](docs/API-POC-STUDY.md).

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

More library idioms and the design rationale live in the [API study](docs/API-POC-STUDY.md); every doc is indexed in [`docs/`](docs/README.md).

---

## `dmtool` — the CLI

Same engine, no Java required. Every command is `dmtool -m <model> <target> <op> [args]` and prints a uniform JSON **result envelope** (`{ok, valid, outcome, data, diagnostics[], …}`). It is **self-describing**, so a cold agent (or you) can discover the whole surface from the binary alone.

### Discover the tool itself

```sh
dmtool manifest                       # every verb × parameter, machine-readable, in one call
dmtool operators                      # the DSL operator catalogue …
dmtool operators GroupFilled          #   … or one operator in full (meaning, examples, gotchas)
dmtool schema rule add                # the exact JSON shape a verb consumes / returns
dmtool --version                      # this build + the kernel it targets (see "compatibility" below)
```

### Read & understand a model

```sh
dmtool -m order.dm.json doc describe          # the model card: fields, kinds, enum domains, groups
dmtool -m order.dm.json doc validate          # run it through the real kernel → {valid, diagnostics[]}
dmtool -m order.dm.json rule read  /Order/DeliveryNotBeforeOrder    # a rule's condition, as typed JSON
dmtool -m order.dm.json rule explain /Order/DeliveryNotBeforeOrder  # what it checks + WHEN it fires
dmtool -m order.dm.json where-used /Order/DeliveryDate             # which rules/computations use a field
```

### Author & check — confirmed by the kernel

```sh
# Validate a candidate rule WITHOUT touching the model (returns {valid, diagnostics[]}):
dmtool -m order.dm.json rule check \
  --field /Order/DeliveryDate \
  --condition 'AllFieldsFilled(OrderDate, DeliveryDate) And [DeliveryDate] < [OrderDate]' \
  --code DELIVERY_BEFORE_ORDER

dmtool -m order.dm.json rule add <rule-spec.json>          # persist it (kernel-checked before + after)
dmtool -m sub.dm.json   computation add <comp-spec.json>   # a computed field, likewise
```

Diagnostics are **corrective**: each carries a stable `code`, a `where`, and a `fix` — and a built-in lint flags the classic **inverted-polarity** mistake automatically.

### Run the rules against real data (not just static checks)

```sh
dmtool -m order.dm.json doc eval    --doc order-instance.json   # which rules actually FIRE on this document
dmtool -m sub.dm.json   doc compute --doc sub-instance.json     # what each computed field evaluates to
```

This is the empirical check a static "is it valid?" can't give — it's how you confirm a rule's *polarity* on concrete data.

### Edit, with a preview diff

```sh
# Re-express an existing rule; --dry-run PREVIEWS the change (writes nothing):
dmtool -m order.dm.json rule modify /Order/DeliveryNotBeforeOrder \
  --condition 'AllFieldsFilled(OrderDate, DeliveryDate) And DifferenceInDays(OrderDate, DeliveryDate) < 0' --dry-run
```

### Edit structure — with a safe-delete gate

```sh
dmtool -m order.dm.json field add --group /Order --name LoyaltyPoints --kind NUMBER
dmtool -m order.dm.json group add --parent /Order --name Shipping
dmtool -m order.dm.json typedef add … ; dmtool -m order.dm.json config modify --decimal-separator ","

# Refactor — structural & safety-gated. typedef rename repoints every using field; extract factors shared-type fields out:
dmtool -m order.dm.json typedef rename Currency --to Money
dmtool -m order.dm.json typedef extract --from /Order/Price,/Order/Cost --id Money   # inverse: typedef inline Money
# field/group/rule rename rewrites every reference to the element (via the kernel's MoveSupportDM), then re-validates:
dmtool -m order.dm.json field rename /Order/DeliveryDate --to ShipDate
# field/group move relocates an element to a new parent and rewrites references to its new path (same engine):
dmtool -m order.dm.json field move /Order/DeliveryDate --to /Order/Shipping   # gate refuses a missing/colliding/self destination
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

`apply` runs a list of op-records (e.g. *add field `Discount`* then *add a rule that references `Discount`*) on a single in-memory session, tolerates transient-invalid intermediate states, gates once at the end, and **rolls back atomically** if any op fails — reporting which op, and why (with did-you-mean correctives for typos). See [`cli-apply`](examples/cli-apply.md).

### Works on real, multi-file apps

```sh
dmtool -m app/storefront.dm.json doc validate --include-dir lib   # resolves include'd / typedef'd models
```

### Version & compatibility — tolerant, but explicit

`dmtool --version` reports what the build targets; loading a model authored for a different format is handled with the kernel as the authority — an older, compatible model loads with an informational `RK_MODEL_VERSION_SKEW`, an incompatible one fails fast with `RK_MODEL_VERSION_INCOMPATIBLE` (and writing a model back never bumps its version).

### See it all, runnable

The [`examples/`](examples/) directory has **verifiable** walkthroughs of every verb — each is documentation *and* a regression test (re-run against the live CLI):

| Demo | Shows |
|---|---|
| [`cli-tour`](examples/cli-tour.md) | discover + read (`manifest` · `operators` · `schema` · `doc describe`/`validate` · `rule read`/`explain`) |
| [`cli-edit-loop`](examples/cli-edit-loop.md) | author/modify rules & computations, `batch`, the re-express loop |
| [`cli-structure-edit`](examples/cli-structure-edit.md) | fields/groups/typedefs/includes/config + the safe-delete gate |
| [`cli-apply`](examples/cli-apply.md) | the atomic multi-op session + rollback + correctives |
| [`cli-runtime`](examples/cli-runtime.md) | `doc eval` / `doc compute` against document instances |
| [`cli-version`](examples/cli-version.md) | `--version`, `manifest.version`, the model-version policy |

The full CLI contract: [`docs/CLI-SPEC.md`](docs/CLI-SPEC.md).

---

## Documentation

Start at the **[docs index](docs/README.md)**; the rest of the map:

| To find… | See |
|---|---|
| Vision, requirements, the decision log | [`docs/PROJECT-PROPOSAL.md`](docs/PROJECT-PROPOSAL.md) |
| Per-feature implementation status | [`docs/API-POC-STUDY.md` §9](docs/API-POC-STUDY.md) |
| The CLI contract | [`docs/CLI-SPEC.md`](docs/CLI-SPEC.md) |
| Verified kernel facts | [`docs/KERNEL-FINDINGS.md`](docs/KERNEL-FINDINGS.md) · [`docs/KERNEL-INTEGRATION-NOTES.md`](docs/KERNEL-INTEGRATION-NOTES.md) |

## Prerequisites

The A12 Kernel is resolved from your **local Maven repository** (`~/.m2`) — no private registry. Publish it from public sources once with the sibling [`a12-kernel-bootstrap`](https://github.com/mbackschat/a12-kernel-bootstrap) kit:

```sh
# in the a12-kernel-bootstrap checkout (a sibling of this repo)
./bootstrap.sh
```

This publishes kernel `30.8.1` (Java only) to `mavenLocal`. You also need **JDK 21**.

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
./gradlew :cli:run --args="-m model.json doc validate"   # run the standalone CLI in dev (no packaging)
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

Full analysis: [`docs/LICENSING.md`](docs/LICENSING.md).

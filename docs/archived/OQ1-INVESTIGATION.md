# OQ1 investigation — code-mode (`dmcode`) vs the bare-tool CLI

> **Archived working doc** — the long-form analysis behind the OQ1 verdict. The durable verdict lives in [`eval/RESULTS.md`](../../eval/RESULTS.md) (2026-06-17 03:03 entry) and [`docs/DMCODE-PROPOSAL.md`](DMCODE-PROPOSAL.md) (OQ1 → parked). This is the long-form working analysis behind that verdict.

**One-line verdict:** code-against-the-API **tied** the bare-tool CLI on correctness across all seven probed tasks — including the four designed to favor it — at **~2.3× token cost** and **worse on refactors**. The reason is the star finding below: **a coding agent clears the "verb ceiling" by scripting around it.**

---

## 1. The experiment

- **Two arms, neither given the skill** (`skill/SKILL.md`):
  - **CLI arm** — only the self-describing `dmtool` binary + the model file (the bare-tool boundary). Audited: read zero repo files.
  - **Code-mode arm** — a `/tmp` **`api.txt` proxy** (scoped signatures + javadoc, ~38k tokens) + `operators.json` + the model, source-launched (`java -cp '…/lib/*' App.java`) against the kits classpath. No `src/`. Audited: read only those two resources + the model.
- **k = 2**, 7 tasks. Clean Agent-tool subagents driven from the main loop; boundaries instructed + transcript-audited (not the workflow-enforced runner).
- Fixtures authored via the CLI (kernel-valid by construction); scoring deterministic (CLI re-checks / JSON inspection), not self-reports.
- **Material asymmetry (inherent, by design):** the CLI self-describes incrementally (`manifest`/`schema`/`--help`); code has no self-description, so `api.txt` is its discovery surface. Honest caveat: the kit javadoc in `api.txt` embeds some judgment (e.g. polarity), a mild tilt toward code on the semantic traps.

---

## 2. Test cases — full detail + rationale

The first three (C1–C3) were the original small-scale set; they tied/strained the CLI too little, which is *why* they tied. A/C/D/E were then designed from a verb-surface study to hit the CLI's *structural* difficulty axes.

| id | fixture | neutral intent (as the agent saw it) | discriminating axis + rationale | gold / deterministic check | CLI | code |
|---|---|---|---|---|---|---|
| **C1** | insurance policy base | "for each of 5 tiers add a `<Tier>Limit` whole-number field + a >0-when-present rule" | bulk-but-simple (5 independent items) — baseline; expected tie | valid + 5 `*Limit` fields + 5 rules | 2/2 | 2/2 |
| **C2** | order, 3 numeric rules (2 unguarded, 1 guarded) | "find every rule that compares a possibly-empty number without a presence check and add the guard; leave correct ones" | conditional transform over a *discovered* set (small) | the unguarded set guarded, correct rules untouched | 2/2 | 1/2 ⚠ |
| **C3** | order with `Legacy*` fields + refs | "rename every `Legacy`-prefixed field to drop the prefix, updating references" | built-in refactor — **CLI-strength control** | no `Legacy` left, renamed fields + refs valid | 2/2 | 2/2 † |
| **A** | order, 6 fields (3 unreferenced), 1 long-message rule | "report `{unreferencedFields, longMessageRules>60chars}` as JSON" | **aggregate query / audit** — CLI has only per-target reads, no predicate-query | set-equality vs `{CustomerName,Notes,ShipCity}` + `{TOTAL_VS_QTY}` | 2/2 | 2/2 |
| **C** | order base + 25-item external JSON spec | "for each item in the spec, add a `<Item>Fee` field + a >0 rule" | **data-driven at scale** (×25, external input) — no import verb; no loop | valid + 25 `*Fee` fields + 25 rules | 2/2 | 2/2 |
| **D** | the C4 order model (ref-map known) | "set each field's internal description to `Constrained by: <rule codes>` / `none`" | **edit parameterized by model state** — op value = a query result; ops have no data-flow | each field's description == the where-used map | 2/2 | 2/2 |
| **E** | 3-model workspace, each with `CustomerId` + a referencing rule | "rename `CustomerId`→`AccountId` in every model, updating refs" | **multi-model transform** — `workspace` verbs are read-only; no cross-model transform | all 3: no `CustomerId`, `AccountId` present, valid | 2/2 | 2/2 † |

⚠ **C2 code 1/2 is an eval-design artifact, not a code deficiency.** I confirmed the `RK_UNGUARDED_NUMBER_COMPARISON` lint flags only the field-vs-constant case (`WEIGHT_MIN`), *not* the field-vs-field `DISCOUNT_MAX` (which is semantically buggy via empty-as-0 but the tool's own oracle calls clean). The code-k1 agent reasonably trusted the lint and under-corrected. My fixture introduced the ambiguity.

† **C3/E "tie" hides a big effort gap** — see §4 (the CLI's cascading `field rename` is one verb; code-mode needed 4–5-iteration manual surgery).

### 2a. How the agents were prompted (verbatim)

**Neutral-prompt discipline:** each prompt states *the requirement only* — never the technique. None said "use a loop", "use `batch`/`apply`", "generate the op-list", or "walk the model". So the §3 scripting patterns are what the agents *chose*, not what they were told.

Only two things varied across the 16 runs: **the per-arm boundary block** (constant within an arm) and **the per-task intent + paths**. The full prompt for one task (C, k1) in each arm, verbatim:

**CLI arm (C, k1):**
```
Use ONLY the `dmtool` CLI to edit a model.

TOOL: `…/cli/build/install/dmtool/bin/dmtool`. Discover via `dmtool --help`/`manifest`/
`<target> <op> --help`/`schema`/`operators`.

MODEL (edit in place, write back with `-o` to the same path): `/tmp/oq1/runs/cli/C/k1/model.dm.json`.
TASK INPUT (you may read this file): `/tmp/oq1/fixtures/C-spec.json` — a JSON array of coverage item names.

STRICT BOUNDARY: only the `dmtool` binary, that model file, and the spec JSON above.
Do NOT read any repo source/docs/tests.

TASK: For each item name N in the spec, add to the order an optional whole-number field
named `<N>Fee` (e.g. CollisionFee) and add a rule that that fee, when present, must be
greater than zero.

When done: `dmtool -m /tmp/oq1/runs/cli/C/k1/model.dm.json model validate` is valid.
Return: the model path, how many fields and rules you added, and a one-line summary.
```

**Code-mode arm (C, k1):**
```
Edit a document model by WRITING AND RUNNING Java against the a12 dmkits library. No CLI tools.

COMPILE+RUN (Java single-file source-launcher, no build tool):
    export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
    "$JAVA_HOME/bin/java" -cp '…/cli/build/install/dmtool/lib/*' /tmp/your-file.java

API REFERENCE: `/tmp/oq1/dmcode-resources/api.txt` (signatures + javadoc) and
`/tmp/oq1/dmcode-resources/operators.json` (catalog). Your ONLY API references. Key entry
points to find there: `EditSession`, `modelkit.*` (FieldKind, FieldTypeSpec, Requiredness),
`rulekit.dsl.Dsl`, `rulekit.validate.RuleValidator`.

MODEL (read, edit via the API, write back to the same path): `/tmp/oq1/runs/code/C/k1/model.dm.json`.
TASK INPUT (you may read): `/tmp/oq1/fixtures/C-spec.json` — a JSON array of coverage item names.

STRICT BOUNDARY: read ONLY `api.txt`, `operators.json`, the model file, and the spec JSON.
Do NOT read the repo's `src`, `docs`, `eval`, or tests.

TASK: For each item name N in the spec, add to the order an optional whole-number field
named `<N>Fee` and a rule that that fee, when present, must be greater than zero.

When done: the model is saved and passes `RuleValidator`. Return: the model path, how many
fields and rules you added, a one-line summary, and how many compile/validate iterations it took.
```

The boundary blocks above were identical for every task in that arm (only the `cli/`↔`code/` path and the resource list differ). The **`TASK:` line is the only thing that changed per task** — here are all seven, verbatim:

| id | the verbatim `TASK:` line |
|---|---|
| C1 | "An insurance policy offers five coverage tiers — Bronze, Silver, Gold, Platinum, Diamond. For each tier, add an optional whole-number field named `<Tier>Limit` (so: BronzeLimit, SilverLimit, GoldLimit, PlatinumLimit, DiamondLimit) holding that tier's claim limit, and add a rule that the limit, when present, must be greater than zero." |
| C2 | "Several rules on this order model compare numeric fields, but some don't first check that the field is present — and an absent number is treated as zero, so those rules can fire incorrectly. Find every rule with that flaw and add the missing presence check; leave the rules that are already correct unchanged." |
| C3 | "Rename every field whose name begins with 'Legacy' to drop that prefix (for example LegacyOrderId becomes OrderId), updating all rule references so the model stays valid." |
| A | "Audit the order model and output exactly this JSON as your final answer (nothing else): `{"unreferencedFields": [<field names that NO rule references>], "longMessageRules": [<codes of rules whose error-message text is longer than 60 characters>]}`. Return only that JSON object." |
| C | "For each item name N in the spec, add to the order an optional whole-number field named `<N>Fee` and a rule that that fee, when present, must be greater than zero." |
| D | "For every field in the order model, set its internal (maintainer-facing) description to exactly `Constrained by: <X>`, where `<X>` is the comma-separated list of rule codes whose condition references that field, in alphabetical order — or the literal `none` if no rule references the field." |
| E | "Rename the field `CustomerId` to `AccountId` in EVERY model in that workspace directory, updating all rule references so each model stays valid." |

Two prompt-shape variants beyond the C template above: **A** is read-only and requires a JSON-only final answer (no model write); **E** points at a *workspace directory* (`…/E/k1/ws/`) the agent lists, not a single model. Everything else is the C template with the paths + `TASK:` swapped.

---

## 3. ⭐ The headline — "the CLI arm scripts around the verb limits" (with actual calls)

**Two structural properties** define where the verb model *theoretically* strains: (1) every read is **per-target** (no predicate-query verb), and (2) `apply`/`batch` is a **control-flow-free, data-flow-free ordered op-list**. The dmcode thesis assumed these are the *agent's* ceiling. They are not — **a coding agent supplies the missing loop / data-flow / filter in shell (Python/jq/bash), using the verbs as primitives.** Here are the real transcripts.

### Pattern 1 — data-driven generation (task C): *generate the op-list programmatically*

**C k1 — Python → `batch`.** After discovering (`schema apply`, `schema batch`, `batch --help`) that `apply` op-records reject rich field config, the agent wrote a Python loop that emits one field-spec + one rule-spec per item and a 50-op `batch` manifest, then ran it in **one** call:

```python
# (verbatim from the agent's bash) — generate 50 ops from the 25-item spec
items = json.load(open("/tmp/oq1/fixtures/C-spec.json"))
batch = []
for n in items:
    fld = f"/Order/{n}Fee"
    json.dump({"group":"/Order","name":f"{n}Fee","kind":"NUMBER","number":{"maxFractionalDigits":0}}, open(f".../field-{n}.json","w"))
    json.dump({"field":fld,"condition":f"FieldFilled({fld}) And [{fld}] <= 0","code":f"{n.upper()}_FEE_POSITIVE","messages":[…]}, open(f".../rule-{n}.json","w"))
    batch.append({"id":f"field-{n}","verb":"field","args":["add", f".../field-{n}.json","-m",model]})
    batch.append({"id":f"rule-{n}", "verb":"rule", "args":["add", f".../rule-{n}.json","-m",model]})
json.dump(batch, open(".../batch-all.json","w"))
```
```sh
dmtool batch /tmp/oq1/runs/cli/C/k1/batch-all.json   # one call → all 50 ops
```

**C k2 — `jq` → `apply`.** Same idea, terser: a single `jq` transform of the spec into a 75-op `apply` array (the agent first *probed* the field-config limit, then used add → modify → rule-add per item):

```sh
jq -c '[ .[] as $n
  | ( {target:"field",op:"add",   group:"/Order",name:($n+"Fee"),kind:"NUMBER"} ),
    ( {target:"field",op:"modify",group:"/Order",name:($n+"Fee"),kind:"NUMBER",number:{maxFractionalDigits:0}} ),
    ( {target:"rule", op:"add", field:("/Order/"+$n+"Fee"),
       condition:("FieldFilled(/Order/"+$n+"Fee) And [/Order/"+$n+"Fee] <= 0"),
       code:(($n|ascii_upcase)+"_FEE_POSITIVE"), messages:[…] } ) ]' /tmp/oq1/fixtures/C-spec.json > ops.json
dmtool -m model.dm.json apply ops.json -o model.dm.json
```

> The "no loop" limit of the op-list is filled by **`jq`/Python generating the op-list**. The CLI verbs (`batch`/`apply`) are the execution primitive.

### Pattern 2 — aggregate audit (task A): *shell loop over per-target reads + a filter*

No predicate-query verb → the agent **looped `where-used` over every field** for the unreferenced-field half:

```sh
for f in CustomerName OrderDate Total Quantity Notes ShipCity; do
  echo "=== $f ==="; dmtool where-used --entity=/Order/$f model.dm.json
done
```

For the "message > 60 chars" half there's no read that surfaces message *text*, so the agent **used `model eval` on a crafted doc to fire the rules and read their messages**, then filtered by length in Python:

```sh
echo '{ "fields": { "Order": { "Total": 1, "Quantity": 5 } } }' > doc.json
dmtool -m model.dm.json model eval --doc=doc.json \
 | python3 -c 'import sys,json; [print(m["code"], len(m["message"])>60) for m in json.load(sys.stdin)["data"]["messages"]]'
```

> The cross-cutting query is assembled from **`where-used`×N + a shell loop + a Python length filter**. (Note the message-length check reached for the JVM-only `model eval` — a gap, see §5e.)

### Pattern 3 — multi-model transform (task E): *shell loop over the cascading verb*

The CLI has no cross-model transform, but `field rename` cascades references within a model, so the agent **looped it over the three models**:

```sh
for m in orderA orderB orderC; do
  dmtool -m /tmp/oq1/runs/cli/E/k1/ws/$m.dm.json field rename /Order/CustomerId --to=AccountId
done
```

> The "no cross-model verb" limit is filled by a **`bash for`-loop**; each iteration uses the safe, cascading `field rename`.

### Pattern 4 — state-parameterized edit (task D): *query → format → write, per element*

```sh
# per field: where-used to get the constraining rules, then meta to write the derived description
dmtool where-used --entity=/Order/$f model.dm.json         # → the rule codes
dmtool -m model.dm.json meta /Order/$f --internal en_US="Constrained by: <codes|none>"
```

> Even the "edit whose value is a query result" (no op-to-op data-flow) is handled by **query-verb → shell → write-verb**, per element.

**The thesis collapse in one sentence:** the verb vocabulary is not the agent's ceiling — *the agent's shell is the loop and the data-pipe*, and `batch`/`apply`/`where-used`/`field rename` are more than enough primitives. So code-mode's expressiveness edge never materialized.

---

## 4. Analysis of both arms

**CLI arm — "discover, then script."** Pattern across tasks: a few `--help`/`schema`/`manifest` discovery calls, then either a direct verb (single edits, the rename loop) or a generated op-list (`jq`/Python → `batch`/`apply`). ~**42k tokens/task** avg, 9–39 tool calls. It hit exactly one real friction — `apply` op-records reject rich field config (`RK_UNKNOWN_ARG`: "valid args: group, kind, name, typedef") — and both C agents *independently* worked around it (one via `batch` of standalone `field add` specs, one via `apply` `add`-then-`modify`). It also leaned on `model eval` (a JVM-only verb) to read message text in A.

**Code-mode arm — "read `api.txt`, write Java, compile-iterate."** ~**97k tokens/task** avg (~2.3×), driven by (a) the 38k `api.txt` read, (b) the compile→run→fix loop (1 iteration on the easy tasks; **4–5 on E**). The compiler-as-oracle worked as advertised on *syntax* (type/path errors caught pre-run, e.g. the `/Order` vs `Order` path got a precise "did you mean '/Order'?"). But two recurring API friction points dominated cost:

- **No reference-cascading rename in the kit** (C3, E): `EditSession.renameField` rewrites only the field's own name/id — **not** rule `errorCondition` / `errorEntityRelPath`. And the rule editors re-bind through `checkConsistency` on every call, so they refuse to run while the model is transiently inconsistent. The only working recipe the agents found: `removeRule` (while fields still consistent) → `renameField` → `addRule` (pointing at the new name) → one final `validate()`. **4–5 iterations** to discover that ordering. The CLI's `field rename` does the whole thing in one verb. → **OQ7** (a real kit gap).
- **No "read existing rules' references" facility** (A, D): the kit exposes no parse-text-to-`Condition` and no enumerate-rules-as-`Condition`; agents fell back to Jackson tree-walks + regex token matching on the stored condition strings. Works, but it's exactly the per-element reasoning the API was supposed to make trivial.

| | CLI arm | code-mode arm |
|---|---|---|
| tokens/task (avg) | **~42k** | ~97k (**~2.3×**) |
| iterations | n/a (verb calls) | 1 easy → **4–5** on refactors |
| oracle | kernel validation (runtime) | **compiler** (pre-run, for syntax) + kernel validate |
| failure mode | a verb arg quirk, worked around | API gaps (no cascade rename, no rule-ref read) → fragile multi-step recipes |
| refactors | one cascading verb | manual remove→rename→re-add surgery |

---

## 5. Can we improve the CLI tool? (grounded in what the agents hit)

Concrete, evidence-backed proposals (in rough priority):

- **(a) Let `apply`/`batch` `field add`/`field modify` op-records take the rich per-kind spec.** Both C agents lost cycles discovering that `apply`'s `field add` rejects `number.maxFractionalDigits` (only `group/kind/name/typedef`), forcing `batch`-of-standalone-specs or `add`-then-`modify`. The standalone `field add <spec.json>` accepts it; the op-record should too (single source the spec parse). **Highest-value, smallest fix.**
- **(b) Add a model-wide usage/audit read** — the single most-looped-around gap. A verb like `field read --all --with-usage` (or `model audit`) returning the **field → referencing-rules map + the unreferenced set** in one call would replace the `where-used`×N loop that A and D both wrote. (Code-mode's only "query" advantage is exactly this; closing it in the CLI removes that advantage entirely.)
- **(c) Expose rule **message text** in a read verb.** A had to fire `model eval` (JVM-only — not even on the native binary!) just to read message strings for a length check. `rule read` returns the condition; it should also surface `messages[]`. Removes a JVM-only dependency from a read path.
- **(d) Accept a **positional entity** for `where-used`.** E-k1 first tried `where-used /Order/CustomerId` (failed: "Missing required option: --entity"), then `--entity=`. Minor, but a positional (like the other reads) is friendlier.
- **(e) Consider a workspace-wide transform** — E looped `field rename` per model. A `workspace rename --entity … --to …` (apply a refactor across every model in a tree) would make the multi-model case a single call. Lower priority (the loop is easy), but it's the one genuine cross-model gap.

None of these are about adding code-mode; they make the **CLI strictly stronger** at exactly the axes this probe stressed.

---

## 6. Anything for the CLI tool's skill (`skill/SKILL.md`)?

The skill's job is **judgment, not the verb catalog** (discoverability lives in the tool). Most of §5 should be **tool fixes, not skill text** (don't bloat the eval's material-under-test). The one genuinely judgment-level idiom worth at most a line or two:

- **"For many similar edits, generate the op-list, don't hand-enumerate."** The agents *independently* discovered the `jq`/Python → `batch`/`apply` idiom — teaching it (one sentence + a tiny example) would make every agent reach it on task 1 instead of after exploration. It's a *strategy*, not a catalog fact, so it fits the skill's remit. Keep it minimal: *"Bulk/data-driven edits: build a `batch`/`apply` op-list with a small script (jq/python) from your input, then apply it in one gated call — don't issue N edits by hand."*

Everything else the probe surfaced (the `apply` field-config quirk, the audit loop, message-text reads) is **better removed by §5's tool fixes** than documented as a workaround in the skill.

---

## 7. Assessment — where is code-mode **definitely** better?

Honestly: **not** on the everyday authoring tasks tested — shell+CLI covers bulk, data-driven, aggregate query, state-parameterized, multi-model, and *beats* code on refactors. Code-mode is definitely better only where the work **exceeds what shell+`jq`+CLI can comfortably express, or needs a capability the shipped CLI lacks:**

1. **Runtime evaluation (the clearest, structural win — OQ2).** `model eval`/`rule test`/`model compute` execute rules against *data instances* via Groovy. The **native CLI cannot ship these** (no Groovy); a JVM bundle (dmcode) **can**. This isn't "better at authoring" — it's "can do a thing the shipped artifact can't." Untested in this probe; the one un-superseded reason to keep dmcode on the table.
2. **Genuine algorithms over the model graph** — transitive dependency closure, cycle detection among computations, topological ordering, "diff two models' rule sets structurally." These are torturous in `jq`/shell and natural in typed Java with real data structures. None of A–E reached this bar; it's the plausible authoring frontier where code would win.
3. **Embedding in a larger JVM program** — when model manipulation is *part of* a bigger application (repeated in-memory queries, a long-lived index, integration with other Java), the library API beats re-shelling the CLI per operation. But that's the **(B) developer-library** persona, not the (A) BA-agent one.
4. **Type-safe construction of intricate conditions** — the compiler catches malformed nesting pre-run. Marginal here, because the CLI's kernel validation catches the same at runtime with a good diagnostic; the win is speed-of-feedback, not capability.

So: **runtime-eval (structural) + real graph algorithms + JVM-embedding.** Not BA authoring.

---

## 8. Suggestions for more test cases

To find code-mode's real frontier (or confirm its absence) and to harden the comparison:

- **Graph algorithm:** "report any circular dependency among the model's computations" or "list the full transitive set of fields a given rule depends on *through* computations." Torturous in shell; natural in code. **Most likely to show a code win.**
- **Runtime-eval (tests OQ2 directly):** "given these 5 data instances, report which rules each violates, and which instance is closest to valid." The native CLI can't; only the JVM/dmcode can. Tests the *structural* advantage, not authoring ergonomics.
- **Scale stress:** the A audit / D state-parameterized on a **100+ field, 50+ rule** model — does the CLI's `where-used`×N loop become slow/expensive enough that one code walk wins on cost? (Tests whether the per-target-read overhead ever flips the ~2.3× cost ratio.)
- **C2, de-ambiguated:** rebuild the conditional-transform fixture so the flawed set is unambiguous (all field-vs-constant, or align with the lint), for a clean conditional-transform number.
- **A real CLI-can't task:** something requiring an intermediate typed data structure consumed by later logic in the same run (true op-to-op data-flow), to probe the data-flow-free `apply` limit at a scale shell can't paper over.
- **Production mode (both arms with the skill):** does the judgment skill move the gap? (Neither arm had it here.) For code-mode, this means the *developer* skill (OQ-deferred); for the CLI, `skill/SKILL.md`.
- **Keep CLI-strength controls** (a built-in refactor) in every set so the comparison stays honest in both directions.

---

## 9. Validity audit — did any agent "cheat" (touch the raw DM-JSON, read **or** write)?

Scanned all 28 subagent transcripts for any direct touch of a model `.dm.json` outside the intended interface (CLI: only `dmtool`; code: only the kits API). **No outcome-altering cheating; the verdict is unaffected.** But honest, corrected detail (a first pass under-counted reads until jq/python reads were explicitly included):

- **All 40 final models re-validate kernel-valid** through the oracle — nothing faked.
- **Writes — clean.** CLI arm: every model write went through `dmtool … -o` (kernel-gated); **0 raw writes**. Code arm: writes via the API. The one flag — code C k2 used the **Write tool** on the model — was investigated and cleared: it ran the EditSession Java source-launcher 7×; the Write content is its `toJson()` **output** (kernel-valid), i.e. API-authored, just persisted via the tool rather than `writeTo()`. Not hand-built JSON.
- **Reads — 3 of 14 CLI agents touched the raw model JSON** (read-only, all for *verification*, never the edit):
  - **CLI C k1** — `python3 -c '… json.load(open(model))'` ×2 (to check the added field's `scale`). *(Missed in the first pass — exactly the jq/python case to count.)*
  - **CLI C k2** — `cat model | jq …` + `jq … model.dm.json` ×2 (count `*Fee` fields / inspect structure).
  - **CLI D k1** — the **Read tool** on the model ×1.
  - The other 11/14 CLI agents touched the model **only** through `dmtool`. These reads bypass the tool's read verbs (`model read`/`field read`/`model describe`) — a purity leak the loose prompt wording (*"…and that model file"*) permitted; every **edit** still went through `dmtool` and all models are kernel-valid, so scores/verdict are unaffected.
- **Code arm:** reads the raw model pervasively (Read-tool + Jackson/`jq`) — but **that is the modality** (it must load the model to edit it: `EditSession.read`/`BoundModel.load` + Jackson inspection where the typed API lacked a reader). Not a bypass.
- **Filter caveat:** `dmtool` invoked via a `$DM`/`$DMTOOL` shell variable, piped to `jq`/`grep`, first looked like a raw read but isn't (it reads `dmtool` *output*) — corrected for in the counts above.

**Boundary fix for any re-run:** *"Operate on the model ONLY through the tool/API; never `cat`/`jq`/`python`/`Read`/`Write` the raw `.dm.json` — use the tool's read verbs (CLI) or load+`toJson` (code)."* This closes the leak the loose "…and that model file" wording allowed.

Verdict impact: **none.** Every model is genuinely kernel-valid; every *edit* was tool-/API-/kernel-gated. The leaks are verification-only raw reads (CLI) + API-output persisted via the Write tool (code C k2).

### Provenance
**Fixtures archived:** [`oq1-fixtures.zip`](oq1-fixtures.zip) — the 8 starting models + `C-spec.json` + the `E-ws/` workspace + a README with the fixture→task→gold map and re-run instructions. The `/tmp/oq1/` working copies, the `api.txt` proxy (regenerable), and the subagent transcripts (`…/subagents/agent-<id>.jsonl`) are ephemeral. k=2; opus subagents. Material-boundary audit: no agent read repo `docs/`/`src/`/tests; the raw-DM-JSON touch audit is §9 above (3 CLI agents read the raw model for verification — no outcome impact).

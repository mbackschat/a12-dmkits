# Probe report — task 01 (person + home/work address + age≥18, then extract) — 2026-06-19

| | |
|---|---|
| **Task** | [`probe/tasks/01-person-address-age.md`](../tasks/01-person-address-age.md) — 2-turn BA session |
| **Binary** | native image, `a12-dmkits 0.3.2` (HEAD: field-modify + strict-validation fixes; built 18:26) |
| **Agent** | general-purpose subagent, inherited session model (Opus 4.8); ~80k tokens, 46 tool uses, ~5 min |
| **Capture** | objective shim log `/tmp/rk-probe-sandbox/dmtool-calls.log` (69 ok calls, 3 nonzero exits + 1 duplicate) + the two final model files |
| **Integrity** | **CLEAN** — `audit-probe.sh` verdict (see below) |

## Integrity verdict — CLEAN

`./probe/audit-probe.sh a43aee4a2d8988080` → **CLEAN**, all three boundary checks pass:
- only file read was `<sandbox>/SKILL.md`; the 6 writes were the agent's own `rule-*.json` spec inputs;
- no Bash command / file op referenced the repo, the workspaces, or any `a12-kernel*` checkout;
- the model files (`person.json`, `address.dm.json`) were touched **only** by `dmtool` — no hand-edit.

So the agent learned the tool solely from the skill + the binary's self-description. The trajectory is a real measurement, and the findings below are trustworthy.

**Repeatable analysis:** `./probe/digest-probe.sh` mechanically reproduces the headline facts — it auto-flags **P1 as a RED FLAG** (a raw exception escaped a verb; exit 1), plus the two `rule read` exit-2s and the duplicate-add retry. The findings below are not eyeballed; the mechanical baseline is a script that gates.

## Outcome vs the gold (semantic)

The session **succeeded** end-to-end and the final models are kernel-valid. Against the task gold:

| gold expectation | result |
|---|---|
| Person root group + name fields | ✅ `/Person` with `FirstName`, `LastName` |
| two address groups (home + work), usual fields | ✅ `HomeAddress` + `WorkAddress`, each street/city/postalCode/country |
| age ≥ 18 **violation** rule, **guarded**, right polarity | ✅ `AGE_MIN_18 = FieldFilled(Age) And [Age] < 18` (fires when under 18; guarded so empty trips `AGE_REQUIRED` instead) |
| kernel-valid | ✅ `model validate` = valid (both models) |
| turn 2: address fields in a **separate** dm, **included twice** (home + work) | ✅ `address.dm.json` + two `modelAlias:"address"` mounts under `/Person` |
| metadata claimed but dropped? | none claimed — no false success |
| **age derived from a birth date + current date** (e.g. `DifferenceInYears(BirthDate, Today)`) | ⚠️ **divergence** — see H1 |

**Two divergences, both honest (not silent drops):**
- **Age modeled as a stored `NUMBER`, not derived from a birth date.** "ensure the person's age is >=18" is ambiguous; the agent took the literal stored-number reading. It's defensible but weaker (a stored age goes stale), and crucially it **never exercised the date operators** (`Today`/`YearFromDate`/`AddYears`/`DifferenceInYears`) — the exact native-only path that crashed in v0.1.0. → harness finding **H1**.
- **Turn-2 semantic shift, agent-flagged:** the work address went from "complete-if-started" (turn-1 conditional `AtLeastOneFieldFilled(...) And NotAllFieldsFilled(...)`) to "required-complete" (the unconditional rule it inherits from the shared dm). The agent **surfaced this as a choice point** rather than hiding it — good behavior.

## What worked well (record the positives too)

- **The self-describing surface sufficed.** The agent discovered the whole verb/param/condition surface from `--help`, `manifest`, per-verb `--help`, `operators`, and `schema rule add` — no repo access needed. Strong signal for the self-explaining-surface requirement.
- **`RK_UNGUARDED_NUMBER_COMPARISON` lint did its job.** The agent `rule check`ed the unguarded `[Age] < 18`, got a `WARNING` with a precise `fix` (`FieldFilled(/Person/Age) And <the comparison>`) and an `explain` citing KERNEL-SEMANTICS §2, and persisted the **guarded** form. This is the diagnostic contract working exactly as designed.
- **Check-before-write workflow.** Every candidate condition went through `rule check` (kernel-validated) before `rule add`. `group extract` was `--dry-run`'d first. `where-used` was run on each group/field before `group remove --cascade`. The safe-delete gate and preview affordances were used as intended.

## Findings — ranked, each to its root cause

### P1 (HIGH) — a duplicate element name escapes as a raw Java stack trace through `rule add`
- **Symptom** (log 3501–3534): `dmtool rule add <dup>` → empty stdout, stderr = `java.lang.IllegalArgumentException: Cannot have two child elements with the same name [FIRSTNAME_REQUIRED] in group '/Person'.` + a 30-line kernel/picocli stack trace, exit 1.
- **Root cause:** `RuleAddCommand.call` (`RuleAddCommand.java:102`) → `RuleValidator.check` → `checkInjected` (`RuleValidator.java:76`) → kernel `checkConsistency` → `ValidationModelConverter` → `Group.addChildEntity` throws `IllegalArgumentException` on the duplicate child name. **That exception is never caught and mapped to a structured diagnostic**, so it propagates to picocli, which dumps the stack trace. This violates the diagnostic contract (CLI-CONFORMANCE-SPEC: every domain error must be a coded, fixable envelope).
- **Why it matters:** re-adding a rule / a name collision is a *routine* authoring mistake — and it produces the worst possible output: no JSON envelope, no `fix`, an internal stack trace an agent can't parse. (It's also what the agent hit here, triggered by P3.)
- **Fix at root:** pre-check the target group's children for the name before kernel injection (or catch the kernel `IllegalArgumentException` around `checkInjected`) and emit `RK_DUPLICATE_ELEMENT_NAME` with `where` (group + name) and a `fix` ("a rule/element named `FIRSTNAME_REQUIRED` already exists in `/Person` — choose a different code, or remove the existing one first").
- **Guard to graduate:** a verb/law test that adds a duplicate-named rule and asserts (a) a clean structured diagnostic with that code + a `fix`, (b) **no stack trace on stderr**, (c) the model is unchanged. Belongs in the conformance harness as a "no raw exception escapes any verb" invariant — the fuzzer clearly doesn't cover this path yet.

### P3 (MEDIUM) — success-signal field differs across verb families; `ok` is universal but not salient
- **Symptom:** the agent's output parser keyed on `valid`; `rule add` has no `valid` key (mutations report `ok`/`outcome:"applied"`/`written`/`changed`), so it read `None`, thought the adds had failed, and **re-ran** `rule add rule-firstname.json` — which is what tripped P1.
- **Root cause:** `rule check`/`validate`/read verbs carry **both** `ok` and `valid`; mutation verbs carry `ok` (+ `outcome`/`written`) but **not** `valid`. `ok` is the universal success boolean, but the skill/`result.schema` don't make that salient, so an agent that first saw a `check` envelope over-generalized `valid` to mutations.
- **Fix at root:** make `ok` the documented universal success field — a one-line skill note ("`ok` = did the verb succeed; `valid` = kernel-validity, present only on read/check/validate") and an explicit note in `result.schema.json`. (Optionally mirror `valid` onto mutations for uniformity, but that's a contract change — prefer the doc fix.)
- **Guard:** assert every mutation envelope has `ok`+`outcome`+`written`; add the skill clarification. Partly a self-describing-surface fix; it directly de-risks P1's trigger.

### P2 (MEDIUM) — `rule read` has no "list" affordance, and its error doesn't point to the list verb
- **Symptom** (log 3536, 3587): the agent ran `rule read` (no arg) to *list* rules → `Missing required parameter: '<rulePathInModel>'` (exit 2), **twice** (a `--help` in between, then the same failing call again), before pivoting to `export person.json rules`.
- **Root cause:** `rule read` reads **one** rule's DSL and requires a path; listing rules is a *different* verb family (`export <model> rules`). The missing-parameter text is picocli's default usage — correct but **not corrective**: it never tells the agent how to list. The mental model "read the rules" maps naturally to `rule read`, but that verb reads a single rule.
- **Fix at root:** when `rule read` is invoked with no path, emit a structured diagnostic that says "to list a model's rules use `export <model> rules`; to read one use `rule read <path>`" (lower-risk than adding a list mode to `rule read`).
- **Guard:** assert `rule read` with no path emits that coded, cross-linking diagnostic.

### P4 (LOW–MED) — `group extract` leaves the sub-model's `modelInfo.name` stale
- **Symptom:** `address.dm.json` has `header.id = "address"` (correct, from `--reference address`) but `content.modelInfo.name = "person"` — copied from the source model, never reset.
- **Root cause:** the extract path clones the source model's `content` (incl. `modelInfo`) into the new sub-model and sets the new `header.id` but does not reset `modelInfo.name` to the reference.
- **Impact:** cosmetic/metadata only (kernel-valid), but a reader/agent sees an "address" model named "person". (Separately, the extracted root group keeps its source name `HomeAddress` and rule code `HOME_ADDRESS_INCOMPLETE` even under the work mount — that's inherent to extracting a specifically-named group, a usability nuance, not a defect.)
- **Fix at root:** on extract, set the new sub-model's `modelInfo.name` to the reference id. **Guard:** assert `modelInfo.name == reference` after extract.

## Harness findings (fix the probe itself)

### H1 (IMPORTANT) — task 01's prompt doesn't force the date-operator path
"ensure the person's age is >=18" lets an agent model `Age` as a stored number and bypass `Today`/`AddYears`/`DifferenceInYears` — the **highest-value surface to probe** (it's what crashed natively in v0.1.0), and this run missed it. **Fix:** tighten the turn-1 prompt to require a birth date ("the person enters their **date of birth**; ensure they are at least 18") — or add a date-forcing variant `01b` — so the native date path is actually exercised. The current task still has value (it found P1–P4), but it under-covers its stated probe intent.

### H2 (minor) — the shim logs `$*` (quote-lossy)
The call log can't show whether a condition was quoted (`--condition FieldFilled(Age) And [Age] < 18` is logged unquoted). This does **not** affect the audit (which reads the transcript) or the findings here; noting it only as a low-priority forensic nicety.

## Triage → next actions

| # | severity | root-cause fix | graduates into guard |
|---|---|---|---|
| **P1** | HIGH | catch/pre-check duplicate element name → `RK_DUPLICATE_ELEMENT_NAME` + `fix` | "no raw exception escapes any verb" conformance test (duplicate-add case) |
| **P3** | MED | `ok` = universal success in skill + `result.schema`; (opt) mirror `valid` | schema/test: every mutation envelope has `ok`+`outcome`+`written` |
| **P2** | MED | no-arg `rule read` → coded diagnostic cross-linking `export … rules` | test: `rule read` (no path) emits that diagnostic |
| **P4** | LOW-MED | extract resets `modelInfo.name` to the reference | test: `modelInfo.name == reference` after extract |
| **H1** | harness | tighten task-01 prompt to a birth date (or add `01b`) | n/a (probe coverage) |

**P1 is the headline** — a broken diagnostic contract on a routine mistake, found proactively, exactly what the probe is for. It also pairs with the planned **"applied ⇒ effected" round-trip sweep**: that deterministic guard plus a "no raw exception escapes" invariant would have caught both P1 and the original `field modify` silent drop without a bug report.

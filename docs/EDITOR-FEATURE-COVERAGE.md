# A12 editor feature-coverage audit (resumable)

> **What this is.** A **resumable audit** of how much of the A12 Tools Document-Model *editor* feature surface dmkits (modelkit/rulekit) + the `dmtool` CLI cover — driven by a user review on 2026-06-21. It records the **concept definitions** (the authoritative meaning of each editor feature), the **support matrix** (modelkit vs CLI vs whether it's explained to a CLI user), the **verification status** of each row (so a future session knows what's *confirmed* vs *still to exercise*), and the **gaps** (graduated to [`FEATURE-GAPS-LEDGER.md`](FEATURE-GAPS-LEDGER.md) §3 as G21–G23).
>
> **To resume / finish the audit:** work the checklist in [§6](#6-to-complete-the-audit-the-checklist). The matrix is **mostly checked by schema/spec-keys + existing tests**; "completely checked" means **exercising each ✅ row end-to-end through the CLI** (write→read-back→confirm effected) and closing the few 🟡 rows.

## 1. Sources (authoritative — consult-only)

| source | what it gave | where |
|---|---|---|
| **SME Document-Model BA docs** | the **definitive meaning** of every editor feature below (the editor's own property panels) | `$HOME/Desktop/a12-knowhow/sme-sme-dm-ba-docs.md` (line refs per concept in §3) |
| merged kernel **BA doc** | cross-check (consult-only) | `../a12-kernel/documentation/_merged/kernel-ba.md` |
| dmkits **source** checked | the support facts | `cli/.../field/FieldSpec.java`, `cli/.../resources/schema/field-add.schema.json` + `add.schema.json`, `cli/.../group/*`, `cli/.../rule/RuleAddCommand`/`RuleModifyCommand`, `cli/.../config/ConfigModifyCommand`, modelkit `ModelDocument`/`ElementDraft`/`FieldAttrs`/`FieldModification`/`Requiredness`, `edit/EditSession`; tests `ElementMetadataEditTest`, `FieldMetadataRoundTripTest`, `ConfigVerbTest`, `RuleVerbTest` |

## 2. Concept glossary (definitive, from the SME doc)

- **Internal description** (`internalDescription`) — *technical documentation*, per-locale. In A12 it is shown **read-only to *Form* modelers** (the Edit Control dialog of Form Models referencing this Document Model). Not seen by end users. (SME §868–874)
- **External description** (`externalDescription`) — a **hint shown to the *end user* in the UI**, per-locale. (SME §196, §875)
- **Global** — a **tag** marking fields "of fundamental relevance"; during a **partial** validation, all global fields are added to the relevant set. (SME §850–852)
- **Transient** — the field is **not persisted** (e.g. interim results of a complex calculation). (SME §854–856)
- **"Only if Parent Group is filled"** — a **modifier on Required** (= relative-to-parent): the field is mandatory only when its parent group contains data; extends `FieldNotFilled(F)` to `GroupFilled(G) And FieldNotFilled(F)`. Maps to dmkits `Requiredness.REQUIRED_IF_PARENT_PRESENT` / the `required:"ifParentPresent"` spec value. (SME §825–836)
- **Use Default Error Messages** — a **field** property: when set, a default localized text (e.g. "This field is required.") is shown for the field's own constraint errors (**required**, **enum**, amount, …); when unset, **custom per-locale text** is given per field. Distinct from a *rule's* messages. (SME §840–842, §1123 for enum, §974 for amount)
- **Suggestions** — a **non-restricting** localised **hint list** for a string field: the user may pick a predefined value *or* type their own; validated against the field's other restrictions (e.g. pattern). (SME §921–929)
- **Helper Text** — per-locale inline help shown to the end user. **Annotations** — name→value metadata. **Type Definition** — a field takes its type from a shared/named type definition.
- **Severity / Level** — a Validation Rule's level: **Error** (blocks save/navigation, red) / **Warning** (orange, non-blocking) / **Info** (blue, non-blocking). (SME §1227–1235; "Condition" = the rule's condition, "Level" = severity)
- **Timezone**, **Supported Characters** — model-level document config.

## 3. Support matrix

Legend — **modelkit** = the builder/`EditSession` substrate · **CLI** = a `dmtool` verb/spec exposes it · **explained?** = is it described in `--help`/`schema` for a CLI user. Verification: 🟢 confirmed (schema/keys + a test) · 🟡 declared but not yet exercised end-to-end · 🔴 gap.

| # | Feature | modelkit | CLI | explained to CLI user? | status |
|---|---|---|---|---|---|
| 1 | **Group — Label** | ✅ `EditSession.setLabel` (any element) | 🔴 no verb (`group add`=parent/name/repeatable; `group modify`=repeatability only) | — | 🔴 **G21** |
| 1 | **Group — Internal/External description** | ✅ `setInternal/ExternalDescription` (tested: `ElementMetadataEditTest`) | 🔴 not exposed | — | 🔴 **G21** |
| 1 | **Group — Annotations** | ✅ `setAnnotation` | 🔴 not exposed | — | 🔴 **G21** |
| 2 | **Field — Type Definition (reuse)** | ✅ | ✅ `--typedef` / spec `typedef` | ✅ schema | 🟢 |
| 2 | **Field — Required** | ✅ | ✅ spec `required` | ✅ schema | 🟢 (`FieldMetadataRoundTripTest`) |
| 2 | **Field — "Only if parent filled"** | ✅ `REQUIRED_IF_PARENT_PRESENT` | ✅ `required:"ifParentPresent"` | ✅ ("required only if the parent group is filled") | 🟢 |
| 2 | **Field — Global** | ✅ | ✅ spec `global` | ✅ ("relevant to partial validation") | 🟡 declared (exercise to confirm effected) |
| 2 | **Field — Transient** | ✅ | ✅ spec `transient` | ✅ ("present at runtime, not persisted") | 🟡 declared |
| 2 | **Field — Error messages (use-default + locale/text)** | 🔴 only string `patternMessage` | 🔴 only `string.patternMessage`; **no use-default toggle + custom required/enum text** | partial | 🔴 **G23** |
| 3 | **Field config — Suggestions (string)** | ✅ | ✅ `string.suggestions` (per-locale) | ✅ schema | 🟡 declared (exercise) |
| 3 | **Field config — Internal/External description** | ✅ | ✅ `internalDescription`/`externalDescription` | ✅ **terse** (see Minor below) | 🟢 (`FieldMetadataRoundTripTest`) |
| 3 | **Field config — Helper Text** | ✅ | ✅ `helperText` | ✅ schema | 🟢 |
| 3 | **Field config — Annotations** | ✅ | ✅ `annotations` | ✅ schema | 🟢 |
| 4 | **Rule — Internal/External description** | ✅ `setInternal/ExternalDescription` on a rule path (tested: `ElementMetadataEditTest`) | 🔴 not in `rule add` spec / `rule modify` (`--comment` is the inline `;;` note, a *different* thing) | — | 🔴 **G22** |
| 4 | **Rule — Annotations** | ✅ `setAnnotation` on a rule path | 🔴 not exposed | — | 🔴 **G22** |
| 5 | **Condition — Level (Error/Warning/Info)** | ✅ Severity | ✅ `--severity` / spec `severity` | ✅ | 🟢 (`RuleVerbTest`) |
| 6 | **Doc config — Timezone** | ✅ | ✅ `config modify --time-zone` | ✅ | 🟢 (`ConfigVerbTest`) |
| 6 | **Doc config — Supported Characters** | ✅ | ✅ `config modify --supported-character` | ✅ | 🟢 (`ConfigVerbTest`) |

**Minor (sharpen, not a gap):** the field schema's `internalDescription`/`externalDescription` descriptions are terse ("internal/maintenance text" vs "end-user-facing text"). The precise A12 semantics — **external = the end-user UI hint; internal = technical doc shown read-only to *Form* modelers** — would help a CLI user choose. Worth tightening when G21/G22 land (so the same wording covers group/rule/field).

## 4. The gaps (→ FEATURE-GAPS-LEDGER §3)

- **G21 — Group metadata in the CLI** (label, internal/external description, annotations). Substrate done + tested (`EditSession.setLabel`/`setInternal/ExternalDescription`/`setAnnotation` over any element path); **no CLI verb exposes it for groups**. Fix: extend `group modify` (or `group add`) with `--label`/`--internal-description`/`--external-description`/`--annotation`. *Low-med, mostly thin verb surface.*
- **G22 — Rule metadata in the CLI** (internal/external description, annotations). Same substrate, same tested support over a rule path; **not in the `rule add` spec or `rule modify`**. Fix: add the keys to the rule spec + `rule modify`. *Low-med, thin surface.*
- **G23 — Field-level error messages** ("Use Default Error Messages" toggle + custom per-locale text for the field's **required**/**enum** errors). Only the string `patternMessage` exists today; `enum.invalidValueMessage` is single-string + no use-default. The general control needs **new modelkit support** (write the field's `errorMessages` + the use-default flag) + CLI keys. *Medium-large — needs modelkit work, then CLI.*

> The **full-doc subagent sweep (§7)** found more beyond these — new gaps **G24–G27** (group index field, typedef enum/restrictions, roles management, model-id rename) + surface defects (the INFO-severity drift, undocumented `$$` escape + date format-sets) + a long tail. Work **§7** alongside this list.

## 5. What is already strong (don't re-litigate)

The **field** surface is near-complete: `required` (+`ifParentPresent`), `global`, `transient`, `internalDescription`, `externalDescription`, `helperText`, `annotations`, `suggestions`, per-kind config (string pattern/length, number scale/bounds/unit, enum values/labels/categories) — all via the `field add`/`field modify` spec, schema-validated, kernel-checked. The modelkit element-metadata substrate (`EditSession.{setLabel,setInternalDescription,setExternalDescription,setAnnotation}`) already works for **field + group + rule** alike — the gaps are only that the CLI exposes it for fields and not yet for groups/rules.

## 6. To complete the audit (the checklist)

A future session: tick each to take the matrix from "schema-checked" to **completely checked**.

- [ ] **Exercise the 🟡 rows end-to-end through the CLI** (write→`field read`/`model describe`/JSON read-back→assert effected), upgrading to 🟢: `global`, `transient`, `string.suggestions`. (The `FieldMetadataRoundTripTest` pattern is the model; consider adding these to it.)
- [ ] **Re-confirm the 🟢 rows** are genuinely round-trip-locked (not just schema-declared) — most already are via the named tests; spot-check any that aren't.
- [ ] **G23 scope check:** confirm there is *no* field-level error-message support beyond `string.patternMessage` (grep `errorMessage`/`useDefault`/enum-message/required-message returned empty on 2026-06-21) — and decide the modelkit shape (`requirednessConfig` error text + enum-value error text + a use-default flag).
- [ ] **Implement G21/G22** (thin CLI exposure of the existing metadata substrate) and **G23** (modelkit + CLI), each TDD'd + ledger-graduated; then re-run this matrix.
- [ ] **Sharpen** the internal/external schema wording (Minor above) when G21/G22 add group/rule descriptions, so one phrasing covers all three.
- [ ] When a row ships, **move its gap row to FEATURE-GAPS-LEDGER §2** and flip the status here.
- [ ] **Act on the §7 reverse-audit findings** (below) — start with §7a (the surface defects).

## 7. Reverse-audit findings — full-doc subagent sweep (2026-06-21)

A 5-subagent sweep of the **entire** SME doc (not just the §3 areas) — each agent extracted every capability in a section range and checked it against the CLI/dmkit. Below are the items **beyond G21–G23**, by action. New ledger gaps: **G24–G27** (recorded in FEATURE-GAPS-LEDGER §3).

### 7a. Self-describing-surface defects / drift (fix — the surface is *wrong* or silent)
- **INFO severity is undiscoverable (defect).** `rule add` with `severity:"INFO"` works end-to-end (kernel accepts + persists; `rule modify --severity` advertises INFO; `dsl.Severity` has INFO) — but the **`rule add` schema says "ERROR | WARNING (default ERROR)"**, **`rule check --severity` paramLabel is `ERROR|WARNING`**, and **`DraftRule` javadoc says "ERROR/WARNING".** A cold agent can't discover INFO on the add/check surface. **Fix (≈3 lines, high value):** align the `rule add` schema description + `rule check` paramLabel + `DraftRule` javadoc to `ERROR | WARNING | INFO`. *(SME 1231.)*
- **Error-message `$$` escape undocumented.** The `messages.text` schema documents `$F$`/`$F.value$`/`$#Group$` interpolation but not the `$$` literal-dollar escape (SME 1308) — a message with a currency amount silently mis-interpolates. Fix: add the `$$` note to the schema.
- **DateFragment/DateRange `format` is an undocumented free string.** The fixed sets (4 fragment formats `MM`/`YYYY`/`YYYY-MM`/`MM-DD`; 5 range formats — SME 1012–1015, 1023–1029) aren't enumerated or exampled in the schema/modelkit; undiscoverable from the self-describing surface (only the kernel rejects a bad value). Fix: enumerate/example them in the `dateFragment`/`dateRange` schema blocks.

### 7b. New capability gaps (→ ledger G24–G27)
- **G24 — Group Index Field via the CLI.** modelkit has it (`DocumentModelDraft.GroupBuilder.indexField` → `indexFieldName`), but **no `group add`/`group modify` flag**. It's the repetition row-key that auto-generates the mandatory + `RepetitionNotUnique` rules and enables Semantic Index / Parallel Iteration (SME 1167–1184). G21-class shape (substrate present, no verb) but distinct from G21. *Also verify:* modelkit's `indexField` requires `repeatable`, but SME 1184 allows an index field on a **non-repeatable** group — possible modelkit over-restriction.
- **G25 — `typedef add` can't create ENUMERATION or restricted typedefs.** Only STRING/NUMBER/DATE/BOOLEAN/CONFIRM **bare** specs; the SME's *primary* typedef use case is reusable **enums** (genders/countries) + **restricted** types (email pattern/length) — SME 1517–1525. modelkit supports it (`addTypeDefinition(id, FieldTypeSpec)`); the CLI reaches it only via `typedef extract` from a pre-built field. Fix: `typedef add` accepts an enum kind + `--values` and per-kind restriction flags (reuse the field-config plumbing).
- **G26 — Roles management on the model.** No verb sets/adds/removes the model's own `roles` annotation, and no `model new --role`. We only *lint* roles (`workspace roles`). (`config modify --annotation` could set it raw, but there's no first-class roles editor.) SME 336, 679–682.
- **G27 — Model-id rename.** No verb changes a model's header `id` (`config modify` won't touch it). SME 609–613. *(The **cross-model** refactoring cascade — rename a sub-model, repair every including model — is already FEATURE-GAPS-LEDGER §4 **out-of-scope** (OQ1); G27 is only the single-model id rename.)*
- **Smaller new gaps** (record; lower priority): Enum **Category `description`** (per-category metadata; `EnumCategory(name,values)` has no description slot — SME 1133) · **locale removal** (we add via `--add-locale`/G16, no remove) · **no "remove import" inverse** for an imported typedef-model (verify — may force a header hand-edit, the G15–G20 anti-pattern) · **no `computation move`** (field/group move exist) · **"Allow Differing Decimal Places"** one-step flag for rules/computations (today: hand-prepend `@SuppressWarning(MVK_INVALID_COMPARE_DEC_PLACES)` — SME 1275–1283, 1362) · **"Insert From DM"** (one-time copy of another model's *resolved* content as plain children — distinct from `include add`/`include inline`; SME 508–510).

### 7c. Scope-sharpening of existing gaps
- **G22** also covers **computation** internal/external description + annotations (same CLI-exposure gap + substrate) — not just rules.
- **G23**: an enum invalid-value message *does* exist (`enum.invalidValueMessage`) but is **single-string for all locales** with **no use-default toggle** — sharper than "only `patternMessage` exists." The field error-message gap spans **required + enum** (both need per-locale + a use-default flag).

### 7d. Kernel-delegated, no early local diagnostic (mostly accept — F11 "loud at build")
Kernel-rejected on `validate`, but no early/local diagnostic: name format/length/`xml`-prefix rules (field + model name), pattern ≤1000-chars + valid-regex pre-check, amount-unit ⇒ {0,2} decimal-places coupling, the 5 include-ability preconditions, the local-vs-imported typedef-mode mixing constraint. Correctness sound; pre-flight-diagnostic opportunities, low priority. *Also: `field add` doesn't pre-gate sibling-name collision the way `field rename` does (`RK_NAME_EXISTS`) — a minor asymmetry.*

### 7e. Documented divergences from the editor (intentional supersets — doc-note, not gaps)
- `group attachment --repeatable` allows multiple attachments; the editor disallows multi-upload (SME 1431).
- `group multiselect --max-rows` is a configurable knob; the editor fixes repeatability at 999,999 (SME 1441).
- We split the editor's single "Exclude Rules" into `--exclude-rules` + `--exclude-computations`.
- `date` fields carry an extra `format` knob (SME says date has no config).

### 7f. Likely intentional non-goals (expert-mode / compatibility-only — record, don't build)
SME §Expert-Mode (277–291) marks these "not recommended / compatibility-only": String `noValueValidation`; Number `min/maxLength`, `maxIntegerDigits`, `positivesOnly`; Date `partiallyKnownAllowed`, must-be-after-1900; DateRange year-interpretation. Treat as non-goals unless a real model needs one.

### 7g. Verification TODO (carried from the sweep — confirm before acting)
- Does `typedef read` surface the **source provenance** (local / included-from-`<model>` / imported-from-`<model>`) the editor's Source column shows? (SME 1533/1581/1587)
- Is there any CLI path to **drop a typedef import** (the editor's "REMOVE IMPORT")? (SME 1591)
- Is an index field on a **non-repeatable** group actually kernel-legal (SME 1184) vs modelkit's `requires repeatable`?

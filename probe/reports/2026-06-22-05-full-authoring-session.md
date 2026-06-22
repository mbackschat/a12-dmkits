# Probe report — task 05 (full authoring session), 2026-06-22 (release binary)

**Headline: CLEAN — 5 turns, 0 internal errors, audit CLEAN 6/6, 0 RED FLAGS.** A re-run on the release binary. The broad from-scratch authoring surface — person + two address groups, an age≥18 violation rule, an explicit `de_DE` add (bilingual labels/messages), roles/users attach (`workspace roles` lints clean), repeatability max + a primary-flag, and a `Having`-filtered "at most one primary work address" rule, then extract→include — authored kernel-valid throughout. One substantive structural finding (F1 — extract/include asymmetry); the rest are by-design / diagnostic-covered.

(An earlier same-day run filed a "standalone vs apply `patternMessage` divergence" headline that was **disproven on re-verify** (both paths produce identical `{en_US}` state, both reject `--add-locale` until covered) — the objective backstop working as designed; this clean run supersedes it.)

## Provenance & integrity (the objective backstop)

| | |
|---|---|
| native binary | `a12-dmkits 0.6.0` (kernel 30.8.1) — release candidate, gate GREEN |
| start | **clean** (from-scratch person model) |
| audit | **CLEAN 6/6** — `Person_DM.json`/`Address.dm.json` mutated only by dmtool; no raw read |
| digest | **0 RED FLAGS** — 7 nonzero exits, all legitimate (a self-caught re-applied `apply`, the `--add-locale --dry-run` + bilingual `apply --dry-run` cascade probes, a `rule read --path` locator slip, a bare-string `roles.yaml` mistake, an include-mount dry-run probe) |

## Findings (classified)

| # | finding | target | class | resolution |
|---|---|---|---|---|
| 05-F1 extract-asymmetry | "extract a shared sub-model and include it for both" can't stay symmetric when one mount needs an extra per-instance field: `group extract` pulls the group's **entire** content (would drag `WorkAddress/IsPrimary` into the shared Address) and `include add` only mounts as a **new child** group — so Work's address fields land a level deeper (`WorkAddress/Location/Street`) than Home's (`HomeAddress/Street`) | CLI (refactor capability) | **by-design limitation** | recorded — extract/include is whole-group + child-mount by design; a per-instance extra field forces the asymmetry. A "partial extract" / flattening mount is a future *capability* (earns-a-verb bar, CLI-SPEC §6), not a defect; the produced model is valid |
| 05-F2 modify-kind | `field modify` needs an explicit `kind` to apply a same-kind config block; the schema doesn't flag it | CLI/material | **by-design** | the same item as 02-modify-retype: modify **replaces** the type, and the explicit `kind` prevents the silent partial-config drop (probe 05-modify-drop). Softening it reintroduces that ambiguity; the diagnostic already gives the precise corrective |
| 05-F3 rule-read-locator | `rule read --path` → "Unknown option" | CLI | diagnostic-covered | the locator corrector fires (positional `<rulePathInModel>`) — four-lane: no skill note |
| 05-F4 roles-yaml-help | the `roles.yaml`/`users.yaml` shapes aren't shown in `workspace roles --help` (the agent wrote a bare-string entry, got a precise reject) | CLI/material | minor (help-doc) | candidate — name the YAML shape in `--help`; the diagnostic already taught it (`- name: admin`, not `- admin`) |
| — | the explicit `de_DE` add + `--add-locale --dry-run` scoping, roles/users attach (0 lint warnings), repeatability max + flag, the `Having`-count primary rule, extract→include — all worked; both rules survived the refactor | — | **positive** | — |

## Replay log (verbatim, in order)
1. create a document model for a person with a home and work address … age >= 18.
2. add a German locale to the person model.
3. create some users and roles and attach them to the model.
4. change the repeatability of the work address to 10, and somehow flag the main work address.
5. move the fields of the address group to a separate dm and include it for both home and work.

## Self-audit
CLEAN: every model mutation via dmtool verbs; `*.json`/`*.yaml` were inputs; no raw `.dm.json` read. Audit 6/6, digest 0 RED FLAGS.

## Resolution (2026-06-22)
- **CLEAN re-run on the release binary** — no crash, no defect; the broad authoring + bilingual + roles + repeatability + extract/include surfaces behaved as specified.
- **05-F1 — recorded, by-design** (extract/include is whole-group + child-mount; a per-instance extra field forces the asymmetry — a future partial-extract capability, not a defect). **05-F2 — by-design** (modify replaces the type; explicit `kind` guards the partial-drop class). **05-F3 — diagnostic-covered.** **05-F4 — minor help-doc candidate.**

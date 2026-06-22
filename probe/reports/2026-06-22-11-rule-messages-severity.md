# Probe report — task 11 (rule messages, severities, token interpolation), 2026-06-22

**Headline: a NATIVE-ONLY CRASH (exit 70 / `RK_INTERNAL_ERROR`) — the probe's highest-value catch.** Authoring messages with interpolation tokens (`$Field.value$`, `$Field$`, `$#Group$`) crashes the native binary: the kernel's error-text **parameter** parser can't load its `LexerTerminals` resource bundle in the native image (`MissingResourceException` → `ExceptionInInitializerError`). It fires on `rule add`, `rule modify`, and even `--dry-run`, and the schema *advertises* these tokens — so an agent following the tool's own self-description hits the crash on the documented happy path. Plain text and the literal `$$` escape are unaffected. Severities (`WARNING`/`INFO`) and the bilingual turn worked. This is exactly the native-only class the probe exists to catch (invisible to the JVM eval).

## Provenance & integrity (the objective backstop)

| | |
|---|---|
| native binary | `a12-dmkits 0.6.0` (kernel 30.8.1; A12 Tools 2025.06-ext5) — the pre-fix binary that carried the gap |
| start mode | **fixture** — seeded from `cli/src/test/resources/models/order-ruled.dm.json` |
| anti-cheat audit | **CLEAN 6/6** — `order-ruled.dm.json` mutated only by dmtool; no hand-edit; no raw `.dm.json` read |
| digest | **8 RED FLAGS** — exit 70 `ExceptionInInitializerError` on every `$…$`-token `rule add`/`rule modify` (incl. `--dry-run`); the non-token turns clean |

## Findings (classified)

| # | finding | target | class | resolution |
|---|---|---|---|---|
| 11-crash | any error-text PARAMETER token (`$Field.value$`/`$Field$`/`$#Group$`) crashes the native binary — `ErrortextParameterMultiLanguageHandler.<clinit>` can't load the `LexerTerminals` ResourceBundle (native resource-registration gap); fires on add/modify/`--dry-run` | native build | **DEFECT (native crash, exit 70)** | **FIXED** — a `:cli` test now exercises a token message so the test-suite-driven capture registers the bundle; a native-battery trip-wire guards the class; re-captured + rebuilt (gate GREEN) |
| 11-rule-read-severity | `rule read <path>` returns `condition` + `messages` but **not** `severity` (nor the rule group) — after `rule add --severity WARNING`/`INFO` the setting can't be confirmed through the sanctioned structured surface | CLI (read-gap) | **DEFECT (observability)** | recorded — a clean fix candidate (add `severity` to the `rule read` envelope); a read-gap sibling |
| 11-skill-bilingual | adding a locale (`--add-locale`) requires a message in the new locale for **every** existing rule/computation (not just edited ones) — surfaced only as a terminal `MVK_ERROR_MESSAGE_FOR_LANGUAGE_MISSING`; the skill's bilingual note covers the enum-label asymmetry but not this rule-message scope | skill | DX gap | recorded — a one-line skill note candidate (the `config modify --add-locale` help already says it; the skill doesn't) |
| — | `WARNING`/`INFO` severities authored fine; the `$$` literal-dollar escape works (not a parameter, so the param parser is never entered); the `RK_UNGUARDED_NUMBER_COMPARISON` lint + `MVK_NEG_CONDITION_IN_ITERATION` guidance were exactly right | — | **positive / prior fix confirmed** | — |

## 11-crash — root cause + the fix (this session)

```
Caused by: java.util.MissingResourceException: Can't find bundle for base name
  com.mgmtp.a12.kernel.core.service.internal.errortext.grammar.languages.LexerTerminals, locale en_US
  at …ErrortextParameterMultiLanguageHandler.<clinit>(…:55)
  at …MissingRegistrationUtils.runIgnoringMissingRegistrations(…)
```
The error-text **parameter** parser (distinct from the condition parser-error bundle, which the gate battery already covered) runs only when a message has a `$…$` token. **No `:cli` test exercised that path**, so the test-suite-driven native capture never recorded the `LexerTerminals` bundle access — and the native image shipped without it. (The `:`/`:adapter` `MessageInterpolationTest`s exist but the capture runs only the `:cli` suite.)

**Root fix — at the mechanism, not the symptom:** rather than hand-registering one bundle, add the missing *shape* to the test suite that drives the capture — `RuleVerbTest.addPersistsARuleWhoseMessageInterpolatesValueAndRowIndexTokens` validates a token-bearing message through `rule add`, so the capture records the bundle automatically (and any sibling resource the param parser reaches). The class is then guarded two ways: that `:cli` test (so the capture keeps covering it) **and** a native-battery check (`error-text PARAMETER tokens`, `scripts/native-battery.sh`) that drives a token message through the native binary — the trip-wire if the gap ever returns.

## Replay log (verbatim, in order)
1. each line item's count must be greater than zero — and the error must show the entered count in its text.
2. when there are several items, the message should say which line number is wrong.
3. add a rule: the backorder quantity shouldn't exceed 1000 — but make it a warning, not a hard error.
4. add an informational note when express priority is chosen (just info, no blocking).
5. the count message should also mention that orders over $1000 need approval — with a literal dollar sign.
6. all of these messages should also read correctly in German.

## Self-audit
CLEAN: every mutation via dmtool verbs (`rule add`/`rule modify`/`apply`); hand-authored files were rule-spec/apply-op inputs + the from-scratch repro (created via `model new`, edited only via dmtool); no raw `.dm.json` read (the `rule read` severity shortfall was filed as 11-rule-read-severity, not papered over). Confirmed by the audit (6/6); the digest's 8 RED FLAGS are the crash itself.

## Resolution (2026-06-22)
- **11-crash — FIXED.** The native capture now covers the message-token path (the new `:cli` token-message test), so the `LexerTerminals` bundle is registered; re-captured + rebuilt, and the native gate (with the new `error-text PARAMETER tokens` battery check) is GREEN — the minimal repro that crashed (a `$/…/.value$` / `$#…$` message via `rule add`/`--dry-run`) now validates cleanly. This is the same class as the v0.1.0 date-operator and v0.6.0 annotation native crashes: an un-exercised kernel shape → a capture gap. NATIVE-IMAGE/KERNEL notes updated with the new re-capture-trigger shape.
- **11-rule-read-severity — recorded, a clean fix candidate.** Adding `severity` (and the rule group) to the `rule read` data envelope closes a genuine observability gap (can't confirm a severity the verb just set). A self-contained read-gap fix; pending a decision (it touches the verb output shape → `result.schema` + a demo).
- **11-skill-bilingual — recorded.** A one-line skill note (adding a locale needs a message in it for *every* existing rule, not just edited ones); the skill is the measured surface, so it gets the careful re-read+verify pass before any edit.

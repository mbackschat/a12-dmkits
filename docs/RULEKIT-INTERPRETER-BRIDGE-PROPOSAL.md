# Topic 2 proposal: Compile rulekit programs into prepared interpreter programs

## Status

Fresh proposal based on the current Java rulekit AST, renderer, catalog, interpreter parser/evaluator, public compatibility surface, and performance constraints, followed by an independent adversarial review. It deliberately does not reuse the earlier “move the interpreter AST into a shared Kotlin DSL core” recommendation.

The API and module names are illustrative. This document proposes a direction; it does not authorize implementation.

[Topic 1](OPERATOR-API-PROPOSAL.md) is a TypeScript-first npm product implemented once in Kotlin Multiplatform, with secondary Java/JVM support. This Topic 2 bridge is an optional JVM consumer of Topic 1’s common prepared-program boundary. It must not make Topic 1 Java-first or determine Topic 1’s API and delivery sequence.

## 1. Executive recommendation

Do **not** make rulekit authoring and interpreter execution share one AST.

Use an explicit compiler boundary:

```text
Java rulekit authoring tree
          │
          │ canonical English DSL
          ▼
[Topic 1](OPERATOR-API-PROPOSAL.md) preparation API
          │
          ▼
opaque interpreter execution form
          │
          ▼
evaluate repeatedly
```

The first implementation should be narrow:

1. Add [Topic 1](OPERATOR-API-PROPOSAL.md)’s owner-bound prepared **candidate rule** API.
2. Add an optional, kernel-free JVM bridge module that converts a rulekit `Rule` into candidate metadata plus canonical condition text.
3. Parse once and reuse the interpreter’s existing execution AST and rule-evaluation machinery.
4. Pair the rulekit authoring model and interpreter evaluation model from the same expanded model, with an explicit fingerprint/provenance contract.
5. Preserve the current Java rulekit AST and its public records.

Do not define another prepared-expression or prepared-computation API in Topic 2. Topic 2 consumes [Topic 1](OPERATOR-API-PROPOSAL.md)’s boundary; it does not duplicate it.

Topic 1 includes a bounded numeric-expression observer in its first useful TypeScript release. After that common observer exists, this bridge may render a rulekit `NumberExpr` and prepare it. Numeric-expression bridging remains a later Topic 2 integration, not part of the first rule bridge.

This design avoids a second implementation of runtime document evaluation and avoids a recursive Java-AST-to-interpreter-AST mapper. It does **not** eliminate every kind of duplication in the repository:

- rulekit still owns typed authoring structure, rendering, linting, polarity analysis, and other authoring semantics;
- the interpreter still owns parsing, normalized execution structure, and runtime document evaluation;
- the bridge still performs a shallow metadata mapping from `Rule` to the candidate-rule DTO;
- renderer and parser necessarily know complementary parts of the language syntax, guarded by shared catalog/conformance data.

The compiler boundary is a sound interoperability foundation. It is **not by itself a proven gradual rulekit-to-Kotlin migration plan**. Rulekit’s renderer, linter, explanation, transforms, validation, reading, writing, and public sealed AST are tightly coupled. Moving those independently requires a later dependency-cut and compatibility proposal.

There is an unavoidable migration trilemma for the recursive public AST:

```text
preserve the current Java record API
        + avoid temporary duplicate representations
        + migrate the AST gradually

Choose at most two.
```

If a full rulekit migration is approved later, either accept an atomic breaking AST cutover or accept a temporary compatibility/adapter representation. This proposal does not hide that cost.

## 2. Motivation

### 2.1 Desired client behavior

Rulekit can construct a typed expression or rule:

```java
NumberExpr discountedTotal =
    sum(model.flatten("/Order/Lines*/Amount"))
        .times(
            number(1).minus(
                model.num("/Order/DiscountPercent")
                    .asNumber()
                    .dividedBy(number(100))
            )
        );
```

The equivalent A12 DSL is:

```text
Sum(Lines*/Amount) * {1 - [DiscountPercent] / 100}
```

The desired behavior is to author through rulekit, prepare once, and evaluate through the interpreter without implementing `Sum`, arithmetic, field access, emptiness, iteration, or decimal behavior again.

### 2.2 Why a full migration is not the current goal

Rulekit is not only an expression builder. It includes:

- model-bound typed references;
- conditions and calculations;
- rules and computations;
- bilingual rendering;
- comments and read fidelity;
- model reading/writing and editing integration;
- transforms;
- operator usage and explanations;
- linting and diagnostics;
- kernel-backed consistency validation.

Most current consumers are Java/JVM consumers, and the public AST uses Java records and Java 21 sealed-pattern traversal. Replacing all of that to evaluate one rulekit-authored rule would be disproportionate.

### 2.3 Why a boundary still matters

Without an explicit boundary, clients will be tempted to:

- render and parse on every evaluation;
- reimplement operators in Java;
- translate every rulekit node manually into interpreter nodes;
- expose interpreter internals as public API;
- couple rulekit directly to the interpreter and its Kotlin runtime;
- run a rule against a different model than the one used to author it.

A prepared compiler boundary prevents those failure modes while keeping the two representations optimized for their actual purposes.

## 3. Source-level finding: the ASTs are not redundant

### 3.1 Rulekit authoring representation

Rulekit exposes a large closed Java DSL with specialized condition variants and roughly forty operator-specific `Calculation` record types.

It carries or supports:

- `ValueKind`;
- model-bound field/group handles;
- scale-faithful `BigDecimal` literals;
- path-style provenance;
- correlation markers;
- Java record components and equality;
- Java 21 exhaustive pattern switches;
- identity-keyed comments;
- bottom-up condition transforms;
- canonical rendering;
- linting and explanation;
- editing and read fidelity.

Those are source-authoring concerns.

### 3.2 Interpreter execution representation

The interpreter uses a smaller generic KMP execution tree:

- `Expr.Field`;
- `Expr.Star`;
- `Expr.Func(name, args)`;
- `Expr.Arith`;
- `Cond.Pred(name, args)`;
- `Cond.Cmp`;
- logical nodes.

It carries execution concerns:

- normalized absolute paths;
- star `bindDepth`;
- `$` correlation markers;
- cached literal runtime values;
- tolerant unknown function/predicate names in selected paths;
- `Dec`, `Val`, and `Tri`;
- kind/formal/repetition behavior resolved through `ModelDef`, `Document`, and evaluation context.

It deliberately does not retain comments, authored path spelling, Java record identity, or operator-specific public source types.

### 3.3 Decimal literal conflict

Rulekit preserves authored decimal scale:

```text
0.00 renders as 0.00
```

Its `BigDecimal` record equality is scale-sensitive.

Interpreter `Dec` equality is value-based and scale-insensitive. The JavaScript `big.js` implementation also canonicalizes trailing-zero spelling.

A single literal node would therefore need at least:

- lexical text for authoring rendering/equality;
- semantic decimal for evaluation.

Adding both to every interpreter literal changes representation, equality, footprint, and potentially initialization cost. This is a redesign, not an extraction.

### 3.4 Reference conflict

Rulekit references carry declared authoring kind and path-style information. Interpreter references carry normalized execution paths and binding data.

Putting all authoring metadata into every execution node enlarges the hot AST. Moving it into wrappers or side maps retains another representation. Neither is a free “shared AST.”

### 3.5 Public Java compatibility conflict

Replacing Java records with Kotlin data classes changes:

- constructors;
- accessor names;
- record reflection;
- record/deconstruction patterns;
- binary signatures;
- equality details;
- traversal ergonomics.

A root façade can preserve convenient builder methods, but it cannot preserve recursive public record components after the record tree disappears.

## 4. Precise meaning of “no duplication”

### 4.1 What this proposal prevents

- no second implementation of runtime document evaluation;
- no Java or TypeScript implementation of arithmetic/date/iteration/formal semantics;
- no recursive rulekit-node-to-interpreter-node mapper;
- no third AST in the initial bridge;
- no copied operator registry in the bridge;
- no parse on each repeated evaluation after successful preparation.

### 4.2 What remains intentionally separate

```text
rulekit
  source typing, authoring invariants, rendering, linting, explanation

interpreter
  parsing, normalized execution IR, document/runtime evaluation

bridge
  model provenance, shallow rule metadata, prepare orchestration
```

Rulekit already contains semantic projections in its linter, polarity reasoning, empty-value analysis, and constant reasoning. Runtime document evaluation remains solely in the interpreter, but it would be false to claim that all semantic knowledge lives there.

These projections should be catalog-derived or differential/conformance-guarded where practical. Auditing them is useful future work, but it is not solved by changing AST ownership.

### 4.3 Shallow mapping is still mapping

Preparing a rulekit `Rule` requires mapping:

- declaring group;
- name/synthetic path;
- absolute error field, from which Topic 1 derives interpreter-relative metadata;
- rendered condition text;
- code;
- severity;
- localized messages.

That adapter is small and stable compared with recursively translating every condition/calculation node, but it is real code and requires exhaustive tests.

## 5. Architecture

### 5.1 Modules

Suggested optional module:

```text
:rulekit-interpreter
    depends on :rulekit
    depends on :interpreter JVM target
    contains no kernel dependency
```

Dependency direction:

```text
:rulekit ─────────────┐
                     ├──► :rulekit-interpreter
:interpreter (JVM) ──┘
```

The interpreter never depends on rulekit. Rulekit does not acquire a mandatory interpreter/Kotlin runtime dependency. Ordinary rulekit JVM consumers are unaffected unless they opt into the bridge.

Kotlin/JS dependencies are not relevant to an ordinary JVM dependency closure; the actual concern is avoiding mandatory Kotlin/JVM/interpreter runtime dependencies for consumers who only author/render rules.

### 5.2 Paired model

The bridge must not accept an arbitrary rulekit tree and an arbitrary interpreter session with matching-looking paths.

Preferred shape:

```text
one resolved, expanded DM-JSON model
                 │
        stable model fingerprint
          ┌──────┴──────┐
          ▼             ▼
  rulekit BoundModel   evaluation session
          │             │
          └──── paired bridge ────► prepared handle
```

The include resolver runs before pairing, or both sides receive the exact same expanded model text. The fingerprint is calculated over the canonical expanded model contract, not the unexpanded include shell.

The current rulekit `Rule` record does not carry model identity. Implementation must therefore choose and enforce one of:

1. a bridge-owned `AuthoredRule` wrapper carrying the paired-model fingerprint;
2. a non-breaking `BoundModel` fingerprint exposed alongside the built rule;
3. exhaustive model-reference/kind/config validation against the evaluation model.

The recommendation is a project-owned authoring builder that creates the fingerprint-carrying wrapper at construction time, plus reference/kind/config validation as defense in depth. Do not post-hoc stamp an arbitrary `Rule` with the current fingerprint and call that provenance.

For interoperability with an already built `Rule`, expose an explicitly named `verifyAndBind` operation. It must exhaustively validate the rule’s paths, kinds, and model-dependent reference configuration before issuing the wrapper; it is not a trust-on-bind shortcut.

Prepared-handle ownership protects reuse after preparation; it does not solve authoring-model provenance before preparation.

### 5.3 Catalog ownership

The bridge must reuse rulekit’s existing catalog instance when rendering many programs. `DslRenderer` otherwise loads the catalog in its convenience constructor, which can repeatedly parse resource data during preparation.

No new operator table is added to the bridge. Portable vocabulary or conformance data should be generated or read from one owned source.

## 6. First implementation: prepared candidate rules

### 6.1 [Topic 1](OPERATOR-API-PROPOSAL.md) dependency

Topic 2 consumes [Topic 1](OPERATOR-API-PROPOSAL.md)’s candidate preparation contract:

```java
Preparation<PreparedRule> preparation =
    session.prepareRule(candidateRuleSpec);

CandidateRuleEvaluation result =
    session.evaluate(preparedRule, documentSnapshot, "en_US");
```

The bridge uses this exact public boundary. `DmInterpreter`, `RuleDef`, and mutable `Document` remain implementation details.

The prepared handle is bound to the creating session/model. The session evaluates it; the handle does not retain a second copy of the model, clock, callback registries, charset, or computation engine.

### 6.2 Required interpreter refactor

Current injected candidates are not already prepare-once programs. Candidate evaluation eventually asks `ParsedModel` for the condition AST and can reparse an ad hoc candidate.

Preparation requires an explicit AST seam:

```kotlin
rowOutcomes(rule, parsedCondition, pass)
```

Stored rules provide their existing cached parsed condition. Prepared candidates provide their owner-bound parsed condition.

This preserves the execution representation and operator/runtime implementation after preparation. It is still an internal evaluation-path refactor and must be proven against the current path.

This first seam covers ordinary candidate rules. In line with [Topic 1](OPERATOR-API-PROPOSAL.md), `RepetitionNotUnique` is rejected until the separate `RepetitionNotUniqueEvaluator` and ordinary row-outcome paths have a unified prepared result contract.

### 6.3 Bridge compilation

For one rule:

1. verify paired-model provenance;
2. render the condition in canonical English relative to the rule group, using the bridge-owned catalog;
3. map the shallow rule metadata to [Topic 1](OPERATOR-API-PROPOSAL.md)’s candidate DTO, passing the absolute error-field path and letting Topic 1 derive interpreter-relative metadata;
4. call the interpreter preparation API once;
5. return the opaque owner-bound handle.

Comments are authoring metadata and are not an evaluation-equivalence dimension. The semantic bridge may omit them from the prepared text while rulekit retains them on the source tree.

The bridge does not:

- walk every AST node to construct interpreter nodes;
- expose `Expr` or `Cond`;
- evaluate an operator;
- perform full MVK consistency checking;
- use the licensed kernel;
- cache globally.

### 6.4 Preparation failures

Preparation returns a structured result. It distinguishes:

- model provenance mismatch;
- syntax/render/parser disagreement;
- unresolved path;
- unsupported prepared-rule construct;
- missing required external configuration when detectable;

Lack of consistency certification is not a preparation failure. [Topic 1](OPERATOR-API-PROPOSAL.md) returns an `UNCERTIFIED` preview handle unless an opaque trusted certificate or verified signed attestation over the exact model and candidate is supplied. Validation of the base model alone does not certify the new candidate.

Current parser/evaluator paths are not uniformly tolerant of unknown functions and predicates. The bridge must use [Topic 1](OPERATOR-API-PROPOSAL.md)’s explicit preparation/evaluation error contract rather than promise that every unsupported shape becomes the same result automatically.

### 6.5 Java client example

```java
RulekitEvaluationProject project =
    RulekitEvaluationProject.open(validatedExpandedModelJson, options);

PairedAuthoringModel model = project.authoring();
NumberRef discount = model.num("/Order/DiscountPercent");

AuthoredRule candidate = model.rule(model.group("/Order"))
    .name("DiscountLimit")
    .errorField(discount)
    .when(discount.filled().and(discount.gt(50)))
    .code("DISCOUNT_TOO_HIGH")
    .severity(Severity.ERROR)
    .message(Dsl.en("Discount must not exceed 50%."))
    .build();

PreparedRule prepared = project.prepare(candidate).orThrow();

EvaluationSession session = project.session();
DocumentSnapshot document = session.readInstanceJson(instanceJson);
CandidateRuleEvaluation result =
    session.evaluate(prepared, document, "en_US");
```

The names above are illustrative. The important properties are:

- `project` owns both model views from one expanded source;
- the project-owned authoring builder creates `AuthoredRule` with provenance rather than stamping an arbitrary rule afterward;
- preparation happens once;
- repeated calls reuse the parsed execution form;
- only `EvaluationSession` evaluates, using [Topic 1](OPERATOR-API-PROPOSAL.md)’s stable DTOs.

This preparation is an `UNCERTIFIED` preview even though the base model was validated. A production handle becomes `CERTIFIED` only after [Topic 1](OPERATOR-API-PROPOSAL.md)’s separately packaged, kernel-linked JVM consistency issuer checks the exact authored candidate against the exact paired-model fingerprint and returns an opaque local certificate or signed remote attestation. The bridge itself remains kernel-free.

## 7. Rulekit numeric expressions

### 7.1 Why they are not in the first Topic 2 increment

The interpreter currently has no stable public arbitrary-value result type and no universal public evaluation context.

Targetless “computation semantics” is not meaningful. A free expression also cannot automatically inherit rule-only error field, polarity, suppression, or partial relevance semantics.

Those contracts belong to [Topic 1](OPERATOR-API-PROPOSAL.md).

### 7.2 Later bridge once [Topic 1](OPERATOR-API-PROPOSAL.md) ships the bounded observer

Rulekit can then prepare its typed calculation through canonical text:

```java
NumberExpr discountedTotal =
    sum(model.flatten("/Order/Lines*/Amount"))
        .times(
            number(1).minus(
                model.num("/Order/DiscountPercent")
                    .asNumber()
                    .dividedBy(number(100))
            )
        );

PreparedNumberExpression prepared =
    project.compileNumberExpression(
        discountedTotal.expression(),
        "/Order"
    ).orThrow();

NumberObservation value =
    project.session().evaluateAt(
        prepared,
        document,
        SimpleLocation.of("/Order", 1)
    );
```

`compileNumberExpression` is a rulekit-specific compile adapter: the bridge renders the numeric `Calculation` inside `NumberExpr`, then delegates preparation to [Topic 1](OPERATOR-API-PROPOSAL.md) and returns Topic 1’s handle. It does not own another expression/result/evaluation contract.

[Topic 1](OPERATOR-API-PROPOSAL.md)’s restrictions still apply: the first observer is number-only, evaluation is validation-style observation, and context shapes that cannot be represented by `SimpleLocation` are rejected.

## 8. Computations

Prepared ad hoc computations are not part of Topic 2’s first bridge.

A rulekit `Computation` contains:

- target field;
- name and group;
- optional common precondition;
- conditional alternatives;
- messages;
- suppression codes.

Interpreter execution additionally needs dependency graph integration, cascaded overlays, poison rules, target iteration and formatting, implicit validation, and preview/apply policy.

The current computation engine is built from model-owned computations. A bridge cannot obtain correct candidate-computation behavior by rendering only the operation.

Until [Topic 1](OPERATOR-API-PROPOSAL.md)’s separate candidate-computation feasibility work is approved, clients have two honest choices:

1. evaluate an existing stored computation with `computeField`;
2. insert the new computation into a newly validated model and create a new paired evaluation project.

## 9. Correctness gates

Split the evidence by responsibility.

### 9.1 Authoring render

For every rulekit construct the bridge accepts:

- canonical English rendering is stable;
- paths resolve relative to the declaring group correctly;
- keyword-named path segments are quoted;
- decimal literal scale is retained in rendered source;
- `$`, star, `Having`, semantic index, and precedence render correctly.

This is a rulekit test.

### 9.2 Parser/preparation

For rendered programs:

- the interpreter parser accepts canonical output;
- normalized targets equal the intended model paths;
- preparation produces the same execution AST behavior as the current parse path;
- provenance mismatch fails before evaluation;
- unsupported shapes fail through the documented contract.

Authored path spelling and comments need not survive; canonical target identity and evaluation are the contract.

### 9.3 Prepared-rule evaluation

For every supported rule family, complete located outcomes from:

```text
rulekit rule → render → prepare once → evaluate repeatedly
```

must equal:

```text
same parsed candidate → shared internal row evaluator
```

The current public candidate API collapses repeated rows. Test the new located findings/suppressions against the internal row outcomes and kernel messages, then separately assert that the new aggregate verdict equals the legacy public `RuleResult` where comparable.

Cover:

- flat and repeated rules;
- nested/starred/parallel/correlated iteration;
- formal suppression;
- multiple row findings;
- message polarity and interpolation;
- custom conditions;
- loud `RepetitionNotUnique` rejection until its dedicated evaluator is unified with prepared outcomes;
- missing callback/unsupported behavior.

### 9.4 Kernel oracle

Kernel differential claims apply only to kernel-legal models/rules that also fall inside [Topic 1](OPERATOR-API-PROPOSAL.md)’s mechanically enforced capability/platform profile. Rulekit’s build-time checks are intentionally not the complete consistency gate, and the kernel-free bridge cannot certify MVK acceptance.

Validated bridge cases must also agree with the applicable kernel runtime/codegen contract after the repository resolves its precedence contradiction. In particular, bridge preparation does not waive Topic 1’s temporary exclusion of `Europe/Berlin` models from the exact profile, or any platform-specific arithmetic/regex exclusion.

### 9.5 Later features

Expression, partial-candidate, and computation gates are added only when those APIs exist. Do not list them as evidence for the first rule bridge.

## 10. Performance and warmup

### 10.1 Non-negotiable outcome

After successful preparation, repeated candidate evaluation must use:

- the same parsed execution node representation;
- the same traversal;
- the same document lookup/iteration machinery;
- the same runtime operator implementations;
- no repeated parse.

The existing stored path and current candidate behavior must remain equivalent.

### 10.2 What cannot honestly be promised in advance

Adding a prepared seam changes internal call structure. Adding public classes changes JVM/JS artifacts and may affect native reachability. The optional bridge is absent from ordinary rulekit users, but the [Topic 1](OPERATOR-API-PROPOSAL.md) core API additions still affect the interpreter artifact.

Therefore “no degradation” is a measured release gate, not proof by architecture.

### 10.3 Preparation cost

The bridge pays:

- provenance checks;
- canonical render;
- shallow DTO mapping;
- one parse/normalization;
- boundary allocations.

It must reuse a catalog instance rather than repeatedly calling convenience constructors that reload catalog JSON.

This cost is outside repeated evaluation, but it still matters for first answer and one-shot consumers.

### 10.4 Measurements

Measure at minimum:

- current unprepared candidate first result;
- bridge render plus preparation plus first result;
- repeated prepared evaluation;
- stored-rule evaluation as the same-AST comparator;
- allocations and retained handle lifetime;
- many-program preparation with one reused catalog;
- fresh JVM process p95;
- packaged dependency and footprint changes.

Node/native startup and artifact gates are [Topic 1](OPERATOR-API-PROPOSAL.md) dependency gates, not Topic 2 bridge measurements: this bridge is JVM-only.

The repository’s incomplete NFR certification must be closed or supplemented before claiming comprehensive neutrality. Use executable SLA/non-inferiority gates and scaling checks.

### 10.5 Failure rule

If prepared evaluation cannot reuse the current execution representation and traversal without a measurable regression, stop and reconsider the boundary. Do not compensate by weakening the interpreter’s correctness or performance goals.

## 11. Java compatibility

The recommended bridge preserves the current rulekit AST:

- Java records remain records;
- canonical constructors and accessors remain;
- record equality remains;
- Java 21 pattern switches remain;
- renderer/linter/explanation traversal remains;
- comments remain identity-keyed to the authoring nodes.

The bridge is additive and optional.

New bridge DTOs should use normal Java-compatible builders where their parameter count or adjacent same-typed values would make positional constructors unsafe.

The new prepared interpreter API uses stable boundary DTOs rather than declaring current Kotlin `Expr`, `Cond`, `Val`, or `Document` to be supported Java ABI.

## 12. TypeScript strategy

TypeScript consumers use [Topic 1](OPERATOR-API-PROPOSAL.md)’s primary npm API directly, including its canonical DSL preparation boundary.

TypeScript packaging, callbacks, values, snapshots, and evaluation are wholly owned by [Topic 1](OPERATOR-API-PROPOSAL.md). This Topic 2 bridge is JVM-only because current rulekit is Java/JVM.

A future typed TypeScript authoring façade would be another **source representation** unless it merely concatenated unsafe strings. It would need typing, precedence, reference handling, rendering, and authoring validation.

The current catalog does not encode every grammar rule and model invariant needed to generate such a façade completely.

Therefore a future TypeScript DSL has three honest options:

1. introduce a KMP authoring AST intended to replace the Java AST eventually;
2. generate a bounded builder surface from an enriched single language contract;
3. maintain a separate TypeScript authoring representation with explicit conformance and accepted duplication.

Option 1 is the only one that directly advances a full rulekit migration, but it triggers the Java compatibility/migration trilemma. It requires a separate proposal.

## 13. What this does and does not prepare for migration

### 13.1 Useful migration foundations created here

- one stable authoring-to-execution compiler boundary;
- model provenance/fingerprinting;
- a prepare-once execution contract;
- canonical render/parser conformance cases;
- catalog/vocabulary coverage guards;
- an optional module boundary;
- performance baselines;
- proof that evaluator internals need not be the authoring representation.

These foundations reduce risk in any later migration.

### 13.2 What remains unproven

The current Java AST is a strongly connected center for:

- rendering;
- linting;
- explanation;
- transforms;
- comments;
- model-bound references;
- reading and writing;
- validation integration.

A KMP renderer or linter cannot simply “move independently” while consuming Java records from `commonMain`. It needs either:

- a structural adapter;
- a shared/new authoring representation;
- or an atomic cutover.

The compiler boundary does not cut that SCC.

### 13.3 Required next step before any migration roadmap

Run a dedicated dependency-cut study:

1. inventory every public AST type and consumer;
2. map compile-time dependency cycles;
3. record Java source/binary compatibility requirements;
4. identify genuinely AST-independent utilities/resources;
5. prototype Java ergonomics over one small KMP source hierarchy;
6. measure representation and startup/footprint changes;
7. choose atomic cutover versus temporary compatibility layer.

Only then can a credible phased migration plan be written.

### 13.4 What may move safely before that study

Portable resources and tests can be consolidated without claiming an AST migration:

- operator IDs and canonical keyword data;
- conformance fixtures;
- generated coverage mappings;
- model-fingerprint algorithms and cross-module test vectors.

Boundary result schemas and public DTO contracts remain owned by [Topic 1](OPERATOR-API-PROPOSAL.md); Topic 2 imports them and may add bridge conformance cases, but does not fork or relocate ownership.

Renderer, linter, transforms, rule/computation values, and the recursive AST are not promised as independently movable layers.

## 14. Why the single-AST proposal is rejected

### 14.1 Moving the interpreter AST is not neutral

The interpreter AST references runtime decimal/value concepts and caches literal `Val` objects. Extracting it cleanly either pulls runtime machinery into the alleged DSL core or changes the hot representation.

### 14.2 It loses authoring fidelity

The interpreter tree does not retain Java operator-specific record types, lexical decimal scale, path-style provenance, comments, or model-bound authoring types.

### 14.3 It breaks Java compatibility

Kotlin data classes are not Java records.

### 14.4 Thin wrappers do not solve a recursive public tree

A wrapper can hide a root construction call. It cannot make nested Kotlin nodes appear as the old Java record component types without retaining a second tree or translating recursively.

### 14.5 Per-family migration is not compositional

Old and new expression families nest inside each other. A partial cut requires adapters at every recursive boundary and becomes a dual representation.

### 14.6 The runtime benefit is small

The shared-AST idea mainly removes a one-time render/parse boundary. Explicit preparation already amortizes that cost without changing the optimized authoring and execution representations.

## 15. If one AST is mandated later

Treat it as a major-version research and migration program, not an extraction.

It would require:

- a new source/execution representation strategy;
- lexical and semantic decimal forms;
- known operator nodes plus a policy for unknown/forward-compatible syntax;
- authoring metadata and comments;
- model provenance;
- Java visitor/fold APIs replacing record-pattern traversal;
- a compatibility policy for every public record;
- atomic cutover or a deliberately temporary dual representation;
- JVM/JS/native correctness and performance recertification.

Two viable high-level strategies exist:

### Atomic cutover

Create the new KMP authoring AST in a major version, migrate all recursive families together, and accept the Java API break.

### Temporary compatibility layer

Keep deprecated Java records as source-facing adapters that compile to the KMP authoring AST, with a removal deadline and explicit temporary duplication.

Neither is the current proposal.

## 16. Delivery plan

### Phase 0: [Topic 1](OPERATOR-API-PROPOSAL.md) prerequisite and contracts

Complete the relevant common prepared-candidate boundary in [Topic 1](OPERATOR-API-PROPOSAL.md) Phase 3 before implementing this bridge. Topic 2 does not wait for Topic 1’s secondary public Java façade or optional certification integrations:

1. ship the stable session/snapshot/result boundary;
2. implement owner-bound prepared candidate rules;
3. define model fingerprint and the `UNCERTIFIED` preview contract; certification may remain optional;
4. establish current candidate/stored-rule performance baselines.

### Phase 1: paired bridge

1. Add the optional `:rulekit-interpreter` JVM module.
2. Build rulekit and interpreter model views from one expanded source.
3. Add fingerprint-carrying authored-rule provenance.
4. Reuse one catalog instance.
5. Render canonical English and perform shallow candidate mapping.
6. Prepare once and evaluate repeatedly.

### Phase 2: bridge conformance qualification

1. Add authoring render cases.
2. Add parser/preparation equivalence.
3. Add located-row oracle equivalence plus aggregate prepared-versus-legacy-candidate equivalence.
4. Add kernel differential cases for validated inputs.
5. Add first-answer, repeated-evaluation, allocation, and footprint gates.

### Phase 3: numeric-expression bridge, conditional

After [Topic 1](OPERATOR-API-PROPOSAL.md) Phase 3’s bounded expression observer exists:

1. render rulekit `NumberExpr`;
2. pass the base group to Topic 1’s number-only observer;
3. preserve paired-model provenance;
4. reject unsupported evaluation contexts;
5. conformance-test the accepted subset.

### Phase 4: migration decision, separate approval

Run the dependency-cut study. Do not announce phased renderer/linter/AST migration before its result.

### Phase 5: computation bridge, separate approval

Proceed only after [Topic 1](OPERATOR-API-PROPOSAL.md) defines candidate-computation insertion, dependencies, poison, storage, and apply contracts.

## 17. Risks and responses

| Risk | Consequence | Required response |
|---|---|---|
| Rule and evaluator came from different models | silently wrong kind/scale/repetition behavior | paired expanded model, fingerprint wrapper, validation |
| “No duplication” is interpreted literally | hidden renderer/parser/linter overlap | state exact ownership and guard it |
| Candidate still reparses | no performance benefit | explicit prepared AST seam and parse-count test |
| Catalog reload per render | poor first answer for many programs | bridge-owned reused catalog |
| Preparation mistaken for MVK validation | invalid rules receive false guarantees | `UNCERTIFIED` by default; Topic 1’s separately packaged kernel-linked issuer is the only path to `CERTIFIED` |
| Bridge grows a recursive mapper | permanent third translation layer | architectural guard: canonical text is the recursive boundary |
| Public Kotlin internals leak | ABI freezes execution implementation | [Topic 1](OPERATOR-API-PROPOSAL.md) versioned DTOs and opaque handles |
| “Migration-ready” becomes roadmap theater | underestimated Java AST SCC | dependency-cut study before migration claims |
| Typed TypeScript façade becomes a third AST | new authoring duplication | separate explicit design and migration choice |
| Prepared refactor changes hot path | startup/warm regression | executable non-inferiority and scaling gates |

## 18. Alternatives considered

### Direct recursive Java AST to interpreter AST translation

Rejected. It duplicates every syntax shape, must track both hierarchies, and becomes permanent mapping code.

### Share the interpreter AST immediately

Rejected because it loses authoring fidelity, changes the hot representation, and breaks Java records.

### Share the rulekit AST with the interpreter

Rejected because common KMP cannot depend on Java rulekit and the large source tree is not the compact normalized execution form.

### Parse on every evaluation

Correct but inefficient for repeated preview. Explicit preparation is the appropriate cache boundary.

### Put the bridge inside rulekit

Rejected for the default artifact. It would force evaluation/runtime dependencies on authoring-only consumers. The optional bridge module is cleaner.

### Put the bridge inside the interpreter

Rejected. It reverses dependency direction and pulls Java authoring concerns into the KMP execution module.

### Full rulekit migration now

Rejected. It is much larger than the interoperability use case and lacks a compatibility/dependency-cut decision.

### Promise zero duplication plus gradual non-breaking AST migration

Rejected as infeasible for the current recursive public Java record tree.

## 19. Independent review verdict

The compiler boundary is the honest recommendation for the immediate need.

It provides:

- rulekit-authored candidate rules evaluated by the interpreter;
- parse once, evaluate many;
- no new runtime operator implementation;
- no recursive AST mapper;
- no interpreter dependency for ordinary rulekit users;
- no Java AST compatibility break;
- no interpreter execution-representation change after preparation;
- a model-provenance contract.

It does **not** prove that rulekit can now migrate gradually to Kotlin. It creates useful foundations for a later migration decision, but the concrete Java AST dependency graph remains to be cut.

The immediate proposal should therefore be approved or rejected as an **interoperability and preparation-cache feature**, not sold as an AST migration.

## 20. Acceptance criteria for the first approved bridge

- [Topic 1](OPERATOR-API-PROPOSAL.md) owns the prepared-rule API; Topic 2 does not define a competing evaluator abstraction;
- rulekit and interpreter keep their purpose-specific trees;
- a paired project derives both model views from one expanded source;
- authored candidates carry a checked model fingerprint/provenance token;
- the bridge maps only shallow rule metadata and canonical text;
- no recursive AST mapper exists;
- one catalog instance can serve many preparations;
- a candidate is parsed once and not reparsed during repeated evaluation;
- the engine, not the handle, owns configuration and performs evaluation;
- prepared located outcomes equal the shared internal row evaluator/kernel messages, and the new aggregate verdict equals the legacy public candidate verdict where comparable;
- kernel comparison is limited to kernel-legal inputs inside Topic 1’s mechanically enforced capability/platform profile;
- preparation returns `UNCERTIFIED` unless Topic 1 verifies an opaque trusted certificate or signed attestation over the exact model and candidate;
- existing Java record APIs remain compatible;
- ordinary rulekit consumers do not acquire the bridge dependency;
- stored interpreter behavior remains equivalent;
- startup, warmup, throughput, allocation, scaling, and footprint remain inside executable budgets;
- numeric-expression bridging waits for [Topic 1](OPERATOR-API-PROPOSAL.md)’s number/location contract;
- candidate-computation bridging waits for its separate engine-orchestration contract;
- no claim of a proven gradual rulekit migration is made without a dependency-cut study.

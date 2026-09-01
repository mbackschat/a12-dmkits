# README infographics

This document is the regeneration and refinement source for the raster infographics embedded in the main and public-release READMEs. It owns the content maps, exact generation prompts, asset registry, factual sources, placement map, visual acceptance gates, and refinement log. The PNG files are presentation artifacts derived from this document.

## Asset registry and README placement

The full-resolution source assets live under `assets/infographics/`. Each table row is one guarded preview placement. `Canonical asset` is repository-relative; `Preview document` links from this file; `Preview path` is relative to that document. When the preview path resolves outside the canonical directory, the linked release-source copy must be byte-identical to the canonical PNG.

<!-- infographic-registry:start -->
| Infographic | Canonical asset | Preview document | Preview path | Width |
|---|---|---|---|---:|
| A12 dmkits toolchain | `assets/infographics/a12-dmkits-toolchain.png` | [root README](../README.md) | `assets/infographics/a12-dmkits-toolchain.png` | `720` |
| A12 dmkits toolchain | `assets/infographics/a12-dmkits-toolchain.png` | [infographic source](INFOGRAPHICS.md) | `../assets/infographics/a12-dmkits-toolchain.png` | `540` |
| dmtool DocumentModel workbench | `assets/infographics/dmtool-model-workbench.png` | [root README](../README.md) | `assets/infographics/dmtool-model-workbench.png` | `720` |
| dmtool DocumentModel workbench | `assets/infographics/dmtool-model-workbench.png` | [dmtool release README](../plugin/README.md) | `assets/infographics/dmtool-model-workbench.png` | `720` |
| dmtool DocumentModel workbench | `assets/infographics/dmtool-model-workbench.png` | [infographic source](INFOGRAPHICS.md) | `../assets/infographics/dmtool-model-workbench.png` | `540` |
| Interpreter runtime | `assets/infographics/a12-interpreter-runtime.png` | [root README](../README.md) | `assets/infographics/a12-interpreter-runtime.png` | `720` |
| Interpreter runtime | `assets/infographics/a12-interpreter-runtime.png` | [Interpreter release README](../interpreter/public-release/README.md) | `assets/infographics/a12-interpreter-runtime.png` | `720` |
| Interpreter runtime | `assets/infographics/a12-interpreter-runtime.png` | [infographic source](INFOGRAPHICS.md) | `../assets/infographics/a12-interpreter-runtime.png` | `540` |
| Interpreter vs Kernel | `assets/infographics/interpreter-vs-kernel.png` | [root README](../README.md) | `assets/infographics/interpreter-vs-kernel.png` | `720` |
| Interpreter vs Kernel | `assets/infographics/interpreter-vs-kernel.png` | [dmtool release README](../plugin/README.md) | `assets/infographics/interpreter-vs-kernel.png` | `720` |
| Interpreter vs Kernel | `assets/infographics/interpreter-vs-kernel.png` | [Interpreter release README](../interpreter/public-release/README.md) | `assets/infographics/interpreter-vs-kernel.png` | `720` |
| Interpreter vs Kernel | `assets/infographics/interpreter-vs-kernel.png` | [infographic source](INFOGRAPHICS.md) | `../assets/infographics/interpreter-vs-kernel.png` | `540` |
| Kotlin, GraalVM, and A12 Kernel roles | `assets/infographics/kotlin-graalvm-kernel-roles.png` | [root README](../README.md) | `assets/infographics/kotlin-graalvm-kernel-roles.png` | `720` |
| Kotlin, GraalVM, and A12 Kernel roles | `assets/infographics/kotlin-graalvm-kernel-roles.png` | [dmtool release README](../plugin/README.md) | `assets/infographics/kotlin-graalvm-kernel-roles.png` | `720` |
| Kotlin, GraalVM, and A12 Kernel roles | `assets/infographics/kotlin-graalvm-kernel-roles.png` | [Interpreter release README](../interpreter/public-release/README.md) | `assets/infographics/kotlin-graalvm-kernel-roles.png` | `720` |
| Kotlin, GraalVM, and A12 Kernel roles | `assets/infographics/kotlin-graalvm-kernel-roles.png` | [infographic source](INFOGRAPHICS.md) | `../assets/infographics/kotlin-graalvm-kernel-roles.png` | `540` |
| JSON Schema functionality | `assets/infographics/json-schema-document-model.png` | [root README](../README.md) | `assets/infographics/json-schema-document-model.png` | `720` |
| JSON Schema functionality | `assets/infographics/json-schema-document-model.png` | [dmtool release README](../plugin/README.md) | `assets/infographics/json-schema-document-model.png` | `720` |
| JSON Schema functionality | `assets/infographics/json-schema-document-model.png` | [infographic source](INFOGRAPHICS.md) | `../assets/infographics/json-schema-document-model.png` | `540` |
| Semantic model diff | `assets/infographics/semantic-model-diff.png` | [root README](../README.md) | `assets/infographics/semantic-model-diff.png` | `720` |
| Semantic model diff | `assets/infographics/semantic-model-diff.png` | [dmtool release README](../plugin/README.md) | `assets/infographics/semantic-model-diff.png` | `720` |
| Semantic model diff | `assets/infographics/semantic-model-diff.png` | [infographic source](INFOGRAPHICS.md) | `../assets/infographics/semantic-model-diff.png` | `540` |
<!-- infographic-registry:end -->

Every README embeds a scaled preview using an HTML `img` element inside a link to the same full-resolution PNG. The preview width is `720`, the image has descriptive alt text, and the adjacent caption tells the reader that the full image opens on click.

The table above is the single source for placement and copy checks. Both `check-infographic-freshness.sh` and `InfographicAssetGuardTest` parse it; neither carries a second asset list. The guards also refuse an unregistered canonical PNG or a registered asset without an accepted-output preview in this document. The public dmtool mirror already copies the complete `plugin/` tree. The Interpreter mirror assembler copies `interpreter/public-release/assets/infographics/` beside its generated root README.

## Shared visual system

- Use case: `infographic-diagram`.
- Audience: software engineers, A12 model developers, technical decision-makers, and coding-agent users with general software architecture knowledge.
- Format: original ByteByteGo-inspired information design without ByteByteGo branding or copied compositions.
- Canvas: high-resolution portrait with a warm off-white background. Use 4:5 by default and 2:3 for the denser layered architectures and dmtool workbench.
- Header: large left-aligned title with a short mint vertical accent bar and a clean upper-right corner.
- Typography: bold geometric sans-serif, horizontal text only, short labels, no paragraphs inside the image.
- Shapes: dark outlined rounded rectangles, pills, compact circular stage numbers, pale grouping fields, and saturated focal nodes.
- Palette: near-black ink, mint accent, pale cyan, pale lavender, pale green, pale yellow, and one saturated purple or blue focal color.
- Connectors: solid arrows mean direct flow; dashed arrows mean verification, evidence, or an indirect relationship. Every arrow has a clear source, target, and action.
- Branding: product names such as `a12-dmkits`, `dmtool`, `modelkit`, `rulekit`, and `A12 Interpreter` are text labels, not logos. Do not add the ByteByteGo logo, mgm branding, an A12 logo, a watermark, or an invented project mark.
- Text fidelity: use only the exact content listed for each infographic. Do not add claims, product names, steps, statistics, or decorative pseudo-text.
- Quality threshold: the takeaway, title, main path, and output must remain clear at README preview width. All exact labels and measurements must be readable at full size.

## Factual sources and freshness triggers

| Claim family | Current source | Regenerate when |
|---|---|---|
| Module roles, boundaries, entry points, and dependency direction | [ARCHITECTURE.md](ARCHITECTURE.md), [API-GUIDE.md](API-GUIDE.md), root [`README.md`](../README.md) | a module, public product name, dependency edge, entry point, or authoring/runtime boundary changes |
| dmtool discovery, model exchange, comprehension, authoring, review, runtime, result, and MCP mechanisms | [CLI-SPEC.md](CLI-SPEC.md), [CLI-CONFORMANCE-SPEC.md](CLI-CONFORMANCE-SPEC.md), root [`README.md`](../README.md), public dmtool [`README.md`](../plugin/README.md) | a featured mechanism, contract, or product positioning changes |
| JSON Schema and OpenAPI transcoding | [SCHEMAKIT-SPEC.md](SCHEMAKIT-SPEC.md), the executable [JSON Schema walkthrough](../examples/cli-jsonschema.md), `dmtool model import-jsonschema --help`, and `dmtool model export-jsonschema --help` | a supported dialect, mapping tier, structural mapping, synthesized-rule family, MappingProfile choice, report contract, export projection/carriage rule, or verification gate changes |
| Structural semantic diff | [MODEL-REVIEW-SPEC.md](MODEL-REVIEW-SPEC.md), the executable [review walkthrough](../examples/cli-review.md), `dmtool model diff --help`, and `dmtool schema model diff` | an input mode, independently resolved baseline rule, change family, delta type, condition-edit classifier, refactor matcher, review-impact tier, or output view changes |
| Interpreter preparation, request lifecycle, targets, and public features | [INTERPRETER-API-SPEC.md](INTERPRETER-API-SPEC.md), [INTERPRETER-ARCHITECTURE.md](INTERPRETER-ARCHITECTURE.md), public Interpreter [`README.md`](../interpreter/public-release/README.md) | the public lifecycle, target set, concurrency contract, or supported result family changes |
| Kernel-conformance claims | [TESTING-SPEC.md](TESTING-SPEC.md), [CONFORMANCE-CORPUS-SPEC.md](CONFORMANCE-CORPUS-SPEC.md), [INTERPRETER-SPEC.md](INTERPRETER-SPEC.md) | the semantics target, oracle hierarchy, differential policy, or portable corpus contract changes |
| Kotlin, Java, GraalVM, and Kernel roles | [ARCHITECTURE.md](ARCHITECTURE.md), [INTERPRETER-ARCHITECTURE.md](INTERPRETER-ARCHITECTURE.md), [INTERPRETER-SPEC.md](INTERPRETER-SPEC.md), [NATIVE-IMAGE-GRAALVM.md](NATIVE-IMAGE-GRAALVM.md), [RELEASE-SPEC.md](RELEASE-SPEC.md), and the generated root [Source statistics](../README.md#source-statistics) | a component boundary, compile target, shipped platform, runtime owner, Kernel responsibility, native-delivery decision, or Java/Kotlin production count changes |
| Performance and resident memory | public [PERFORMANCE.md](../interpreter/public-release/PERFORMANCE.md), [INTERPRETER-PERFORMANCE.md](INTERPRETER-PERFORMANCE.md), [INTERPRETER-PERFORMANCE-RESULTS.md](INTERPRETER-PERFORMANCE-RESULTS.md) | a qualifying benchmark rerun changes any displayed number, machine/runtime identity, timing boundary, or eligibility rule |

The performance poster is dated evidence. Its current figures were measured on 2026-08-17 on Apple M1, JDK 21, and Chrome 151. Re-run the owning benchmark protocols before replacing a number. Do not infer a new ratio from unrelated results or turn a bounded scenario into a universal claim.

## Synchronization contract

The public Interpreter [`PERFORMANCE.md`](../interpreter/public-release/PERFORMANCE.md) owns every mutable measurement printed in the performance poster. [`check-infographic-freshness.sh`](../scripts/check-infographic-freshness.sh) extracts the current values from the report's named table rows and evidence footer, then exact-matches the corresponding lines in the compiled ImageGen prompt below. The check contains no second numeric snapshot, so changing an owning table cell makes the prompt check fail immediately.

GitHub CI checks out rulekit, sets up the repository-pinned Node runtime, and then runs that stable shell command before it checks out the Kernel bootstrap kit or installs Java and Gradle. Stale claims, broken previews, missing assets, or drifted public copies therefore stop the workflow before the expensive work begins. Keeping the check in the existing build job avoids paying for a second runner startup and duplicate repository checkout on every successful run. The committed [pre-push hook](../.githooks/pre-push) runs the same check locally and then runs `actionlint`, so any Git client gets the same pre-push verdict after `core.hooksPath` is configured. `InfographicAssetGuardTest` repeats the source-to-prompt and asset checks in the ordinary documentation suite, while `GateTierWiringTest` locks both this published ordering and the workflow itself and prevents a separate preflight job from being introduced silently.

The PNG is intentionally not treated as machine-readable data. CI can prove that the owning reports and the exact generation prompts agree, but reliable raster-text verification would require brittle OCR. The public performance report owns the mutable benchmark figures. The generated root Source Statistics table owns the Java/Kotlin production counts; both guards derive the displayed one-decimal kLOC values from its `Code` column, while `update-code-statistics.sh --check` keeps that table measured before publication. When either guard fails after an owning-source refresh, update the content map and prompt, regenerate or focus-edit the image with built-in ImageGen, inspect it at full resolution, synchronize every public copy, and add a dated refinement-log row. The binary-copy check then proves that every public repository receives the accepted pixels.

## Infographic 1: A12 dmkits toolchain

### Accepted output

<p align="center">
  <a href="../assets/infographics/a12-dmkits-toolchain.png">
    <img src="../assets/infographics/a12-dmkits-toolchain.png" alt="A12 dmkits toolchain from typed authoring through Kernel acceptance to runtime evaluation" width="540">
  </a>
</p>
<p align="center"><sub>1024 × 1536 PNG. Click the preview to open the full-resolution image.</sub></p>

### Content map

- Ten-second takeaway: modelkit and rulekit author a DocumentModel, the real A12 Kernel confirms it, and the clean-room Interpreter evaluates Documents across runtimes.
- Exact title: `a12-dmkits: Author, Confirm, Run`
- Primary layout: layered architecture.
- Reading direction: top to bottom.
- Focal mechanism: the authoring layer containing modelkit, rulekit, and EditSession.
- Major regions: consumers, entry points, typed authoring, Kernel acceptance, runtime evaluation, outputs.
- Final output: a kernel-accepted DocumentModel plus validation and computation results.

### Exact relationships

- Developer -> uses -> Typed Java API.
- Agent / BA -> drives -> dmtool CLI + MCP.
- Typed Java API -> authors through -> modelkit + rulekit.
- dmtool CLI + MCP -> orchestrates -> modelkit + rulekit.
- modelkit -> defines -> structure.
- rulekit -> defines -> rules + computations.
- modelkit + rulekit -> meet in -> EditSession.
- EditSession -> submits -> Real A12 Kernel.
- Real A12 Kernel -> accepts -> DocumentModel JSON.
- DocumentModel JSON + A12 Document -> run on -> A12 Interpreter.
- A12 Interpreter -> returns -> validation + computation.

### Generation prompt

```text
Use case: infographic-diagram
Asset type: GitHub README technical infographic
Primary request: Create an original, polished portrait technical infographic about the a12-dmkits toolchain, using a clean ByteByteGo-inspired information-design language without copying an existing poster or using the ByteByteGo logo or wordmark.

Communication goal:
- Audience: software engineers, A12 model developers, technical decision-makers, and coding-agent users.
- Ten-second takeaway: modelkit and rulekit author a DocumentModel, the real A12 Kernel confirms it, and the clean-room Interpreter evaluates Documents across runtimes.
- Exact title: "a12-dmkits: Author, Confirm, Run".
- Tone: technically precise, compact, approachable, and visually scannable.

Canvas and header:
- Tall 2:3 portrait composition at high resolution.
- Very light warm-gray background.
- Large bold sans-serif title at the top left.
- Short mint-green vertical bar immediately left of the title.
- Leave the opposite header corner clean.
- Reserve a clear gap between the header and the diagram body.

Primary information structure:
- Use a layered architecture as the single dominant layout skeleton.
- Primary reading direction: top to bottom.
- Focal mechanism: the typed authoring layer containing modelkit, rulekit, and EditSession.
- Major regions in reading order: PEOPLE, ENTRY POINTS, TYPED AUTHORING, KERNEL ACCEPTANCE, RUNTIME EVALUATION, OUTPUTS.
- Final output: a kernel-accepted DocumentModel plus validation and computation results.

Exact content:
- Use these labels exactly as written and add no other text.
- Title: "a12-dmkits: Author, Confirm, Run"
- Region: "PEOPLE"
- Nodes: "Developer" and "Agent / BA"
- Region: "ENTRY POINTS"
- Nodes: "Typed Java API" and "dmtool CLI + MCP"
- Region: "TYPED AUTHORING"
- Node: "modelkit"
- Caption under modelkit: "Structure • fields • groups • types"
- Node: "rulekit"
- Caption under rulekit: "Rules • computations • read-back"
- Central node: "EditSession"
- Caption under EditSession: "One model • one gate"
- Region: "KERNEL ACCEPTANCE"
- Node: "Real A12 Kernel"
- Caption: "Accepts the complete DocumentModel"
- Output node: "Kernel-accepted DocumentModel JSON"
- Region: "RUNTIME EVALUATION"
- Input node: "A12 Document"
- Node: "A12 Interpreter"
- Caption: "Clean-room • JVM + JS"
- Final output node: "Validation + computation"
- Arrow labels: "uses", "drives", "authors", "orchestrates", "confirm", "run", "return"

Semantic mapping:
- People = simple person or agent icon + pale cyan + rounded card.
- Entry points = terminal or code icon + lavender + rounded card.
- modelkit = model-tree icon + pale yellow + rounded card.
- rulekit = condition-tree icon + pale green + rounded card.
- EditSession = interlocking model-and-rule icon + saturated purple + rounded card.
- Real A12 Kernel = shield-check icon + saturated blue + rounded card.
- A12 Interpreter = compact evaluator chip icon + mint + rounded card.
- Direct action = solid near-black arrow with the exact action label.

Layout-specific composition:
- Align the primary data path vertically.
- Make each major layer a wide labeled container.
- Expand TYPED AUTHORING to show modelkit and rulekit side by side, converging on EditSession.
- Place auxiliary explanatory captions inside their owning nodes, not on the primary route.
- Put the accepted model and runtime results in a conclusive bottom region.

Visual language:
- Heavy near-black outlines, rounded rectangles, pill labels, and generous gutters.
- Flat, colorful technical icons with consistent dark outlines and no realistic shading.
- Neutral canvas with a mint accent, pale cyan, pale lavender, pale green, pale yellow, saturated purple, and saturated blue.
- Keep equivalent concepts aligned and visually identical wherever they repeat.
- Keep all text horizontal and fully legible.

Avoid:
- No ByteByteGo logo, wordmark, watermark, A12 logo, mgm branding, or copied poster composition.
- No photorealistic imagery, 3D icons, gradients, neon glow, glassmorphism, blurred shadows, decorative textures, or generic stock art.
- No tiny paragraphs, illegible text, invented labels, duplicated words, random arrows, unlabeled crossings, ambiguous line endings, excessive nesting, inconsistent icons, or decorative charts.
```

## Infographic 2: dmtool DocumentModel workbench

### Accepted output

<p align="center">
  <a href="../assets/infographics/dmtool-model-workbench.png">
    <img src="../assets/infographics/dmtool-model-workbench.png" alt="dmtool DocumentModel workbench for discovery, schema exchange, understanding, authoring, review, and runtime automation" width="540">
  </a>
</p>
<p align="center"><sub>1024 × 1536 PNG. Click the preview to open the full-resolution image.</sub></p>

### Content map

- Ten-second takeaway: one self-describing tool covers the complete DocumentModel lifecycle, from plain-language intent or external schemas to verified models and executable Documents.
- Exact title: `dmtool: The A12 DocumentModel Workbench`
- Exact subtitle: `Create • translate • understand • evolve • verify • run`
- Primary layout: hub-and-spoke workbench.
- Reading direction: agent loop at the top, central hub, six numbered capability regions, then the bottom evidence rail.
- Focal mechanism: `dmtool` around an `A12 DocumentModel`, available through `CLI • MCP • native`.
- Major regions: discover, create and bridge, understand, author and transform, preview/review/verify, run and automate.
- Visual priority: CREATE & BRIDGE gets the largest capability card because bidirectional JSON Schema/OpenAPI exchange distinguishes the originating workflow; the Kernel and Interpreter remain small assurance badges rather than dominant stages.
- Density limit: at most six short feature labels in each capability region. Exact commands and detailed caveats stay in the READMEs.
- Final output: a kernel-accepted DocumentModel, canonical A12 Documents, and structured evidence for the next agent iteration.

### Exact relationships

- Human intent -> is interpreted by -> Agent.
- Agent -> invokes -> dmtool.
- dmtool -> returns -> Structured evidence.
- Structured evidence -> drives -> Agent iteration.
- dmtool -> operates on -> A12 DocumentModel.
- CLI + MCP + native -> expose -> dmtool.
- DISCOVER -> supplies -> manifests, schemas, operators, patterns, diagnostics, and examples.
- CREATE & BRIDGE -> originates -> a new DocumentModel.
- JSON Schema -> imports through -> dmtool -> A12 DocumentModel.
- OpenAPI 3.0 / 3.1 / 3.2 -> imports through -> dmtool -> A12 DocumentModel.
- A12 DocumentModel -> exports through -> dmtool -> JSON Schema or OpenAPI.
- Whole-bundle import -> creates -> connected models through includes.
- Unsupported or ambiguous source semantics -> produce -> explicit decisions, with strict mode available.
- Source constraints without a field-config home -> become -> A12 rules.
- UNDERSTAND -> maps -> report + tree, plain-language glosses, dependencies, usage, and workspace graph.
- AUTHOR & TRANSFORM -> evolves -> structure, configuration, rules, computations, patterns, and references.
- rename + move -> rewrite -> affected references.
- extract + inline -> refactor -> reusable model structure.
- PREVIEW, REVIEW & VERIFY -> evaluates -> dry-run, semantic model diff, impact tiers, corrective diagnostics, safe refactor gates, and rollback.
- Real A12 Kernel -> confirms -> complete model acceptance.
- RUN & AUTOMATE -> creates and consumes -> canonical A12 Documents.
- model seed -> creates -> canonical A12 Document JSON.
- full or partial validation + compute/apply -> returns -> structured JSON result envelope.
- A12 Interpreter -> provides -> runtime behavior differentially verified against the Kernel.

### Generation prompt

```text
Use case: infographic-diagram
Asset type: GitHub README technical infographic
Primary request: Create an original, polished portrait technical infographic presenting dmtool as the complete A12 DocumentModel workbench, using a clean ByteByteGo-inspired information-design language without copying an existing poster or using the ByteByteGo logo or wordmark.

Communication goal:
- Audience: coding-agent users, business analysts, model developers, software engineers, and technical decision-makers.
- Ten-second takeaway: one self-describing tool covers the complete DocumentModel lifecycle, from plain-language intent or external schemas to verified models and executable Documents.
- Exact title: "dmtool: The A12 DocumentModel Workbench".
- Exact subtitle: "Create • translate • understand • evolve • verify • run".
- Tone: technically precise, dense but orderly, approachable, and visually scannable.

Canvas and header:
- Tall 2:3 portrait composition at high resolution, preferably 1536 x 2304 or larger.
- Fully opaque warm off-white background extending to every canvas edge.
- Large bold sans-serif title at the top left.
- Short mint-green vertical bar immediately left of the title.
- Leave the opposite header corner clean.
- Put the subtitle directly below the title in smaller bold type.
- Reserve a clear gap between the header and the diagram body.

Primary information structure:
- Use a hub-and-spoke workbench as the single dominant layout skeleton.
- Primary reading direction: the agent loop at the top, then the central dmtool hub, then six numbered capability cards arranged around it, then the evidence rail at the bottom.
- Put a compact horizontal loop immediately below the subtitle: Human intent → Agent → dmtool → Structured evidence → Iterate.
- Make the central saturated-purple hub the visual anchor. It contains "dmtool", "A12 DocumentModel", and the access pill "CLI • MCP • native".
- Arrange six large rounded capability cards around the hub, three on the left and three on the right, with short clean spokes from the hub. Number them 1 through 6.
- Give CREATE & BRIDGE visibly more area than the other cards. Its bidirectional exchange is the key differentiator.
- Keep the two conformance assurances as small badges inside their owning cards. Do not make the Kernel or Interpreter separate stages or dominant nodes.
- Final output: a kernel-accepted DocumentModel, canonical A12 Documents, and structured evidence that drives the next agent iteration.

Exact content:
- Use these labels exactly as written and add no other text.
- Title: "dmtool: The A12 DocumentModel Workbench"
- Subtitle: "Create • translate • understand • evolve • verify • run"
- Agent-loop labels: "Human intent", "Agent", "dmtool", "Structured evidence", "Iterate"
- Central hub label: "dmtool"
- Central hub object: "A12 DocumentModel"
- Central access pill: "CLI • MCP • native"
- Card 1 title: "1  DISCOVER"
- Card 1 labels: "manifest", "schemas", "operators", "patterns", "diagnostics", "worked examples"
- Card 1 callout: "Live contract, not a hand-maintained catalog"
- Card 2 title: "2  CREATE & BRIDGE"
- Card 2 main bridge: "JSON Schema ⇄ A12 DocumentModel ⇄ OpenAPI"
- Card 2 labels: "model new", "OpenAPI 3.0 / 3.1 / 3.2", "whole-bundle import", "connected models via includes", "best-effort + strict", "constraints → A12 rules"
- Card 3 title: "3  UNDERSTAND"
- Card 3 labels: "report + tree", "plain-language glosses", "explain + deps", "where-used + usage", "workspace graph", "multi-file resolution"
- Card 3 output pill: "Actionable model map"
- Card 4 title: "4  AUTHOR & TRANSFORM"
- Card 4 labels: "structure + config", "rules + computations", "pattern scaffolds", "rename + move", "extract + inline", "atomic apply"
- Card 4 foundation pill: "modelkit • rulekit • EditSession"
- Card 5 title: "5  PREVIEW, REVIEW & VERIFY"
- Card 5 labels: "dry-run", "semantic model diff", "impact tiers", "corrective diagnostics", "safe refactor gates", "rollback • no write"
- Card 5 assurance badge: "Real A12 Kernel"
- Card 5 assurance caption: "Complete model accepted"
- Card 6 title: "6  RUN & AUTOMATE"
- Card 6 labels: "seed canonical Documents", "full + partial validation", "compute + apply", "custom conditions + types", "explicit unsupported", "JSON result envelope"
- Card 6 assurance badge: "A12 Interpreter"
- Card 6 assurance caption: "Runtime differentially verified"
- Bottom evidence pills: "Self-describing", "Machine-readable", "Atomic writes", "Reviewable changes", "Portable runtime", "Corrective diagnostics"

Semantic mapping:
- Human intent and Agent = speech-bubble and agent icons + pale cyan.
- dmtool hub = workbench or command-terminal icon + saturated purple.
- A12 DocumentModel = model-tree icon nested inside the hub.
- DISCOVER = compass and catalog icons + pale lavender.
- CREATE & BRIDGE = bidirectional schema/document arrows + pale cyan and pale yellow, with the main bridge set as the largest text inside the card.
- UNDERSTAND = map, magnifier, and dependency-tree icons + pale green.
- AUTHOR & TRANSFORM = model-tree, rule-node, and refactor icons + pale yellow.
- PREVIEW, REVIEW & VERIFY = before/after panels, checklist, and shield-check icons + pale lavender.
- RUN & AUTOMATE = document, evaluator chip, and result-envelope icons + mint.
- Direct dmtool capability = one short solid near-black spoke from the central hub to the owning card. Spokes carry no text.
- Kernel assurance = small saturated-blue shield badge inside card 5.
- Interpreter assurance = small saturated-purple evaluator badge inside card 6.

Layout-specific composition:
- Use a narrow top agent-loop rail, a strong central hub, and six capability cards balanced around it.
- Keep card headings, label positions, icon sizes, padding, and borders consistent.
- Make cards 1, 3, and 5 the left column; cards 2, 4, and 6 the right column. Give card 2 extra height and rebalance the others without crowding.
- Render feature labels as compact chips or short one-line rows, never paragraphs.
- In card 2, make "JSON Schema ⇄ A12 DocumentModel ⇄ OpenAPI" a clear bidirectional horizontal bridge, with the supporting labels beneath it.
- In card 5, show dry-run feeding a two-panel semantic diff, then the safety and Kernel assurance badge.
- In card 6, show a canonical Document entering validation and computation, then a JSON result envelope leaving.
- Use only six hub spokes. Avoid connections between capability cards.
- Finish with one aligned bottom rail of six evidence pills.

Visual language:
- Heavy near-black outlines, rounded rectangles, pill labels, simple circular region numbers, and generous gutters.
- Flat, colorful technical icons with consistent dark outlines and no realistic shading.
- Neutral canvas with a mint accent, pale cyan, pale lavender, pale green, pale yellow, saturated purple, and saturated blue.
- Use bold geometric sans-serif type. Keep titles prominent, card labels medium, and captions compact but fully readable at full resolution.
- Preserve a strong hierarchy despite the density: title, hub, card headings, main bridge, labels, evidence pills.
- Keep all text horizontal and fully legible.

Avoid:
- No ByteByteGo logo, wordmark, watermark, A12 logo, mgm branding, or copied poster composition.
- No photorealistic imagery, 3D icons, gradients, neon glow, glassmorphism, blurred shadows, decorative textures, or generic stock art.
- Do not present dmtool as only a safe editor, only a semantic-diff tool, or mainly a Kernel-versus-Interpreter wrapper.
- No shell commands, source code, invented CLI verbs, invented diagnostics, invented versions, tiny paragraphs, illegible text, duplicated words, random arrows, unlabeled crossings, ambiguous line endings, excessive nesting, inconsistent icons, or decorative charts.
```

## Infographic 3: A12 Interpreter runtime

### Accepted output

<p align="center">
  <a href="../assets/infographics/a12-interpreter-runtime.png">
    <img src="../assets/infographics/a12-interpreter-runtime.png" alt="A12 Interpreter preparation, reusable request lifecycle, outputs, and Kernel conformance evidence" width="540">
  </a>
</p>
<p align="center"><sub>1024 × 1536 PNG. Click the preview to open the full-resolution image.</sub></p>

### Content map

- Ten-second takeaway: a workspace becomes one reusable PreparedModel, while each request gets its own Interpreter and immutable Document snapshot across JVM, Node.js, browser, and native dmtool.
- Exact title: `A12 Interpreter: Prepare Once, Run Everywhere`
- Primary layout: layered architecture.
- Reading direction: top to bottom.
- Focal mechanism: the PreparedModel and per-request Interpreter boundary.
- Major regions: workspace, preparation, request, operations, results, targets, verification.
- Final output: ValidationReport and ComputationReport across four consumers.

### Exact relationships

- Workspace sources -> collect + expand -> ModelWorkspace.
- ModelWorkspace -> prepare once -> PreparedModel.
- PreparedModel -> creates -> per-request Interpreter.
- A12 Document JSON -> decodes to -> immutable Document snapshot.
- Interpreter + Document -> run -> validate + compute + apply.
- Operations -> return -> ValidationReport + ComputationReport.
- PreparedModel -> serves -> concurrent requests.
- Differential verification -> compares with -> A12 Kernel 30.8.1.
- One common engine -> runs on -> JVM + Node.js + browser + native dmtool.

### Generation prompt

```text
Use case: infographic-diagram
Asset type: GitHub README technical infographic
Primary request: Create an original, polished portrait technical infographic explaining the A12 Interpreter preparation and request lifecycle, using a clean ByteByteGo-inspired information-design language without copying an existing poster or using the ByteByteGo logo or wordmark.

Communication goal:
- Audience: TypeScript, Kotlin, Java, browser, and server developers evaluating the A12 Interpreter.
- Ten-second takeaway: a workspace becomes one reusable PreparedModel, while each request gets its own Interpreter and immutable Document snapshot across JVM, Node.js, browser, and native dmtool.
- Exact title: "A12 Interpreter: Prepare Once, Run Everywhere".
- Tone: technically precise, compact, approachable, and visually scannable.

Canvas and header:
- Tall 2:3 portrait composition at high resolution.
- Very light warm-gray background.
- Large bold sans-serif title at the top left.
- Short mint-green vertical bar immediately left of the title.
- Leave the opposite header corner clean.
- Reserve a clear gap between the header and the diagram body.

Primary information structure:
- Use a layered architecture as the single dominant layout skeleton.
- Primary reading direction: top to bottom.
- Focal mechanism: the boundary between the reusable PreparedModel and a per-request Interpreter.
- Major regions in reading order: WORKSPACE, PREPARE ONCE, EACH REQUEST, OPERATIONS, RESULTS, RUNS ON.
- Place a narrow VERIFICATION rail beside the main path.
- Final output: ValidationReport and ComputationReport across four consumers.

Exact content:
- Use these labels exactly as written and add no other text.
- Title: "A12 Interpreter: Prepare Once, Run Everywhere"
- Region: "WORKSPACE"
- Nodes: "Finite sources" and "On-demand provider"
- Node: "ModelWorkspace"
- Arrow label: "collect + expand"
- Region: "PREPARE ONCE"
- Focal node: "PreparedModel"
- PreparedModel caption: "ASTs • indexes • plans • dependencies"
- PreparedModel pill: "Shared across requests"
- Region: "EACH REQUEST"
- Input node: "A12 Document JSON"
- Node: "Immutable Document snapshot"
- Node: "Interpreter"
- Interpreter pill: "Per request"
- Region: "OPERATIONS"
- Operation pills: "validateFull", "validatePart", "compute", "apply"
- Region: "RESULTS"
- Output nodes: "ValidationReport" and "ComputationReport"
- Region: "RUNS ON"
- Target pills: "JVM", "Node.js", "Browser", "native dmtool"
- Side rail title: "VERIFICATION"
- Side rail node: "A12 Kernel 30.8.1"
- Side rail labels: "Differential tests", "Fuzzing", "Portable corpus", "Generated Java co-evidence"
- Side rail conclusion: "Kernel-matched on the exercised surface"
- Feature pills: "Canonical pointers", "Custom callbacks", "Explicit unsupported", "Limits + cancellation"
- Main arrow labels: "collect + expand", "prepare once", "create", "decode", "run", "return"

Semantic mapping:
- Workspace = folder and dependency-tree icon + pale cyan + rounded container.
- PreparedModel = reusable model chip icon + saturated purple + large rounded container.
- Document snapshot = document tree icon + pale yellow + rounded node.
- Interpreter = evaluator chip icon + mint + rounded node.
- Operations = compact dark-outlined pills with pale green fill.
- Results = report icons + saturated blue + rounded nodes.
- Runtime targets = platform icons in one pale lavender band.
- Verification = shield and comparison icon + pale green rail + dashed purple connectors.

Layout-specific composition:
- Align the primary lifecycle vertically.
- Make each major layer a wide labeled container.
- Expand PREPARE ONCE and EACH REQUEST to make their ownership boundary unmistakable.
- Place the VERIFICATION rail beside, not on, the main lifecycle.
- Put runtime targets and reports in a conclusive bottom region.

Visual language:
- Heavy near-black outlines, rounded rectangles, pill labels, and generous gutters.
- Flat, colorful technical icons with consistent dark outlines and no realistic shading.
- Neutral canvas with a mint accent, pale cyan, pale lavender, pale green, pale yellow, saturated purple, and saturated blue.
- Keep all text horizontal and fully legible.

Avoid:
- No ByteByteGo logo, wordmark, watermark, A12 logo, mgm branding, or copied poster composition.
- No photorealistic imagery, 3D icons, gradients, neon glow, glassmorphism, blurred shadows, decorative textures, or generic stock art.
- Do not imply that PreparedModel and Interpreter have the same lifetime or thread-sharing contract.
- Do not claim universal equivalence with every possible Kernel input.
- No tiny paragraphs, illegible text, invented labels, duplicated words, random arrows, unlabeled crossings, ambiguous line endings, excessive nesting, inconsistent icons, or decorative charts.
```

## Infographic 4: Interpreter vs A12 Kernel

### Accepted output

<p align="center">
  <a href="../assets/infographics/interpreter-vs-kernel.png">
    <img src="../assets/infographics/interpreter-vs-kernel.png" alt="Measured A12 Interpreter performance and resident memory compared with the A12 Kernel" width="540">
  </a>
</p>
<p align="center"><sub>1126 × 1397 PNG. Click the preview to open the full-resolution image.</sub></p>

### Content map

- Ten-second takeaway: after exact-result preflight, the measured Interpreter scenarios use less time and less resident memory than both compared Kernel strategies.
- Exact title: `Interpreter vs A12 Kernel: Same Results, Different Cost`
- Primary layout: split-screen comparison with shared measurement cards.
- Reading direction: top to bottom.
- Focal mechanism: a cross-engine warm-validation bar comparison over the same 10,000-row Document.
- Major regions: eligibility gate, warm validation, benchmark range, resident model memory, browser context, measurement boundary.
- Final output: a bounded evidence statement, not a universal benchmark claim.

### Exact relationships

- Exact result preflight -> admits -> timed comparison.
- Interpreter JVM -> validates -> 10,000-row Document in 17.5 ms.
- Kernel generated Java -> validates -> same Document in 695.4 ms.
- Kernel dynamic Groovy -> validates -> same Document in 794.7 ms.
- PreparedModel -> retains -> 54 to 325 kB.
- Kernel generated Java -> retains -> 4.2× to 8.1× more memory.
- Kernel dynamic Groovy -> retains -> 18.5× to 37.9× more memory.

### Generation prompt

```text
Use case: infographic-diagram
Asset type: GitHub README technical benchmark infographic
Primary request: Create an original, polished portrait technical infographic comparing measured A12 Interpreter performance and resident memory with two A12 Kernel evaluation strategies, using a clean ByteByteGo-inspired information-design language without copying an existing poster or using the ByteByteGo logo or wordmark.

Communication goal:
- Audience: software architects, performance engineers, and developers evaluating the A12 Interpreter.
- Ten-second takeaway: after exact-result preflight, the measured Interpreter scenarios use less time and less resident memory than both compared Kernel strategies.
- Exact title: "Interpreter vs A12 Kernel: Same Results, Different Cost".
- Tone: evidence-led, technically precise, compact, approachable, and visually scannable.

Canvas and header:
- Light 4:5 portrait composition at high resolution.
- Very light warm-gray background.
- Large bold sans-serif title at the top left.
- Short mint-green vertical bar immediately left of the title.
- Leave the opposite header corner clean.
- Reserve a clear gap between the header and the diagram body.

Primary information structure:
- Use a split-screen comparison with shared horizontal benchmark cards as the single dominant layout skeleton.
- Primary reading direction: top to bottom.
- Focal mechanism: a warm-validation bar comparison over the same 10,000-row Document.
- Major regions in reading order: ELIGIBILITY GATE, WARM VALIDATION, BENCHMARK RANGE, RESIDENT MODEL MEMORY, BROWSER CONTEXT, MEASUREMENT BOUNDARY.
- Final output: a bounded evidence statement, not a universal benchmark claim.

Exact content:
- Use these labels and numbers exactly as written and add no other text.
- Title: "Interpreter vs A12 Kernel: Same Results, Different Cost"
- Top gate title: "ELIGIBILITY GATE"
- Top gate text: "Same model • same Document • identical complete outcomes"
- Top gate pill: "Only then compare"
- Section title: "WARM VALIDATION • 10,000 rows • 4,900 findings"
- Bar label: "Interpreter • JVM"
- Bar value: "17.5 ms"
- Bar label: "Kernel • generated Java"
- Bar value: "695.4 ms"
- Bar label: "Kernel • dynamic Groovy"
- Bar value: "794.7 ms"
- Callout: "≈40× faster vs generated Java"
- Callout: "≈45× faster vs dynamic Groovy"
- Section title: "BENCHMARK RANGE"
- Card label: "Default inventory"
- Card value: "2.8× – 17.1× faster"
- Card label: "Repeated Documents"
- Card value: "12.2× – 16.8× faster"
- Card label: "Hardest measured case"
- Card value: "1.73× faster"
- Card label: "Huge-document edge"
- Card value: "≈152× faster"
- Section title: "RESIDENT MODEL MEMORY • JVM"
- Memory label: "Interpreter"
- Memory value: "54 – 325 kB"
- Memory label: "Kernel • generated Java"
- Memory value: "379 kB – 1.46 MB"
- Memory callout: "4.2× – 8.1× more"
- Memory label: "Kernel • dynamic Groovy"
- Memory value: "1.09 – 11.98 MB"
- Memory callout: "18.5× – 37.9× more"
- Section title: "BROWSER CONTEXT • 200 rows"
- Browser value: "11.55 ms first answer"
- Browser value: "1.1 ms warm"
- Bottom boundary: "Prepared engines • validation only • Kernel codegen outside the clock"
- Bottom evidence: "Measured 2026-08-17 • Apple M1 • JDK 21 • Chrome 151"
- Bottom qualifier: "Bounded same-machine measurements, not universal promises"

Semantic mapping:
- Eligibility gate = shield-check icon + pale green + rounded banner.
- Interpreter = mint fill + evaluator chip icon.
- Kernel generated Java = pale lavender fill + compiled-code icon.
- Kernel dynamic Groovy = pale yellow fill + dynamic-code icon.
- Time = horizontal bars sharing one exact scale, with values printed beside the bars.
- Resident memory = three aligned container icons sharing one visual scale.
- Browser context = compact browser icon + pale cyan callout.

Layout-specific composition:
- Divide comparative regions into aligned Interpreter, Kernel generated Java, and Kernel dynamic Groovy lanes.
- Keep equivalent metrics at matching vertical positions and use the same icon scale and label hierarchy.
- Make the 10,000-row warm-validation bars the largest region.
- Keep benchmark-range cards and resident-memory comparisons compact but fully legible.
- Put the timing boundary, machine identity, and bounded qualifier in a clear bottom evidence band.

Visual language:
- Heavy near-black outlines, rounded rectangles, pill labels, and generous gutters.
- Flat, colorful technical icons with consistent dark outlines and no realistic shading.
- Neutral canvas with a mint accent, pale cyan, pale lavender, pale green, pale yellow, saturated purple, and saturated blue.
- Keep all text horizontal and fully legible.
- Use bars proportional to the printed timing values and do not visually reverse the comparison.

Avoid:
- No ByteByteGo logo, wordmark, watermark, A12 logo, mgm branding, or copied poster composition.
- No photorealistic imagery, 3D icons, gradients, neon glow, glassmorphism, blurred shadows, decorative textures, or generic stock art.
- Do not time Kernel code generation, imply that code generation is a per-request cost, compare unequal results, or present these bounded scenarios as universal performance promises.
- No invented measurements, rounded substitutions, missing units, tiny footnotes, illegible text, duplicated words, decorative axes, random arrows, or inconsistent scales.
```

## Infographic 5: Kotlin, GraalVM, and A12 Kernel roles

### Accepted output

<p align="center">
  <a href="../assets/infographics/kotlin-graalvm-kernel-roles.png">
    <img src="../assets/infographics/kotlin-graalvm-kernel-roles.png" alt="How Kotlin Multiplatform, GraalVM Native Image, and the A12 Kernel divide responsibilities across a12-dmkits" width="540">
  </a>
</p>
<p align="center"><sub>1024 × 1536 PNG. Click the preview to open the full-resolution image.</sub></p>

### Content map

- Ten-second takeaway: Kotlin carries one portable evaluation engine, GraalVM delivers the complete JVM application as a native `dmtool`, and the real A12 Kernel remains the authority for model acceptance and conformance.
- Exact title: `Kotlin, GraalVM & A12 Kernel`
- Exact subtitle: `Portable engine • native delivery • authoritative acceptance`
- Primary layout: comparative small multiples with one shared Kotlin-versus-Java table and one compact architectural-benefits band.
- Reading direction: three role cards from left to right, the language table from top to bottom, then the benefits synthesis from left to right.
- Focal mechanism: the three technology-role cards, supported by a compact structural and runtime comparison of the Java and Kotlin codebases.
- Major regions: Kotlin Multiplatform, GraalVM Native Image, A12 Kernel, Kotlin versus Java, why this split scales.
- Final output: a clear division of technology responsibilities, the Java/Kotlin component and runtime map, and the architectural leverage created by that separation.

### Exact relationships

- Kotlin common code -> compiles to -> sibling JVM + JavaScript targets.
- One Kotlin common codebase -> branches into -> multiple target environments.
- Kotlin/JVM Interpreter -> runs inside -> native dmtool.
- Kotlin/JS Interpreter -> runs in -> Node.js + browser.
- GraalVM Native Image -> AOT-compiles -> the `:cli` JVM application stack.
- Per-OS native dmtool -> serves -> CLI + MCP + agent-plugin use cases without a Java installation.
- Java dmkits -> authors + edits -> a DocumentModel.
- Real A12 Kernel -> confirms -> the authored DocumentModel.
- Kotlin/JVM Interpreter -> runs -> the accepted DocumentModel inside native dmtool.
- A12 Kernel JVM -> verifies semantics through -> differential tests -> Kotlin Interpreter.
- `:adapter` -> confines -> Kernel types behind data and SPI boundaries.
- Java production code -> implements -> dmkits + adapter + cli.
- Kotlin production code -> implements -> interpreter.
- Kotlin test code -> implements -> adapter differential laws.
- Java 21 -> compiles to -> JVM bytecode -> GraalVM Native Image.
- Kotlin Multiplatform -> compiles to -> Kotlin/JVM + Kotlin/JS.
- Portability -> enables -> more runtime integrations.
- Native distribution -> enables -> easier automation and adoption.
- One model authority -> enables -> safe evolution with conformance evidence.

### Generation prompt

```text
Use case: infographic-diagram
Asset type: GitHub README technical infographic
Primary request: Create an original, polished portrait technical infographic explaining the distinct roles of Kotlin Multiplatform, GraalVM Native Image, and the A12 Kernel across a12-dmkits, using a clean ByteByteGo-inspired information-design language without copying an existing poster or using the ByteByteGo logo or wordmark.

Communication goal:
- Audience: software engineers, solution architects, A12 model developers, and technical decision-makers.
- Ten-second takeaway: Kotlin carries one portable evaluation engine, GraalVM delivers the complete JVM application as a native dmtool, and the real A12 Kernel remains the authority for model acceptance and conformance.
- Exact title: "Kotlin, GraalVM & A12 Kernel".
- Exact subtitle: "Portable engine • native delivery • authoritative acceptance".
- Tone: technically precise, compact, approachable, and visually scannable.

Canvas and header:
- Tall 2:3 portrait composition at high resolution.
- Fully opaque warm off-white background extending to every canvas edge.
- Large bold sans-serif title at the top left.
- Short mint-green vertical bar immediately left of the title.
- Put the subtitle directly below the title in smaller bold type.
- Leave the opposite header corner clean.
- Reserve a clear gap between the header and the diagram body.

Primary information structure:
- Use comparative small multiples as the single dominant layout skeleton.
- Primary reading direction: scan the three equal technology cards from left to right, read the compact KOTLIN vs JAVA table from top to bottom, then scan WHY THIS SPLIT SCALES from left to right.
- Put three equal-height rounded cards across the upper two-thirds: KOTLIN MULTIPLATFORM, GRAALVM NATIVE IMAGE, and A12 KERNEL.
- Give every card the same row structure: role tag, components, environment or targets, use case, why, payoff, and one boundary or guardrail where required.
- Use a compact wide lower table to compare Java and Kotlin by size, structure, function, technology derivation, and target runtime.
- Use the remaining bottom space for a three-column benefits band that synthesizes architectural rationale and potential without introducing a process diagram.
- Final output: the language table and benefits band complete the technology-role cards without repeating the toolchain, dmtool workflow, Interpreter internals, or performance topics of the other infographics.

Exact content:
- Use these labels exactly as written and add no other text.
- Title: "Kotlin, GraalVM & A12 Kernel"
- Subtitle: "Portable engine • native delivery • authoritative acceptance"
- Card title: "KOTLIN MULTIPLATFORM"
- Card role tag: "Portable runtime"
- Row label: "COMPONENTS"
- Row value: ":interpreter"
- Row value: ":adapter differential tests"
- Row label: "TARGETS"
- Row value: "JVM • JavaScript"
- Row label: "RUNS IN"
- Row value: "JVM • Node.js • Browser • native dmtool"
- Row label: "USE CASE"
- Row value: "Validate • compute • apply"
- Row label: "WHY"
- Row value: "One common semantics engine"
- Row label: "PAYOFF"
- Row value: "Portable APIs • less runtime drift"
- Card title: "GRAALVM NATIVE IMAGE"
- Card role tag: "Native delivery"
- Row label: "COMPONENT"
- Row value: ":cli / dmtool"
- Row label: "COMPILES"
- Row value: "Java kits • :adapter • Kotlin/JVM Interpreter • A12 Kernel"
- Row label: "SHIPS FOR"
- Row value: "macOS arm64"
- Row value: "Linux x64 + arm64"
- Row value: "Windows x64"
- Row label: "USE CASE"
- Row value: "CLI • MCP • agent plugins"
- Row label: "WHY"
- Row value: "AOT compile the JVM stack"
- Row label: "PAYOFF"
- Row value: "Self-contained • fast start • no Java install"
- Row label: "GUARDRAIL"
- Row value: "Reflection + resources gated"
- Card title: "A12 KERNEL"
- Card role tag: "Domain authority"
- Row label: "COMPONENTS"
- Row value: ":adapter + embedded dmtool"
- Row value: "root + :adapter tests"
- Row label: "RUNS AS"
- Row value: "JVM oracle • native structural tier"
- Row label: "USE CASE"
- Row value: "Check • parse • format • read • rewrite"
- Row label: "WHY"
- Row value: "A12 owns the model contract"
- Row label: "PAYOFF"
- Row value: "Kernel-accepted models • conformance evidence"
- Row label: "BOUNDARY"
- Row value: "Not the dmtool runtime evaluator"
- Bottom table title: "KOTLIN vs JAVA"
- Column header: "JAVA"
- Column header: "KOTLIN"
- Row label: "SIZE"
- Java size: "47.7 kLOC"
- Kotlin size: "19.1 kLOC"
- Row label: "STRUCTURE"
- Java structure: "dmkits • adapter • cli"
- Kotlin structure: "interpreter • adapter laws (tests)"
- Row label: "FUNCTION"
- Java function: "Model tooling • Kernel integration • CLI/MCP"
- Kotlin function: "Validation • computation"
- Row label: "TECHNOLOGY"
- Java technology: "Java 21 → JVM bytecode → GraalVM Native Image"
- Kotlin technology: "Kotlin Multiplatform → Kotlin/JVM + Kotlin/JS"
- Row label: "RUNTIME"
- Java runtime: "JVM • native dmtool"
- Kotlin runtime: "JVM • native dmtool • Node.js • Browser"
- Bottom scope note: "Production code • Tokei • generated/build/fixtures/dependencies excluded"
- Bottom band title: "WHY THIS SPLIT SCALES"
- Benefits heading: "PORTABILITY"
- Benefits line: "One semantics engine"
- Benefits line: "More runtime integrations"
- Benefits heading: "DISTRIBUTION"
- Benefits line: "One native executable"
- Benefits line: "Easier automation and adoption"
- Benefits heading: "TRUST"
- Benefits line: "One model authority"
- Benefits line: "Safe evolution with conformance evidence"

Semantic mapping:
- Kotlin Multiplatform = one purple source circle on the left branching into three right-pointing arrow paths + pale lavender card + saturated purple role tag.
- GraalVM Native Image = compact executable cube or chip icon + pale cyan card + saturated blue role tag.
- A12 Kernel = shield-check engine icon + pale green card + saturated green role tag.
- Java comparison column = pale yellow field + dark header pill.
- Kotlin comparison column = pale lavender field + saturated purple header pill.
- Technology derivation = inline horizontal arrows inside the owning table cells.
- Portability benefit = pale lavender field + saturated purple heading.
- Distribution benefit = pale cyan field + saturated blue heading.
- Trust benefit = pale green field + saturated green heading.

Layout-specific composition:
- Give each technology its own pastel panel.
- Repeat the same internal coordinate system and row hierarchy in all three cards.
- Keep common row labels at matching vertical positions and use additions only for technology-specific boundary or guardrail details.
- Keep the upper cards visually equal; do not imply that one technology owns the others.
- Make KOTLIN vs JAVA one compact full-width comparison table with a narrow row-label column and two equal language columns. Minimize row padding while keeping every value comfortably legible.
- Align equivalent cells exactly. Put 47.7 kLOC and 19.1 kLOC in the largest type within the table.
- Keep each structure, function, technology, and runtime value to one or two lines. The technology arrows stay inside their own cells and never cross a column boundary.
- Put the scope note in one restrained full-width footer inside the table boundary.
- Put WHY THIS SPLIT SCALES in one compact full-width rounded band below the table. Use three equal columns with no arrows: portability, distribution, and trust.
- Keep this final band about architectural rationale and future leverage. Do not depict a toolchain lifecycle, dmtool workflow, Interpreter internals, or performance comparison.

Visual language:
- Heavy near-black outlines, rounded rectangles, pill labels, simple section tabs, and generous gutters.
- Flat, colorful technical icons with consistent dark outlines and no realistic shading.
- Neutral canvas with a mint accent, pale cyan, pale lavender, pale green, pale yellow, saturated purple, saturated blue, and saturated green.
- Use bold geometric sans-serif type. Keep titles prominent, row labels compact, and values fully readable at full resolution.
- Keep all text horizontal and fully legible.

Avoid:
- No ByteByteGo logo, wordmark, watermark, A12 logo, mgm branding, Kotlin logo, GraalVM logo, or copied poster composition.
- No photorealistic imagery, 3D icons, gradients, neon glow, glassmorphism, blurred shadows, decorative textures, or generic stock art.
- Do not imply that GraalVM provides model semantics, that Kotlin validates model structure, that the Kernel is the dmtool runtime evaluator, or that the standalone JavaScript package embeds the Kernel.
- Do not imply that adapter laws are production Kotlin or that the kLOC values include tests, generated code, fixtures, build output, or dependencies.
- Do not reverse the Kotlin branching metaphor: it must show one source at the left branching into multiple right-pointing outputs.
- Do not turn WHY THIS SPLIT SCALES into a workflow, runtime architecture, or benchmark panel.
- No invented components, targets, platforms, performance numbers, tiny paragraphs, illegible text, duplicated words, random arrows, unlabeled crossings, ambiguous line endings, excessive nesting, inconsistent icons, or decorative charts.
```

## Infographic 6: JSON Schema functionality

### Accepted output

<p align="center">
  <a href="../assets/infographics/json-schema-document-model.png">
    <img src="../assets/infographics/json-schema-document-model.png" alt="How schemakit imports JSON Schema and OpenAPI into kernel-valid A12 DocumentModels and exports them with explicit fidelity" width="540">
  </a>
</p>
<p align="center"><sub>1536 × 1024 PNG. Click the preview to open the full-resolution image.</sub></p>

### Content map

- Ten-second takeaway: schemakit imports broad JSON Schema and OpenAPI structure and constraints into a kernel-valid DocumentModel, while export distinguishes native projection, A12 carriage, and explicit loss.
- Exact title: `JSON Schema ⇄ A12 DocumentModel`
- Exact subtitle: `Import broadly • export honestly • report every decision`
- Primary layout: wide four-column bidirectional translation map.
- Reading direction: left to right across one shared external-schema ↔ A12 bridge, four aligned capability columns, then the fidelity contract.
- Focal mechanism: two parallel, oppositely directed arrows connecting the same external-schema node and A12 DocumentModel node.
- Major regions: shared external schema endpoint, A12 DocumentModel, import resolution, model mapping, synthesized rules and reporting, export, fidelity contract.
- Final output: an auditable translation in which every mapping decision or loss is visible.

### Exact relationships

- one shared JSON Schema or OpenAPI node -> enters through -> `model import-jsonschema` -> A12 DocumentModel.
- `AUTO` -> detects -> JSON Schema or OpenAPI 3.0 / 3.1 / 3.2.
- `$ref` and `$defs` -> resolve into -> typedefs, inlined groups, or bundle includes.
- `object` -> maps to -> group.
- `array<object>` -> maps to -> repeatable group.
- scalar -> maps to -> typed field.
- direct constraints -> map to -> requiredness and field config.
- constraints without a field-config home -> synthesize -> kernel-valid a12 rules.
- uncertain choices -> follow -> best-effort MappingProfile or strict omission.
- every guess or omission -> appears in -> transcoding report with corrective action.
- A12 DocumentModel -> exits through -> `model export-jsonschema` -> the same shared JSON Schema or OpenAPI node that supplies import.
- groups, fields, requiredness, and typedefs -> export as -> native JSON Schema structure.
- Tier-A rule shapes -> project to -> native JSON Schema keywords.
- other rules and computations -> ride -> `x-a12-rule` and `x-a12-computation`.
- temporal and custom fields without a native export mapping -> fall back to -> string plus diagnostic.
- verification -> checks -> Kernel acceptance, structural round-trip, meta-schema validity, and report accuracy.

### Generation prompt

```text
Use case: infographic-diagram
Asset type: GitHub README technical infographic
Primary request: Create an original, polished landscape technical infographic explaining the built JSON Schema and OpenAPI transcoding functionality in a12-dmkits schemakit, using a clean ByteByteGo-inspired information-design language without copying an existing poster or using the ByteByteGo logo or wordmark.

Communication goal:
- Audience: software engineers, API designers, A12 model developers, coding-agent users, and technical decision-makers.
- Ten-second takeaway: schemakit imports broad JSON Schema and OpenAPI structure and constraints into a kernel-valid A12 DocumentModel, while export distinguishes native projection, A12 carriage, and explicit loss.
- Exact title: "JSON Schema ⇄ A12 DocumentModel".
- Exact subtitle: "Import broadly • export honestly • report every decision".
- Tone: technically precise, compact, approachable, and visually scannable.

Canvas and header:
- Wide 3:2 landscape composition at high resolution, preferably 1536 × 1024 or larger.
- Fully opaque warm off-white background extending to every canvas edge.
- Large bold sans-serif title at the top left.
- Short mint-green vertical bar immediately left of the title.
- Put the subtitle directly below the title in smaller bold type.
- Leave the opposite header corner clean.
- Reserve a clear gap between the header and the diagram body.

Primary information structure:
- Use a wide four-column bidirectional translation map as the single dominant layout skeleton.
- Primary reading direction: left to right across the shared bridge, then across four aligned capability columns, then the FIDELITY CONTRACT footer.
- Put one compact SOURCE DIALECTS rail below the subtitle.
- Put one clear horizontal bridge below it with exactly two endpoint nodes: JSON Schema / OpenAPI on the left and A12 DocumentModel on the right.
- Connect those same two nodes with two separate parallel arrows: a green right-pointing arrow labeled import-jsonschema from JSON Schema / OpenAPI to A12 DocumentModel, and a blue left-pointing arrow labeled export-jsonschema from A12 DocumentModel back to JSON Schema / OpenAPI. Both arrowheads must visibly terminate at their correct endpoint boxes.
- Under the bridge, place four equal rounded columns from left to right: IMPORT with DETECT & RESOLVE, BUILD THE MODEL with MAP STRUCTURE and MAP CONSTRAINTS, RULE SYNTHESIS with the rule pills and REPORT, and EXPORT with its four compact stages.
- Finish with one compact three-part FIDELITY CONTRACT band spanning the full width.
- Final output: an auditable translation in which every mapping decision or loss is visible.

Exact content:
- Use these labels exactly as written and add no other text.
- Title: "JSON Schema ⇄ A12 DocumentModel"
- Subtitle: "Import broadly • export honestly • report every decision"
- Top rail title: "SOURCE DIALECTS"
- Top rail pills: "JSON Schema 2020-12", "Draft-07 read", "OpenAPI 3.0", "OpenAPI 3.1 / 3.2", "JSON • YAML"
- Bridge source: "JSON Schema / OpenAPI"
- Bridge center: "A12 DocumentModel"
- Right-pointing bridge label: "import-jsonschema"
- Left-pointing bridge label: "export-jsonschema"
- First column title: "IMPORT"
- Left column tag: "BROAD COVERAGE"
- Left stage title: "1  DETECT & RESOLVE"
- Left stage lines: "AUTO dialect", "$ref • $defs", "component • whole bundle"
- Second column title: "BUILD THE MODEL"
- Second column stage title: "2  MAP STRUCTURE"
- Left stage lines: "object → group", "array<object> → repeatable group", "scalar → typed field", "leaf $ref → typedef", "bundle object ref → include"
- Second column stage title: "3  MAP CONSTRAINTS"
- Left stage lines: "direct → requiredness + field config", "composed → a12 rules", "choices → MappingProfile"
- Third column title: "RULE SYNTHESIS"
- Third column stage title: "4  REPORT"
- Left stage lines: "mapped • omitted • notes", "corrective TO-DO", "never silent"
- Fourth column title: "EXPORT"
- Right column tag: "BEST-EFFORT"
- Right stage title: "1  EMIT NATIVE"
- Right stage lines: "groups • fields • required", "typedefs → $defs + $ref"
- Right stage title: "2  PROJECT"
- Right stage line: "Tier-A rules → keywords"
- Right stage title: "3  CARRY"
- Right stage lines: "rules → x-a12-rule", "computations → x-a12-computation"
- Right stage title: "4  DISCLOSE"
- Right stage lines: "temporal + custom → string", "diagnostic for every loss"
- Rule pills: "exclusive bounds", "multipleOf • const", "dependentRequired", "uniqueItems • minItems", "if / then / else", "not • contains", "union discriminators"
- Bottom band title: "FIDELITY CONTRACT"
- Bottom heading: "IMPORT"
- Bottom line: "Map or report • never silent"
- Bottom heading: "EXPORT"
- Bottom line: "Native where faithful • carriage otherwise"
- Bottom heading: "VERIFY"
- Bottom line: "Kernel accept • round-trip • meta-schema"

Semantic mapping:
- External schema = document-with-braces icon + pale cyan rounded node.
- A12 DocumentModel = model-tree icon + saturated purple rounded node.
- Import = right-pointing saturated green arrow + pale green column.
- Export = left-pointing saturated blue arrow + pale blue column.
- MappingProfile choices and transcoding report = sliders and checklist icons + pale yellow accents.
- Synthesized rules = condition-tree icons + pale lavender band.
- Native mapping = solid node or arrow; carriage = dashed purple container; explicit loss = amber diagnostic triangle.

Layout-specific composition:
- Use a wide landscape grid with four equal capability columns below one full-width bridge.
- Keep the two bridge endpoint boxes identical in height and align them horizontally.
- Draw the import and export arrows as separate parallel routes between those exact same boxes; do not leave either arrow floating or point export toward A12.
- Keep IMPORT, BUILD THE MODEL, and RULE SYNTHESIS visually grouped as the broad import side while EXPORT remains a distinct blue field.
- Render stage details as short rows or pills, never paragraphs.
- Arrange the seven rule pills as a compact two-row grid inside the third column.
- Keep FIDELITY CONTRACT visually conclusive and readable at README preview width.

Visual language:
- Heavy near-black outlines, rounded rectangles, pill labels, simple stage numbers, and generous gutters.
- Flat, colorful technical icons with consistent dark outlines and no realistic shading.
- Neutral canvas with a mint accent, pale cyan, pale blue, pale green, pale lavender, pale yellow, saturated purple, saturated blue, and saturated green.
- Use bold geometric sans-serif type. Keep the title prominent, stage headings strong, and detail rows compact but fully readable.
- Keep all text horizontal and fully legible.

Avoid:
- No ByteByteGo logo, wordmark, watermark, A12 logo, mgm branding, JSON Schema logo, OpenAPI logo, or copied poster composition.
- No photorealistic imagery, 3D icons, gradients, neon glow, glassmorphism, blurred shadows, decorative textures, or generic stock art.
- Do not imply symmetric fidelity, silent omissions, universal lossless export, runtime validation by a stock JSON Schema validator, or direct schemakit coupling to raw Kernel types.
- Do not imply that a feature omission aborts the rest of an import; entry-point selection and invalid input remain separate invocation preconditions.
- Do not turn this into the broader dmtool lifecycle or repeat the workbench infographic.
- No invented keywords, mapping tiers, dialects, constraints, commands, performance claims, tiny paragraphs, illegible text, duplicated words, random arrows, ambiguous direction, excessive nesting, or decorative charts.
```

## Infographic 7: Semantic model diff

### Accepted output

<p align="center">
  <a href="../assets/infographics/semantic-model-diff.png">
    <img src="../assets/infographics/semantic-model-diff.png" alt="How dmtool binds two model snapshots, ignores formatting noise, classifies semantic changes, and ranks review impact" width="540">
  </a>
</p>
<p align="center"><sub>1024 × 1536 PNG. Click the preview to open the full-resolution image.</sub></p>

### Content map

- Ten-second takeaway: `model diff` independently binds two model snapshots, compares authored structure rather than text, recognizes rule and refactor meaning conservatively, and puts silent-risk changes first.
- Exact title: `Semantic Model Diff`
- Exact subtitle: `Compare model meaning • rank review impact`
- Primary layout: sequential review pipeline with one impact-ranking conclusion.
- Reading direction: baseline inputs, independent binding, noise gate, complete comparison, semantic classifiers, impact tiers, trusted outputs.
- Focal mechanism: AST-level condition analysis and asymmetric HIGH / MEDIUM / LOW review-impact ranking.
- Major regions: two snapshots, parse and bind, formatting-noise gate, complete authored state, condition and refactor analysis, impact ranking, outputs.
- Final output: a tool-computed structural change-set and an impact-sorted human review view.

### Exact relationships

- base file and its dependency snapshot -> bind independently from -> head file and its dependency snapshot.
- `--since <ref>` -> resolves -> base model and dependencies at the Git ref.
- working tree -> supplies -> head model and dependencies.
- parsed authored state -> removes -> formatting-only noise.
- authored order -> remains -> semantic state.
- model diff -> compares -> fields, groups, rules, computations, typedefs, includes/imports, configuration, and element position.
- typed deltas -> preserve -> deterministic before/after strings plus typed values.
- rule AST comparison -> classifies -> clause, reference, operator, and operand edits.
- structural complement -> proves -> `POLARITY_INVERTED`.
- conservative relocation matching -> recognizes -> rename or move.
- rename or move plus rewritten references -> groups into -> one verifiable refactor overlay.
- uncertainty -> remains -> removed plus added.
- review-impact ranking -> places -> loosening and silent acceptance above tightening and loud failure.
- JSON output -> provides -> trusted machine contract.
- `--text` -> provides -> impact-sorted human review.
- `schema model diff` -> describes -> the output contract and reason codes.

### Generation prompt

```text
Use case: infographic-diagram
Asset type: GitHub README technical infographic
Primary request: Create an original, polished portrait technical infographic explaining dmtool's structural semantic model diff and review-impact engine, using a clean ByteByteGo-inspired information-design language without copying an existing poster or using the ByteByteGo logo or wordmark.

Communication goal:
- Audience: software engineers, A12 model reviewers, coding-agent users, business analysts, and technical decision-makers.
- Ten-second takeaway: model diff independently binds two model snapshots, compares authored structure rather than text, recognizes rule and refactor meaning conservatively, and puts silent-risk changes first.
- Exact title: "Semantic Model Diff".
- Exact subtitle: "Compare model meaning • rank review impact".
- Tone: technically precise, trust-focused, compact, approachable, and visually scannable.

Canvas and header:
- Tall 2:3 portrait composition at high resolution, preferably 1024 × 1536 or larger.
- Fully opaque warm off-white background extending to every canvas edge.
- Large bold sans-serif title at the top left.
- Short mint-green vertical bar immediately left of the title.
- Put the subtitle directly below the title in smaller bold type.
- Leave the opposite header corner clean.
- Reserve a clear gap between the header and the diagram body.

Primary information structure:
- Use a sequential review pipeline as the single dominant layout skeleton.
- Primary reading direction: top to bottom through five numbered bands, then the trusted output rail.
- Band 1 starts with two equal snapshots side by side and converges only after each has its own binding boundary.
- Band 2 is a formatting-noise gate.
- Band 3 is the complete authored-state comparison grid.
- Band 4 contains two aligned semantic-analysis cards: RULE AST and REFACTOR.
- Band 5 is the focal three-column HIGH / MEDIUM / LOW review-impact ranking.
- Finish with a compact trusted-output rail.
- Final output: a tool-computed structural change-set and an impact-sorted human review view.

Exact content:
- Use these labels exactly as written and add no other text.
- Title: "Semantic Model Diff"
- Subtitle: "Compare model meaning • rank review impact"
- Band title: "1  BIND BOTH SNAPSHOTS"
- Left snapshot title: "BASE"
- Left snapshot lines: "file + dependency snapshot", "or Git ref via --since"
- Right snapshot title: "HEAD"
- Right snapshot lines: "file + dependency snapshot", "or working tree"
- Binding pill: "Independently resolved"
- Band title: "2  REMOVE TEXT NOISE"
- Noise input: "JSON formatting"
- Noise result: "not a change"
- Order note: "Authored order stays semantic"
- Band title: "3  COMPARE THE COMPLETE MODEL"
- Family pills: "Fields", "Groups", "Rules", "Computations", "Typedefs", "Includes / imports", "Configuration", "Position"
- Delta pill: "before / after + typed values"
- Band title: "4  UNDERSTAND THE CHANGE"
- Left analysis title: "RULE AST"
- Left analysis lines: "clause • reference • operator • operand", "located edit script", "complement → POLARITY_INVERTED"
- Right analysis title: "REFACTOR"
- Right analysis lines: "rename • move", "group rewritten referrers", "unsure → removed + added"
- Band title: "5  RANK REVIEW IMPACT"
- High heading: "HIGH"
- High lines: "constraint loosened or removed", "polarity inverted", "active reader affected"
- Medium heading: "MEDIUM"
- Medium lines: "constraint tightened", "computation or config changed", "element moved"
- Low heading: "LOW"
- Low lines: "message or metadata", "element renamed", "pure reorder"
- Impact callout: "Loosening > tightening"
- Impact explanation: "Silent acceptance deserves first review"
- Bottom rail title: "TRUSTED OUTPUT"
- Bottom output pills: "JSON truth contract", "--text impact view", "schema model diff"
- Bottom benefit pills: "No formatting noise", "Danger first", "Tool-computed trust"

Semantic mapping:
- Base snapshot = document-stack icon + pale cyan rounded card.
- Head snapshot = document-stack icon + pale lavender rounded card.
- Independent binding = two separate dark-outlined resolver boxes that converge into the comparison engine.
- Formatting noise = gray text-lines icon stopped by a filter gate.
- Complete model = eight small aligned family pills in pale yellow and pale green.
- Rule AST = branching condition-tree icon + pale lavender analysis card.
- Refactor = path-relocation and linked-reference icon + pale cyan analysis card.
- HIGH impact = saturated coral-red heading + pale coral field.
- MEDIUM impact = saturated amber heading + pale yellow field.
- LOW impact = saturated green heading + pale green field.
- Trusted output = shield-check icon + saturated purple rail.

Layout-specific composition:
- Stack five full-width stage bands from top to bottom with consistent numbers, borders, and gutters.
- Keep the BASE and HEAD paths separate until both independent binding boxes feed the semantic engine.
- Make the formatting filter visually remove only text noise; never show authored order disappearing.
- Render the eight compared families as a clean two-row grid, not a tree or lifecycle diagram.
- Keep RULE AST and REFACTOR equal in visual weight and contained inside band 4.
- Make the impact tier band the largest and strongest region. Align equivalent headings and detail rows across HIGH, MEDIUM, and LOW.
- Place the asymmetric-impact callout across the impact band without obscuring any tier.
- Make the trusted-output rail visually conclusive and readable at README preview width.

Visual language:
- Heavy near-black outlines, rounded rectangles, pill labels, compact circular stage numbers, and generous gutters.
- Flat, colorful technical icons with consistent dark outlines and no realistic shading.
- Neutral canvas with a mint accent, pale cyan, pale lavender, pale green, pale yellow, pale coral, saturated purple, saturated blue, saturated green, amber, and coral-red.
- Use bold geometric sans-serif type. Keep the title prominent, stage titles strong, and all detail rows compact but fully readable.
- Keep all text horizontal and fully legible.

Avoid:
- No ByteByteGo logo, wordmark, watermark, A12 logo, mgm branding, Git logo, or copied poster composition.
- No photorealistic imagery, 3D icons, gradients, neon glow, glassmorphism, blurred shadows, decorative textures, or generic stock art.
- Do not call this a textual diff, behavioral-equivalence proof, runtime differential, or agent-authored summary. It is a tool-computed structural semantic diff.
- Do not imply that formatting changes are semantic, that authored reordering is ignored, that every removed-and-added pair is a rename or move, or that risk means probability.
- Do not claim a Markdown output view; the built views shown here are JSON and --text.
- Do not repeat the broader dmtool workbench, authoring lifecycle, Interpreter runtime, or performance topics of the other infographics.
- No invented change families, impact reasons, commands, output formats, tiny paragraphs, illegible text, duplicated words, random arrows, ambiguous connectors, excessive nesting, or decorative charts.
```

## Regeneration workflow

1. Revalidate every factual input against the sources in the freshness table. Runtime behavior claims require the real Kernel differential; performance and memory figures require the owning measurement protocol and results record.
2. Update the relevant content map and exact prompt in this file before generating. The prompt is the reviewable source, while the PNG is derived output.
3. Generate each poster separately with the built-in ImageGen tool. Do not batch distinct posters into one prompt.
4. Inspect the full-resolution result for exact text, hierarchy, reading order, connector meaning, repeated-entity consistency, color discipline, mobile legibility, and unauthorized branding.
5. Refine with focused image edits that name only the failed gate and preserve all unaffected layout, copy, palette, and icons.
6. Save the accepted image to its canonical path under `assets/infographics/`, then synchronize the release-source copies named in the registry.
7. Verify every README preview and click target, the byte identity of release copies, the dmtool shared README region, the Interpreter mirror assembly, and the documentation guards.

## Refinement log

Add one row per accepted generation or targeted edit. Record rejected attempts only when the reason changes how a future regeneration should be prompted.

| Date | Infographic | Generation or edit | Accepted outcome and reusable refinement note |
|---|---|---|---|
| 2026-08-28 | A12 dmkits toolchain | Initial built-in ImageGen generation, then a background-only edit | Accepted at 1024 × 1536. Keep the 2:3 layered architecture, all exact labels, and the fully opaque warm off-white outer canvas. |
| 2026-08-28 | dmtool safe editing | Initial built-in ImageGen generation, then a background-only edit | Superseded by the broader workbench concept. The sequential workflow overemphasized safe editing and hid schema exchange, comprehension, semantic review, structural refactors, and runtime automation. |
| 2026-08-28 | dmtool DocumentModel workbench | Fresh built-in ImageGen generation, then a focused RUN & AUTOMATE edit | Accepted at 1024 × 1536. Keep the hub-and-spoke lifecycle, the larger bidirectional schema bridge, each runtime capability listed once, small Kernel and Interpreter assurance badges, and the fully opaque warm off-white canvas. |
| 2026-08-28 | A12 Interpreter runtime | Initial built-in ImageGen generation, then a background-only edit | Accepted at 1024 × 1536. Keep the 2:3 prepare-once and request-many architecture, verification rail, and fully opaque warm off-white outer canvas. |
| 2026-08-28 | Interpreter vs A12 Kernel | Initial built-in ImageGen generation, then a background-only edit | Accepted at 1126 × 1397. Preserve every printed measurement and date, the timing-proportional bars, the comparison boundaries, and the fully opaque warm off-white outer canvas. |
| 2026-08-29 | Kotlin, GraalVM, and A12 Kernel roles | Initial built-in ImageGen generation, then a focused composition-rail edit | Accepted at 1024 × 1536. Keep the three aligned role cards, treat Kotlin/JVM and Kotlin/JS as sibling targets, keep the GraalVM boundary around dmtool's JVM stack only, and render Kernel differential verification as a dashed evidence route. |
| 2026-08-29 | Kotlin, GraalVM, and A12 Kernel roles | Replaced the composition rail with a focused Kotlin-versus-Java table edit | Accepted at 1024 × 1536. Keep the language comparison compact, express each build derivation inline, scope kLOC to production code, and bind both displayed sizes to the generated root Source Statistics table. |
| 2026-08-29 | Kotlin, GraalVM, and A12 Kernel roles | Focused kLOC correction after refreshing the owning Source Statistics tables | Accepted at 1024 × 1536 with 47.7 kLOC Java and 19.1 kLOC Kotlin. Run `update-code-statistics.sh --check` before freezing a count; prompt-to-table synchronization cannot detect a stale owning table by itself. |
| 2026-08-29 | Kotlin, GraalVM, and A12 Kernel roles | Corrected the Kotlin branching metaphor, compressed the language table, and added the architectural-benefits band | Accepted at 1024 × 1536. Keep the Kotlin icon as one source branching to multiple outputs and keep WHY THIS SPLIT SCALES focused on portability, distribution, and trust so this poster does not repeat the toolchain, workbench, Interpreter-runtime, or performance infographics. |
| 2026-08-30 | JSON Schema functionality | Initial built-in ImageGen generation, then focused source-icon and exact-token edits | Accepted at 864 × 1821. Keep the asymmetric import/export columns, use neutral schema-document symbols instead of product logos, and spell code-shaped labels such as `array<object>` and `dependentRequired` exactly. |
| 2026-08-30 | Semantic model diff | Initial built-in ImageGen generation, then a focused header-contrast edit | Accepted at 1024 × 1536. Keep the sequential review pipeline, preserve the distinct structural-versus-text comparison boundary, and render the title against a light header so it remains legible at README preview width. |
| 2026-08-30 | JSON Schema functionality | Rebuilt as a wide four-column layout, corrected the export endpoint, then restored the exact `array<object>` token | Accepted at 1536 × 1024. Wide infographics are valid when the topology benefits; connect import and export to the same external-schema node so both direction and target remain explicit. |

# dmtool CLI — multi-file & workspaces

When you're handed a model that references others, or a whole **directory** of models, these verbs map the cross-model picture: `model info` (one model's outbound references resolved to files) and the `workspace` family (`list`/`graph`/`roles`) over a folder. Commands run through `dmtool` from the repo root; some use `jq`. Re-check with `uvx showboat@0.6.1 verify examples/cli-workspace.md` (exit 0 = output still matches the live CLI).

## model info — one model's header dashboard

`model info` is the model's identity card in a single read: `id`/`modelType`/`modelVersion`, the super/subtype graph (the `abstract`/`superTypes`/`subTypes` convention), and every outbound reference — `include`s and type-def imports — **resolved to its file** (via `-w/--workspace`, default the model's own folder). It carries *counts*, never contents — the field/rule/config detail stays in `model describe`/`model read`/`config read`, so it adds no redundancy. Projected here with `jq`.

```bash
dmtool -m examples/models/multifile/app/storefront.dm.json model info -w examples/models/multifile | jq -c '.data | {id, includes, counts}'
```

```output
{"id":"storefront","includes":[{"alias":"catalog","ref":"catalog","resolvedPath":"lib/catalog.dm.json"}],"counts":{"groups":2,"fields":1,"rules":0,"computations":0,"typeDefinitions":0}}
```

→ `storefront` resolves its `catalog` include to `lib/catalog.dm.json` (the file to hand `-w/--workspace`), and the counts orient you — 2 groups, 1 field, no rules/computations — without dumping their contents. This is the single-model peer of `workspace list` below, which does the same across a whole folder.

## workspace list — what's in a folder of models

When you're handed a **directory** of models, `workspace list` is the cross-model "ls" that goes *into* them: a per-model index where every `include` / type-def import is **cross-resolved to its file within the scan**. So an agent learns which file provides a referenced model — instead of guessing an `-w/--workspace`. Kernel-free (a half-wired workspace still lists); `--recursive` widens the resolution scope, `--validate` adds a per-model validity flag, `--format table` renders the same facts for humans. Projected here with `jq`.

```bash
dmtool workspace list examples/models/multifile --recursive | jq -c '.data.models[] | {id, path, includes: [.includes[] | {ref, resolvedPath}]}'
```

```output
{"id":"storefront","path":"app/storefront.dm.json","includes":[{"ref":"catalog","resolvedPath":"lib/catalog.dm.json"}]}
{"id":"catalog","path":"lib/catalog.dm.json","includes":[]}
```

→ `storefront` declares an `include` of the model id `catalog`, and the scan **resolves it to `lib/catalog.dm.json`** — the file an agent must put on the `-w/--workspace` to load `storefront`. Were the target outside the scan, `resolvedPath` would be `null` (widen with `--recursive`, as here). The same resolution covers type-def imports and surfaces the sub/supertype convention (`abstract`/`superTypes`/`subTypes`), so one read maps a whole workspace.

## workspace graph — the inheritance hierarchy

Where `workspace list` is the flat per-model index, `workspace graph` draws the **sub/supertype hierarchy** a flat list can't show at a glance — subtype→supertype edges from the `superTypes`/`subTypes` convention. It is **inheritance only** (the composition relations — includes/imports — stay in `list`/`model info`, where they're already resolved). `--format tree` renders it for humans:

```bash
dmtool workspace graph examples/models/inheritance --format tree
```

```output
Product_Base (abstract)
  - ProductBundle
  - ProductSingle
```

→ `Product_Base` is the abstract root; its two subtypes nest under it. The default `--format json` returns the same hierarchy as `{nodes, edges}` (each edge `resolved` iff both ends are in the scan — a dangling supertype shows `resolved:false`), and `--format dot` emits a Graphviz digraph.

## workspace roles — does every model gate to a defined role?

A12 models carry a `roles` header annotation (a comma-separated list) naming who may access them; a workspace declares those roles in `auth/roles.yaml` and its users (with the roles each holds) in `auth/users.yaml`. `workspace roles` is the **access-control lint** that joins all three: it resolves every model's gating roles — and every user's authorities — against the roles file and reports the cross-file inconsistencies the kernel never sees. It discovers both files under the workspace root (`auth/roles.yaml` / `auth/users.yaml`, also the project-template `import/auth/…` and the Preview-App conventions), scans the models recursively, and surfaces the defined roles, the users, each model's gating roles, and the findings:

```bash
dmtool workspace roles examples/models/storefront-workspace \
  | jq -c '{rolesFile: .data.rolesFile, usersFile: .data.usersFile, definedRoles: [.data.definedRoles[].name],
            users: [.data.users[] | {username, authorities}], models: [.data.models[] | {id, roles}], findings: .data.findings}'
```

```output
{"rolesFile":"auth/roles.yaml","usersFile":"auth/users.yaml","definedRoles":["shopper","merchant"],"users":[{"username":"alice","authorities":["shopper"]},{"username":"bob","authorities":["merchant"]}],"models":[{"id":"Catalog_DM","roles":["merchant"]},{"id":"Storefront_DM","roles":["shopper","merchant"]}],"findings":[]}
```

→ Both models gate to roles `auth/roles.yaml` defines (`shopper`/`merchant`), so the lint is clean. The headline finding is an **undefined role** — a model gating to a role the file doesn't declare, which even the A12 model editor permits silently. Resolving the same models against a roles file that omits `merchant` surfaces it. The lint is **purely advisory — it warns, it never blocks** (access-control config is often a dev seed or owned by an external IdP, so it must not stop work on the models); the verb **always exits 0**:

```bash
dmtool workspace roles examples/models/storefront-workspace \
  --roles examples/models/shopper-only-roles.yaml 2>&1 \
  | jq -c '{warnings: .data.warnings, findings: [.data.findings[] | {code, modelId, username, role}]}'; echo "(exit ${PIPESTATUS[0]})"
```

```output
{"warnings":3,"findings":[{"code":"UNDEFINED_ROLE","modelId":"Catalog_DM","username":null,"role":"merchant"},{"code":"UNDEFINED_ROLE","modelId":"Storefront_DM","username":null,"role":"merchant"},{"code":"UNDEFINED_AUTHORITY","modelId":null,"username":"bob","role":"merchant"}]}
(exit 0)
```

→ The omission ripples across the whole **RBAC triangle** (models gate to roles ← `roles.yaml` → users hold authorities). `merchant` is referenced in three places this roles file no longer declares — the two models that **gate** to it (`UNDEFINED_ROLE`) and the user **bob** who **holds** it (`UNDEFINED_AUTHORITY`, the membership-edge twin) — so each draws a **warning** (never an error; exit stays 0). The lint surfaces the rest of the triangle too: a model left ungated when a roles file exists (`MISSING_ROLE_ASSIGNMENT`), roles declared with no roles file (`NO_ROLES_FILE`), more than one roles file (`MULTIPLE_ROLES_FILES`), a model gated to roles **no user holds** (`UNREACHABLE_MODEL` — nobody can open it), and, as an `INFO`, a declared role held by no user and gating no model (`ORPHAN_ROLE` — dead). `--roles` / `--users` override discovery.

Every finding carries a **`fix`** alongside its `message` — the concrete remedy, not just the diagnosis (the same `{message, fix}` shape the `RK_*` diagnostic catalog uses). So an agent reads what to *do*, not only what's wrong:

```bash
dmtool workspace roles examples/models/storefront-workspace \
  --roles examples/models/shopper-only-roles.yaml | jq -r '.data.findings[].fix'
```

```output
declare 'merchant' in the workspace roles file, or remove it from Catalog_DM's roles annotation
declare 'merchant' in the workspace roles file, or remove it from Storefront_DM's roles annotation
declare 'merchant' in the workspace roles file, or remove it from bob's authorities
```

→ Each finding names its remedy — declare the role, or drop the reference — so the next action is unambiguous. (A diagnostic `code` you get back from any verb is explorable too: `data.findings[]` codes via that verb's `schema <target> <op>`, `RK_*` codes via `dmtool diagnostics <code>`, `MVK_*` kernel codes via `dmtool operators` / `rule explain`.)

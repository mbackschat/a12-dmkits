# General CLI Tool Best Practices for LLM Agents

**Generated:** 2026-06-28  
**Audience:** software architects, platform engineers, CLI maintainers, developer-experience teams, and teams designing tools consumed by both humans and LLM agents  
**Source context:** extracted and generalized from the Salesforce Headless 360 / MCP tools schema analysis  
**Scope:** practical design recommendations for building general-purpose CLI tools that are efficient, safe, predictable, and maximally useful to LLM coding agents and automation systems.

---

## 1. Executive summary

The biggest lesson from MCP tool design is that a CLI command is no longer only a human-facing terminal interface. When LLM agents use CLI tools, the command becomes part of an agentic execution environment. The agent must discover the command, choose it correctly, construct valid arguments, interpret outputs, recover from failures, decide whether an operation is safe, and possibly compose the command with other tools.

That means a good modern CLI should be designed as a **capability contract** rather than a thin shell wrapper.

A traditional human-oriented CLI often optimizes for readability, colorized output, interactive prompts, progress spinners, terse errors, and flexible inference from local state. Those features are useful for humans but frequently hostile to agents. An agent-oriented CLI should instead optimize for deterministic behavior, structured input and output, explicit side effects, machine-readable schemas, stable errors, non-interactive execution, idempotent writes, dry-run previews, resumable operations, and clear policy boundaries.

The central recommendation is simple:

> Design CLI commands as if each command were an MCP tool transported over a terminal process.

The implementation consequence is significant. A CLI command should have a contract roughly equivalent to an API endpoint or MCP tool definition:

```text
Name and purpose
Input schema
Output schema
Side effects
Required permissions
Preconditions
Confirmation requirements
Idempotency semantics
Exit-code taxonomy
Error model
Examples
Versioning and compatibility rules
```

For software platforms, the ideal architecture is not to hand-maintain a REST API, a CLI, and MCP tools separately. Instead, define platform capabilities once in a capability registry, then generate the CLI parser, MCP tool definition, OpenAPI schema, documentation, tests, and policy metadata from the same source of truth.

---

## 2. What general CLI tools can learn from MCP

MCP tools force designers to make the machine-facing contract explicit. A tool has a name, a description, an input schema, optionally an output schema, and annotations that describe risk characteristics such as read-only, destructive, idempotent, or open-world behavior.

General CLI tools should copy that discipline.

Most CLIs already have implicit contracts. The problem is that those contracts are spread across help text, implementation code, documentation pages, examples, shell conventions, environment variables, exit codes, and tribal knowledge. Humans can compensate. Agents cannot reliably compensate.

The lesson from MCP is that the CLI contract should be explicit, discoverable, and machine-readable.

For example, a human can understand this command with documentation and context:

```bash
myplatform deploy
```

An agent needs much more precision:

```bash
myplatform deploy metadata \
  --project-root /repo \
  --target-env staging \
  --test-level run-local-tests \
  --json \
  --non-interactive \
  --idempotency-key 01JZ2... \
  --timeout 900
```

The agent-friendly version specifies the operation type, target scope, execution mode, output format, non-interactive behavior, retry identity, and timeout.

---

## 3. Core design principles

### 3.1 Treat commands as capabilities, not implementation wrappers

A weak CLI mirrors internal services or scripts:

```text
myplatform call service-x --method runThing --payload payload.json
```

A strong CLI exposes domain capabilities:

```text
myplatform crm account get-context --account-id 001... --json
myplatform service case resolve --case-id 500... --mode preview --json
myplatform deploy metadata --project-root . --target-env staging --json
```

Agents reason better about task-oriented verbs than about low-level implementation details. A command should represent a useful user or platform intent, not merely an internal endpoint.

### 3.2 Optimize for boring determinism

A good agent-facing CLI should be boring. The same command with the same inputs and same environment should produce the same shape of output, the same exit-code semantics, and the same error categories.

Avoid hidden behavior such as:

```text
- inferring production vs staging from an implicit local default;
- silently opening a browser;
- prompting for confirmation in the middle of execution;
- writing config files as a side effect of a read command;
- changing output shape based on terminal width;
- mixing progress logs into stdout;
- returning prose errors that require semantic interpretation.
```

Humans can tolerate surprising behavior. Agents need stable contracts.

### 3.3 Separate human UX from machine UX

Do not choose between beautiful human output and machine-readable output. Support both, but make them explicit modes.

A good default for humans might be:

```bash
myplatform service case get 500123
```

A good mode for agents is:

```bash
myplatform service case get 500123 --json --no-color --non-interactive
```

The machine mode must be strict. In `--json` mode, stdout should contain exactly one JSON document unless JSON Lines is explicitly requested.

### 3.4 Make side effects explicit

Agents must know whether a command reads, creates, updates, deletes, deploys, sends, publishes, charges, emails, or triggers external processes.

Do not hide side effects behind innocent names.

Weak:

```bash
myplatform sync contacts
```

Better:

```bash
myplatform contacts sync-preview --source crm --target marketing --json
myplatform contacts sync-commit --preview-id prev_01JZ... --confirmation-token conf_01JZ... --idempotency-key idem_01JZ... --json
```

Separating preview and commit is one of the most useful safety patterns for agent-facing CLIs.

---

## 4. Recommended global flags

Every serious CLI intended for use by agents should provide a consistent set of global flags. The exact names can vary, but consistency across commands matters more than cleverness.

```text
--json                    Output machine-readable JSON only on stdout.
--pretty                  Pretty-print JSON for human debugging.
--no-color                Disable ANSI color sequences.
--quiet                   Suppress non-essential output.
--verbose                 Emit additional diagnostics to stderr.
--dry-run                 Validate and preview without committing side effects.
--non-interactive         Disable prompts; fail deterministically if interaction is required.
--yes                     Optional human convenience flag; avoid using it as the only confirmation mechanism.
--idempotency-key KEY     Deduplicate retried write operations.
--timeout SECONDS         Bound total execution time.
--cwd PATH                Explicit working directory or project root.
--profile NAME            Explicit account/profile/environment.
--target-env ENV          Explicit runtime target such as dev, staging, prod, sandbox.
--trace-id ID             Correlate CLI execution with logs, traces, and audit records.
--schema                  Return command contract or link to command schema.
```

The most important flags are `--json`, `--non-interactive`, `--dry-run`, `--idempotency-key`, `--timeout`, and explicit target selection such as `--profile` or `--target-env`.

---

## 5. stdout, stderr, and exit-code contract

The CLI must define a strict IO contract.

```text
stdout = machine-readable command result
stderr = diagnostics, logs, warnings, and progress
exit code = top-level process outcome
```

This rule is especially important in `--json` mode.

Bad:

```text
Deploying metadata...
Running tests...
Done!
{"status":"success","operationId":"op_123"}
```

Good:

```text
stderr: 2026-06-28T12:00:00Z INFO Deploy queued
stderr: 2026-06-28T12:00:10Z INFO Running tests
stdout: {"status":"success","operationId":"op_123","componentsDeployed":42}
```

In JSON mode, stdout should be parseable without heuristics. No banners, no spinners, no warnings, no color, no tables, and no mixed prose.

### Recommended exit-code taxonomy

Do not return exit code `1` for every failure. Agents and automation systems need stable categories.

```text
0   success
1   generic failure, no better category available
2   invalid arguments or schema validation failed
3   missing precondition
4   not found
5   permission denied
6   confirmation required
7   version conflict or concurrency conflict
8   policy denied
9   rate limited
10  dependency unavailable
11  timeout or operation incomplete
12  idempotency conflict
13  partial success
14  authentication required
15  unsupported version or incompatible server
16  unsafe target rejected
```

The JSON result should include a matching stable error code. The exit code is useful for scripts; the JSON error object is useful for agents.

---

## 6. JSON output design

### 6.1 Use an envelope

Prefer a stable result envelope instead of returning arbitrary top-level shapes per command.

```json
{
  "status": "success",
  "command": "service.case.resolve",
  "operationId": "op_01JZ...",
  "result": {
    "caseId": "500xx0000012345",
    "newStatus": "Resolved",
    "resolutionId": "res_01JZ..."
  },
  "warnings": [],
  "traceId": "tr_01JZ..."
}
```

For errors:

```json
{
  "status": "error",
  "command": "service.case.resolve",
  "error": {
    "code": "CONFIRMATION_REQUIRED",
    "message": "Resolving this case changes customer-visible status and requires confirmation.",
    "retryable": false,
    "correctableByAgent": true,
    "confirmation": {
      "previewId": "prev_01JZ...",
      "requiredAction": "Ask the user to approve the proposed resolution.",
      "tokenInput": "--confirmation-token"
    }
  },
  "traceId": "tr_01JZ..."
}
```

### 6.2 Keep output compact but complete

LLM agents are sensitive to context size. Avoid returning large blobs by default. Prefer summaries with explicit expansion options.

Good defaults:

```bash
myplatform logs search --query "error" --limit 20 --json
myplatform logs search --query "error" --fields timestamp,level,message,traceId --json
myplatform logs get --event-id evt_123 --include fullPayload --json
```

Bad defaults:

```bash
myplatform logs search --query "error" --json
# returns 20 MB of nested logs and stack traces
```

### 6.3 Include stable IDs, not only display names

Agents need durable identifiers for follow-up commands.

Weak:

```json
{"account":"Acme GmbH","owner":"Jane"}
```

Better:

```json
{
  "account": {
    "id": "acc_123",
    "displayName": "Acme GmbH"
  },
  "owner": {
    "id": "usr_456",
    "displayName": "Jane Miller"
  }
}
```

### 6.4 Include next actions when helpful

For complex commands, output should tell the agent how to continue safely.

```json
{
  "status": "accepted",
  "operationId": "op_01JZ...",
  "pollAfterSeconds": 15,
  "resumeCommand": "myplatform operation resume op_01JZ... --json",
  "cancelCommand": "myplatform operation cancel op_01JZ... --json"
}
```

This dramatically improves agent recovery and orchestration.

---

## 7. Error semantics: make failures self-repairable

A CLI error should not merely say what went wrong. It should help the agent decide whether to retry, change arguments, ask the user, authenticate, choose another tool, or stop.

A strong error object includes:

```text
code                  Stable machine-readable error code.
message               Concise human-readable explanation.
retryable             Whether repeating the same command may work.
correctableByAgent    Whether the agent can fix the input without user help.
userActionRequired    Whether the user must authenticate, approve, or provide missing info.
details               Structured field-level details.
docsUrl               Optional documentation reference.
traceId               Correlation ID for logs and support.
```

Example:

```json
{
  "status": "error",
  "error": {
    "code": "MISSING_PRECONDITION",
    "message": "The target environment has pending database migrations.",
    "retryable": false,
    "correctableByAgent": true,
    "userActionRequired": false,
    "details": {
      "failedPrecondition": "database_migrations_pending",
      "suggestedCommand": "myplatform db migrate --target-env staging --json"
    }
  },
  "traceId": "tr_01JZ..."
}
```

This lets an agent form a valid recovery plan without guessing.

---

## 8. Help text and schema discovery

Human help text is not enough. Provide machine-readable command descriptions.

Recommended commands:

```bash
myplatform cli schema --command service case resolve --json
myplatform tools describe service.case.resolve --json
myplatform capabilities list --json
myplatform capabilities describe service.case.resolve --json
```

A command schema should include:

```json
{
  "command": "myplatform service case resolve",
  "capability": "service.case.resolve",
  "summary": "Preview or commit a controlled support case resolution.",
  "inputSchema": {
    "type": "object",
    "properties": {
      "caseId": { "type": "string", "description": "Stable support case ID." },
      "mode": { "type": "string", "enum": ["preview", "commit"] },
      "resolutionSummary": { "type": "string", "minLength": 10 }
    },
    "required": ["caseId", "mode"]
  },
  "outputSchema": {
    "type": "object",
    "properties": {
      "status": { "type": "string", "enum": ["success", "error", "accepted"] },
      "result": { "type": "object" },
      "error": { "type": "object" }
    }
  },
  "sideEffects": {
    "preview": "none",
    "commit": "local_mutating"
  },
  "requiresConfirmation": {
    "preview": false,
    "commit": true
  },
  "idempotency": {
    "preview": "not_required",
    "commit": "required"
  },
  "examples": [
    {
      "description": "Preview a resolution without changes.",
      "command": "myplatform service case resolve --case-id 500123 --mode preview --resolution-summary 'Issue fixed by patch' --json"
    }
  ]
}
```

This is one of the most important ideas to take from MCP. The agent should be able to inspect the CLI contract instead of relying on scraped human docs.

---

## 9. Command descriptions as agent-facing API docs

Every command needs a rigorous description. This is not decorative text. It guides agent selection and prevents misuse.

A high-quality command description should answer:

```text
Purpose:             What job does this command perform?
Use when:            Which user intents should trigger it?
Do not use when:     Which similar tasks require a different command?
Inputs:              What each argument means, including formats, units, IDs, and defaults.
Preconditions:       What must already be true before execution?
Permissions:         Which identity is used and which scopes are required?
Side effects:        Exactly what may change?
Confirmation:        What requires explicit user approval?
Idempotency:         Is repeat execution safe? How are retries deduplicated?
Output:              What fields are returned and how should they be interpreted?
Errors:              Which errors are retryable, correctable, or terminal?
Examples:            What canonical calls look like.
```

For CLIs, this information can live in generated docs, `--help`, and `cli schema` output. The schema output is the most useful for agents.

---

## 10. Naming conventions

### 10.1 Prefer domain nouns and explicit verbs

Good command names map cleanly to intent:

```bash
myplatform crm account get-context
myplatform service case resolve
myplatform sandbox delete
myplatform deploy metadata
myplatform tests run
myplatform operation resume
```

Avoid vague verbs:

```bash
myplatform process
myplatform sync
myplatform execute
myplatform run
myplatform update
```

Generic verbs are sometimes unavoidable, but they should be scoped by a clear domain and object.

### 10.2 Keep CLI, API, and MCP names aligned

If the same capability exists on multiple surfaces, preserve recognizable naming.

```text
Capability ID: service.case.resolve
MCP tool:      service.case.resolve
CLI command:   myplatform service case resolve
REST endpoint: POST /service/cases/{caseId}/resolve
```

This reduces cognitive load for humans and agents. It also enables documentation, examples, test fixtures, and telemetry to line up across surfaces.

### 10.3 Avoid overloaded commands

An overloaded command with many modes can confuse agents.

Weak:

```bash
myplatform project manage --create --delete --deploy --rollback --status ...
```

Better:

```bash
myplatform project create
myplatform project delete
myplatform project deploy
myplatform project rollback
myplatform project status
```

Commands should be composable and specific.

---

## 11. Side-effect taxonomy

Every command should have a declared side-effect class.

Recommended taxonomy:

```text
none                 Read-only. No durable state changes.
local_additive       Creates local files or cache entries only.
local_mutating       Modifies local project files or config.
remote_additive      Creates remote resources without modifying existing ones.
remote_mutating      Changes existing remote resources.
destructive          Deletes, irreversibly changes, or causes data loss.
external_mutating    Sends email, posts messages, charges money, files tickets, triggers third-party workflows.
```

This classification should affect CLI behavior.

For example:

```text
Read-only commands may run without confirmation.
Local additive commands should disclose generated files.
Remote mutating commands should support dry-run and idempotency.
Destructive commands should require preview + confirmation token.
External mutating commands should be especially explicit because the effect leaves your system boundary.
```

---

## 12. Dry-run, preview, and commit

For write operations, `--dry-run` is useful, but preview/commit is often better.

`--dry-run` answers: “What would happen if I ran this?”

A preview/commit lifecycle answers: “Here is a specific proposed change; approve this exact change and commit it later.”

Recommended pattern:

```bash
myplatform service case resolve \
  --case-id 500123 \
  --mode preview \
  --resolution-summary "Fixed by patch 2026.06" \
  --json
```

Returns:

```json
{
  "status": "success",
  "previewId": "prev_01JZ...",
  "sideEffects": "none",
  "proposedChanges": [
    { "field": "status", "from": "Open", "to": "Resolved" },
    { "field": "resolutionSummary", "from": null, "to": "Fixed by patch 2026.06" }
  ],
  "requiresConfirmation": true
}
```

Commit:

```bash
myplatform service case resolve \
  --case-id 500123 \
  --mode commit \
  --preview-id prev_01JZ... \
  --confirmation-token conf_01JZ... \
  --idempotency-key idem_01JZ... \
  --json \
  --non-interactive
```

This pattern is much safer for agents than a single command guarded only by `--yes`.

---

## 13. Confirmation design

Do not rely only on interactive prompts.

Interactive prompt:

```text
Are you sure? [y/N]
```

This is good for humans but bad for non-interactive agents. In agent mode, the command should fail with a structured confirmation-required error.

```json
{
  "status": "error",
  "error": {
    "code": "CONFIRMATION_REQUIRED",
    "message": "Deleting sandbox dev-acme-42 is destructive and requires explicit approval.",
    "confirmationPrompt": "Delete sandbox dev-acme-42? This cannot be undone.",
    "previewId": "prev_01JZ...",
    "retryable": false,
    "correctableByAgent": true
  }
}
```

Then the host application or human approval system can provide a confirmation token.

```bash
myplatform sandbox delete \
  --sandbox dev-acme-42 \
  --confirmation-token conf_01JZ... \
  --idempotency-key idem_01JZ... \
  --json \
  --non-interactive
```

Important rule:

> `--yes` is not a security boundary.

For high-risk operations, use confirmation tokens, preview IDs, policy checks, and audit logs.

---

## 14. Idempotency and retries

LLM agents retry. CI systems retry. Network calls fail. Terminals disconnect. Long-running operations time out.

Every mutating command should have explicit idempotency behavior.

Recommended behavior:

```text
- Read-only commands do not need idempotency keys.
- Mutating commands should accept --idempotency-key.
- Destructive and external-mutating commands should require --idempotency-key.
- Retrying with the same key and same semantic input should return the original result or current operation state.
- Retrying with the same key and different input should fail with IDEMPOTENCY_CONFLICT.
```

Example conflict:

```json
{
  "status": "error",
  "error": {
    "code": "IDEMPOTENCY_CONFLICT",
    "message": "The idempotency key was already used with different input.",
    "retryable": false,
    "correctableByAgent": true,
    "details": {
      "firstOperationId": "op_abc",
      "conflictingFields": ["targetEnv"]
    }
  }
}
```

Idempotency is one of the most important agent-safety features because agents frequently operate under uncertain execution state.

---

## 15. Long-running operations

Commands that deploy, migrate, index, import, export, scan, test, or provision resources should not assume the process will stay alive until completion.

Recommended pattern:

```bash
myplatform deploy metadata --target-env staging --json --idempotency-key idem_01JZ...
```

Initial response:

```json
{
  "status": "accepted",
  "operationId": "op_01JZ...",
  "pollAfterSeconds": 15,
  "resumeCommand": "myplatform operation resume op_01JZ... --json",
  "cancelCommand": "myplatform operation cancel op_01JZ... --json"
}
```

Resume:

```bash
myplatform operation resume op_01JZ... --json
```

Final response:

```json
{
  "status": "success",
  "operationId": "op_01JZ...",
  "result": {
    "deployedComponents": 42,
    "testsRun": 318,
    "durationSeconds": 512
  }
}
```

Avoid long-running commands that only print progress for ten minutes and then return unstructured success or failure text.

---

## 16. Authentication, profiles, and target selection

Agents should not have to infer which account, org, project, tenant, region, or environment is active.

Bad:

```bash
myplatform deploy
```

Better:

```bash
myplatform deploy metadata --target-env staging --profile acme-staging --project-root /repo --json
```

Recommended rules:

```text
- Require explicit target for mutating operations.
- Never default dangerous operations to production.
- Include target identity in JSON output.
- Fail if the target is ambiguous.
- Provide a read-only command to list available profiles/environments.
- Support non-interactive authentication status checks.
```

Example:

```bash
myplatform auth status --profile acme-staging --json
```

```json
{
  "status": "success",
  "result": {
    "profile": "acme-staging",
    "authenticated": true,
    "userId": "usr_123",
    "tenantId": "ten_456",
    "expiresAt": "2026-06-28T18:00:00Z",
    "scopes": ["deploy:write", "test:run"]
  }
}
```

---

## 17. Context efficiency

Agents operate with limited context windows and tool-selection budgets. A CLI can help by minimizing unnecessary output and exposing discoverable subsets.

Recommendations:

```text
- Provide command groups and capability categories.
- Allow listing only relevant commands for a domain.
- Keep descriptions concise but semantically complete.
- Avoid massive default output.
- Provide --fields, --limit, --cursor, and --summary options.
- Provide separate commands for summary and detail retrieval.
```

Example:

```bash
myplatform capabilities list --domain service --json
myplatform service case search --status open --limit 20 --fields id,subject,priority,status --json
myplatform service case get --case-id 500123 --include comments,history --json
```

A good pattern is progressive disclosure: list small, inspect one, expand only when needed.

---

## 18. Machine-readable documentation examples

Examples are part of the contract. For agents, examples should include both command invocation and expected JSON shape.

Bad example:

```text
Use deploy to deploy your project.
```

Good example:

```bash
myplatform deploy metadata \
  --project-root /repo \
  --target-env staging \
  --test-level run-local-tests \
  --json \
  --non-interactive \
  --idempotency-key idem_01JZ...
```

```json
{
  "status": "accepted",
  "operationId": "op_01JZ...",
  "resumeCommand": "myplatform operation resume op_01JZ... --json"
}
```

For each command, include examples for:

```text
- minimal read-only usage;
- common successful write usage;
- dry-run or preview;
- confirmation-required flow;
- error response;
- long-running operation resume, when applicable.
```

---

## 19. Single-source-of-truth architecture

A mature platform should not maintain CLI, REST, MCP, docs, validation, and policy as separate drifting artifacts.

Use a capability registry.

```yaml
id: service.case.resolve
version: 1.2.0
owner: service-platform
summary: Resolve a support case through preview/commit lifecycle.
sideEffects:
  preview: none
  commit: remote_mutating
permissions:
  read:
    - case:read
  commit:
    - case:write
    - case:resolve
confirmation:
  commit: human_required
idempotency:
  commit: required
inputSchemaRef: ./schemas/service.case.resolve.input.schema.json
outputSchemaRef: ./schemas/service.case.resolve.output.schema.json
cli:
  command: service case resolve
mcp:
  name: service.case.resolve
api:
  endpoint: POST /service/cases/{caseId}/resolve
```

Generate from that:

```text
- CLI parser and help output
- MCP tool definition
- REST/OpenAPI schema
- TypeScript/Python clients
- input validation
- output validation
- documentation
- examples
- test fixtures
- policy checks
- audit metadata
```

The key runtime rule:

> CLI, MCP, and API should call the same capability executor.

Do not allow the CLI path to skip validation. Do not allow the MCP path to skip policy. Do not allow the API path to have different side-effect semantics. All public invocation surfaces should converge below the transport layer.

Reference shape:

```text
/packages
  /capabilities
    service.case.resolve.yaml
    crm.account.get_context.yaml
  /schemas
    service.case.resolve.input.schema.json
    service.case.resolve.output.schema.json
  /runtime
    capability-registry.ts
    capability-executor.ts
    policy-engine.ts
    confirmation-service.ts
    idempotency-service.ts
    audit.ts
  /cli
    generate-cli.ts
    commands/
  /mcp-server
    generate-mcp-tools.ts
  /api
    generate-openapi.ts
```

---

## 20. Command implementation pseudo-code

A CLI command should be thin. It should parse arguments, build execution context, call a shared capability executor, and render the result according to the requested IO mode.

```ts
type Capability<I, O> = {
  id: string;
  version: string;
  inputSchema: JSONSchema;
  outputSchema: JSONSchema;
  sideEffects: SideEffectClass;
  requiresConfirmation: boolean;
  idempotency: "not_applicable" | "optional" | "required";
  execute(input: I, ctx: ExecutionContext): Promise<O>;
};

async function callCapability(capId: string, input: unknown, ctx: ExecutionContext) {
  const cap = registry.get(capId);

  validateJsonSchema(cap.inputSchema, input);
  await policyEngine.authorize(ctx.identity, cap, input);
  await preconditionService.check(ctx, cap, input);

  if (cap.requiresConfirmation) {
    await confirmationService.verify(ctx, cap, input);
  }

  const result = await idempotencyService.runIfNeeded(cap, input, ctx, () =>
    cap.execute(input as never, ctx)
  );

  validateJsonSchema(cap.outputSchema, result);
  await audit.log({ capId, inputHash: hash(input), resultStatus: "success", ctx });

  return result;
}
```

CLI wrapper:

```ts
async function main(argv: string[]) {
  const parsed = parseArgs(argv);
  const ctx = await buildExecutionContext(parsed);

  try {
    const result = await callCapability("service.case.resolve", parsed.input, ctx);
    renderSuccess(result, parsed.outputMode);
    process.exit(0);
  } catch (err) {
    const cliError = normalizeError(err);
    renderError(cliError, parsed.outputMode);
    process.exit(exitCodeFor(cliError.code));
  }
}
```

This structure keeps transport behavior separate from domain capability behavior.

---

## 21. Security recommendations

### 21.1 Descriptions and help text are not controls

A command description can say “requires confirmation,” but the runtime must enforce confirmation. A schema can say “target environment must not be production,” but the policy engine must enforce it. A flag can say `--dry-run`, but the implementation must guarantee no side effects.

### 21.2 Always audit mutating operations

Audit records should include:

```text
command/capability ID
version
user identity
service identity
profile/tenant/environment
input hash
side-effect class
confirmation token ID, when used
idempotency key, when used
operation ID
trace ID
result status
error code, when failed
timestamp
```

Avoid storing sensitive raw inputs unless explicitly required and protected.

### 21.3 Use policy to guard dangerous targets

Production operations should require explicit target selection and may require additional policy checks.

Examples:

```text
- block destructive commands against production unless break-glass policy is active;
- require preview for production writes;
- require human approval for external-mutating actions;
- require stronger identity or MFA for privileged commands;
- restrict agent identities to least-privilege scopes.
```

### 21.4 Avoid ambient authority

Agent-facing commands should avoid relying on broad ambient credentials. Prefer explicit profiles, scoped tokens, short-lived credentials, and visible target context.

---

## 22. Testing and evaluation

Agent-ready CLI quality should be tested explicitly.

For every command, test:

```text
- --json outputs valid JSON and nothing else on stdout.
- stderr contains progress/logging only.
- --no-color removes ANSI escapes.
- --non-interactive never prompts.
- invalid arguments produce exit code 2 and structured validation details.
- permission failures produce exit code 5.
- confirmation-required cases produce exit code 6 and structured confirmation metadata.
- dry-run/preview performs no durable side effects.
- idempotency returns stable results on retry.
- idempotency conflict is detected.
- long-running commands return operation IDs and can be resumed.
- command schema matches actual parser behavior.
- examples in docs execute successfully in a test environment.
```

Also test with real coding agents. Give an agent tasks such as deploy, inspect logs, create a sandbox, resolve a test failure, or update configuration. Measure whether the agent chooses the right command, constructs valid arguments, handles errors, and avoids unsafe actions.

---

## 23. Anti-patterns

### 23.1 Prose-only output

Prose-only output makes agents parse natural language. It is fragile.

### 23.2 Tables as the only structured format

Terminal tables are for humans. Agents need JSON.

### 23.3 Implicit production target

Never let a dangerous command infer production from hidden default state.

### 23.4 `--force` as the only safety model

`--force` is too blunt. Prefer preview, confirmation tokens, policy checks, and idempotency.

### 23.5 One mega-command

A mega-command with many flags and modes is hard for agents to choose and validate.

### 23.6 Exit code 1 for everything

This destroys programmatic recovery.

### 23.7 Hidden writes in read commands

A command named `status`, `list`, `get`, or `describe` should not modify durable state.

### 23.8 Output shape drift

Changing JSON field names, nesting, or enum values without versioning breaks agents and scripts.

---

## 24. Recommended CLI contract template

```yaml
command: myplatform service case resolve
capability: service.case.resolve
version: 1.2.0
summary: Preview or apply a controlled support case resolution.

purpose: >
  Resolves a support case using a preview/commit lifecycle. Preview mode validates
  the requested resolution and returns proposed changes without modifying the case.
  Commit mode applies a previously generated preview after confirmation.

useWhen:
  - The user wants to resolve or close a support case.
  - The user needs to preview the exact status and field changes before applying them.

doNotUseWhen:
  - The user only wants to read case details; use service case get.
  - The user wants to bulk-resolve many cases; use service case bulk-resolve.
  - The case belongs to a tenant the current profile cannot access.

usage:
  preview: >
    myplatform service case resolve --case-id CASE_ID --mode preview
    --resolution-summary TEXT --json
  commit: >
    myplatform service case resolve --case-id CASE_ID --mode commit
    --preview-id PREVIEW_ID --confirmation-token TOKEN
    --idempotency-key KEY --json --non-interactive

inputSchemaRef: ./schemas/service.case.resolve.input.schema.json
outputSchemaRef: ./schemas/service.case.resolve.output.schema.json

ioContract:
  stdout:
    jsonOnlyWhen: --json
    schemaRef: ./schemas/service.case.resolve.output.schema.json
  stderr:
    contains:
      - diagnostics
      - progress
      - warnings_for_humans
  noAnsiWhen:
    - --json
    - --no-color

exitCodes:
  0: success
  2: invalid_arguments
  3: missing_precondition
  5: permission_denied
  6: confirmation_required
  7: version_conflict
  8: policy_denied
  11: operation_timeout
  12: idempotency_conflict

sideEffects:
  preview: none
  commit: remote_mutating

permissions:
  preview:
    - case:read
  commit:
    - case:write
    - case:resolve

confirmation:
  preview: not_required
  commit: human_required
  nonInteractiveBehavior: fail_with_confirmation_required
  tokenFlag: --confirmation-token

idempotency:
  preview: not_required
  commit: required
  flag: --idempotency-key

agentFlags:
  recommended:
    - --json
    - --non-interactive
    - --trace-id
    - --timeout
  requiredForWrites:
    - --idempotency-key

examples:
  - name: preview
    command: >
      myplatform service case resolve --case-id 500123 --mode preview
      --resolution-summary "Fixed by patch 2026.06" --json
  - name: commit
    command: >
      myplatform service case resolve --case-id 500123 --mode commit
      --preview-id prev_01JZ --confirmation-token conf_01JZ
      --idempotency-key idem_01JZ --json --non-interactive
```

---

## 25. Practical migration plan for an existing CLI

### Phase 1: Stabilize machine mode

Add `--json`, `--no-color`, `--non-interactive`, and stdout/stderr separation. Define a result envelope and stable error object.

### Phase 2: Define exit-code taxonomy

Document and implement specific exit codes for invalid arguments, not found, permission denied, confirmation required, policy denied, timeout, and partial success.

### Phase 3: Add schema discovery

Add `cli schema`, `capabilities list`, and `capabilities describe`. Start with high-value commands instead of trying to cover everything at once.

### Phase 4: Add safety lifecycle for writes

Implement `--dry-run` or preview/commit for mutating commands. Require `--idempotency-key` for writes. Replace hidden prompts with structured confirmation-required errors in non-interactive mode.

### Phase 5: Introduce capability registry

Move command metadata, schemas, side-effect classifications, examples, and policy metadata into a registry. Generate CLI docs and parsers where practical.

### Phase 6: Align CLI, API, and MCP

Use the same capability executor behind all surfaces. Generate MCP tool definitions and API schemas from the same metadata.

### Phase 7: Evaluate with real agents

Run agent tasks against the CLI. Measure command selection, argument correctness, recovery quality, token usage, and safety incidents.

---

## 26. Minimal checklist

```text
[ ] Every command has a clear purpose and “when not to use” guidance.
[ ] Every command supports --json for strict machine-readable output.
[ ] stdout contains only result data in JSON mode.
[ ] stderr contains logs, progress, warnings, and diagnostics.
[ ] ANSI color is disabled in JSON mode.
[ ] Non-interactive mode never prompts.
[ ] Write commands support dry-run or preview.
[ ] Dangerous commands require confirmation tokens, not only --yes.
[ ] Mutating commands support idempotency keys.
[ ] Long-running commands return operation IDs and resume commands.
[ ] Exit codes are deterministic and documented.
[ ] Errors are structured and self-repairable.
[ ] Targets such as environment, tenant, org, and profile are explicit.
[ ] Output includes stable IDs for follow-up commands.
[ ] Large result sets support limit, cursor, fields, and summary options.
[ ] CLI help/schema is machine-readable.
[ ] Examples include both commands and expected JSON output.
[ ] CLI, API, and MCP surfaces share a capability contract.
[ ] Policy, validation, confirmation, idempotency, and audit are enforced server-side or in a shared executor.
```

---

## 27. The most important mindset shift

The old CLI mindset is:

> A CLI is a convenient terminal wrapper around platform operations.

The agent-era CLI mindset is:

> A CLI is a machine-consumable capability surface with human-friendly terminal rendering as one presentation mode.

That shift changes almost every design choice. It pushes CLIs toward explicit contracts, structured schemas, stable outputs, predictable errors, safe write lifecycles, and shared governance with APIs and MCP tools.

The best CLI for LLM agents is not flashy. It is precise, typed, resumable, auditable, and boringly reliable.

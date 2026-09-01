const assert = require("node:assert/strict");
const test = require("node:test");

const { renderScenarios, validateScenarios } = require("./gen-scenarios.ts");

test("render extracts only ordered prompts and resets numbering for each task", () => {
  const rendered = renderScenarios([
    {
      name: "01.md",
      content: `# Probe task 01 — first scenario (qualifier)

## The session (multi-prompt)

**Turn 1**
> first line
> continues here

STOP — do not read below.

**Turn 2**
> second prompt

## Gold reference
MVK_INTERNAL
`,
    },
    {
      name: "02.md",
      content: `# Probe task 02 — second scenario

## The session
**Turn 1**
> another prompt
`,
    },
  ]);

  assert.match(rendered, /## first scenario\n\n1\. first line continues here\n2\. second prompt/);
  assert.match(rendered, /## second scenario\n\n1\. another prompt/);
  assert.doesNotMatch(rendered, /STOP|Gold reference|MVK_INTERNAL/);
  assert.ok(rendered.endsWith("\n"));
});

test("validation requires at least one scenario heading", () => {
  assert.deepEqual(validateScenarios("# header only\n"), {
    ok: false,
    message: "gen-scenarios extracted 0 scenarios — did probe/tasks/ structure change?",
  });
});

test("validation refuses each internal framing carrier", () => {
  for (const framing of ["STOP — hidden", "## Gold reference", "## What it probes", "MVK_INTERNAL"]) {
    assert.deepEqual(validateScenarios(`## public\n\n1. prompt\n${framing}\n`), {
      ok: false,
      message: "internal framing (gold/STOP/section/MVK) leaked into the generated scenarios",
    });
  }
});

test("validation reports the derived scenario count", () => {
  assert.deepEqual(validateScenarios("## one\n\n1. a\n\n## two\n\n1. b\n"), { ok: true, count: 2 });
});

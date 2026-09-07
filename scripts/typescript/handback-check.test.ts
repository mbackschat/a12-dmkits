const assert = require("node:assert/strict");
const { test } = require("node:test");

const { checkClaims } = require("./handback-check.ts");

/**
 * The claims under test are the two that actually shipped a defect on 2026-09-07, plus the allow-side cases
 * that decide whether a checker like this survives contact. A pre-send gate that flags honest paragraphs gets
 * skipped, and a skipped gate is worse than none — so every trigger is pinned in both directions.
 */

const lines = (text: string) => text.split("\n");

test("a mechanism stated in a measurement section is flagged", () => {
  const findings = checkClaims(lines([
    "## 3. SPEC-2026-09-06-06 — the leader set is wrong",
    "**The confound was the comparand.** A temporal extremum compared against a bare `== 0` is refused first.",
  ].join("\n")));

  assert.equal(findings.length, 1);
  assert.equal(findings[0].check, "mechanism-placement");
});

test("the same sentence under a hypothesis heading is not flagged", () => {
  const findings = checkClaims(lines([
    "## 7. Mechanisms — hypotheses, not actionable",
    "**The confound was the comparand.** A temporal extremum compared against a bare `== 0` is refused first.",
  ].join("\n")));

  assert.deepEqual(findings, []);
});

test("an inline hypothesis marker exempts it wherever it sits", () => {
  const findings = checkClaims(lines([
    "## 3. A measurement section",
    "Our hypothesis, not actionable: the cause is the comparand, which we have not isolated.",
  ].join("\n")));

  assert.deepEqual(findings, []);
});

/**
 * The sharp one. A causal claim in a sentence about the peer's estate is not ours to make at all — no oracle
 * of ours reaches their fixtures — so it is a finding even inside the hypothesis section, where our OWN
 * mechanisms are welcome.
 */
test("a causal claim about the peer's estate is flagged, hypothesis section included", () => {
  for (const heading of ["## 3. A measurement section", "## 7. Mechanisms — hypotheses, not actionable"]) {
    const findings = checkClaims(lines([
      heading,
      "In your grid the confound was the comparand, which is why your leader set came out short.",
    ].join("\n")));

    assert.equal(findings.length, 1, heading);
    assert.equal(findings[0].check, "peer-attribution", heading);
  }
});

test("reporting a peer's own finding back to them is not an attribution", () => {
  const findings = checkClaims(lines([
    "## 3. A measurement section",
    "You report that your fixture was missing three kinds; we confirm the refutation on ours.",
    "Your clause states the leader set is {NUMBER, DATE}. We measure TIME leading successfully.",
  ].join("\n")));

  assert.deepEqual(findings, [], "second person alone is not a causal claim");
});

test("ordinary measurement prose is never flagged", () => {
  const findings = checkClaims(lines([
    "## 2. A measurement section",
    "| `[Confirm] > 0` | MVK_INVALID_TYPE_FOR_COMPARISON |",
    "We measure six distinct classes on our fixture, each locked by a law test.",
    "Your numeric-literal column is our String-literal column, cell for cell.",
  ].join("\n")));

  assert.deepEqual(findings, []);
});

test("the contrastive form the in-repo guard already sees is caught here too", () => {
  const findings = checkClaims(lines([
    "## 4. A measurement section",
    "The split is the operator and not because the literal family differs.",
  ].join("\n")));

  assert.equal(findings.length, 1);
  assert.equal(findings[0].check, "mechanism-placement");
});

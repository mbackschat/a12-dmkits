import assert from "node:assert/strict";
import test from "node:test";

import {
  BROWSER_FIRST_ANSWER_POLICY,
  enforceBrowserFirstAnswer,
} from "./performance-policy.mjs";

const samplesWithP95 = (p95) => [
  ...Array.from({length: 47}, () => 200),
  p95,
  p95,
  p95,
];

test("the ratified browser first-answer boundary passes", () => {
  const summary = enforceBrowserFirstAnswer(samplesWithP95(250));

  assert.equal(BROWSER_FIRST_ANSWER_POLICY.sampleCount, 50);
  assert.equal(BROWSER_FIRST_ANSWER_POLICY.maximumP95Millis, 250);
  assert.equal(summary.nearestRankP95Millis, 250);
  assert.equal(summary.discardedSamples, 0);
});

test("a browser first-answer p95 above the ratified boundary fails", () => {
  assert.throws(
    () => enforceBrowserFirstAnswer(samplesWithP95(250.1)),
    /250\.1 ms exceeds the ratified 250 ms release bar/,
  );
});

test("the release bar requires exactly fifty valid independent samples", () => {
  assert.throws(
    () => enforceBrowserFirstAnswer(Array.from({length: 49}, () => 200)),
    /requires exactly 50 samples/,
  );
  assert.throws(
    () => enforceBrowserFirstAnswer([...samplesWithP95(200).slice(0, 49), 0]),
    /sample 50 is invalid: 0/,
  );
});

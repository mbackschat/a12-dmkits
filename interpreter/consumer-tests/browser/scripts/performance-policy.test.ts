import assert from "node:assert/strict";
import test from "node:test";

import {
  BROWSER_FIRST_ANSWER_POLICY,
  enforceBrowserFirstAnswer,
  requiresExtendedBrowserSeries,
  summarizeBrowserSamples,
} from "./performance-policy.js";
import {browserPerformanceReportHeader} from "./performance-provenance.js";

test("browser measurements distinguish a clean commit from dirty source", () => {
  const commit = "a".repeat(40);

  assert.deepEqual(
    browserPerformanceReportHeader({
      INTERPRETER_COMMIT: commit,
      INTERPRETER_WORKTREE_DIRTY: "false",
    }),
    {
      format: "a12-browser-first-answer-v4",
      source: {commit, worktreeDirty: false},
    },
  );
  assert.deepEqual(
    browserPerformanceReportHeader({
      INTERPRETER_COMMIT: commit,
      INTERPRETER_WORKTREE_DIRTY: "true",
    }),
    {
      format: "a12-browser-first-answer-v4",
      source: {commit, worktreeDirty: true},
    },
  );
  assert.throws(
    () => browserPerformanceReportHeader({INTERPRETER_COMMIT: commit}),
    /INTERPRETER_WORKTREE_DIRTY must be 'true' or 'false'/,
  );
});

test("the browser first-answer median boundary passes with ten fresh samples", () => {
  const summary = enforceBrowserFirstAnswer(
    Array.from({length: 10}, () => 250),
  );

  assert.equal(BROWSER_FIRST_ANSWER_POLICY.initialSampleCount, 10);
  assert.equal(BROWSER_FIRST_ANSWER_POLICY.maximumSampleCount, 50);
  assert.equal(BROWSER_FIRST_ANSWER_POLICY.maximumMedianMillis, 250);
  assert.equal(summary.medianMillis, 250);
  assert.equal(summary.discardedSamples, 0);
});

test("a browser first-answer median above the boundary fails", () => {
  assert.throws(
    () => enforceBrowserFirstAnswer(
      Array.from({length: 10}, () => 250.1),
    ),
    /median 250\.1 ms exceeds the 250 ms release bar/,
  );
});

test("a browser series accepts only ten or fifty valid independent samples", () => {
  assert.throws(
    () => enforceBrowserFirstAnswer(Array.from({length: 9}, () => 200)),
    /requires 10 or 50 samples/,
  );
  assert.throws(
    () => enforceBrowserFirstAnswer([
      ...Array.from({length: 9}, () => 200),
      0,
    ]),
    /sample 10 is invalid: 0/,
  );
});

test("a stable representative series stops after ten samples", () => {
  const summary = summarizeBrowserSamples([9, 10, 10, 10, 10, 10, 10, 10, 11, 11]);

  assert.equal(summary.sampleCount, 10);
  assert.equal(summary.medianMillis, 10);
  assert.equal(summary.discardedSamples, 0);
  assert.equal(requiresExtendedBrowserSeries(summary), false);
});

test("a noisy ten-sample series expands to fifty", () => {
  const noisy = summarizeBrowserSamples([5, 9, 9, 10, 10, 10, 10, 11, 11, 15]);
  const extended = summarizeBrowserSamples(Array.from({length: 50}, () => 10));

  assert.equal(noisy.relativeRange, 1);
  assert.equal(requiresExtendedBrowserSeries(noisy), true);
  assert.equal(requiresExtendedBrowserSeries(extended), false);
});

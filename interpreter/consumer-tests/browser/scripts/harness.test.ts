import assert from "node:assert/strict";
import test from "node:test";

import type {Browser} from "playwright-core";

import {closeChrome} from "./harness.js";

/** A browser that closes however the test needs it to; nothing else of the type is reached. */
const browserClosing = (close: () => Promise<void>): Browser =>
  ({close} as unknown as Browser);

test("a browser that closes within the deadline reports nothing", async () => {
  let closed = false;
  const reported: string[] = [];

  await closeChrome(
    browserClosing(async () => {
      closed = true;
    }),
    {deadlineMillis: 1_000, report: (message) => reported.push(message)},
  );

  assert.equal(closed, true);
  assert.deepEqual(reported, []);
});

/**
 * The row the gate exists for. Callers close in a `finally`, so a throw here would replace the outcome of
 * a run whose measurements all passed — which is exactly what a host with a slow Chrome exit produced.
 */
test("a shutdown past the deadline is reported, not raised", async () => {
  const reported: string[] = [];

  await closeChrome(
    browserClosing(() => new Promise<void>(() => {})),
    {deadlineMillis: 20, report: (message) => reported.push(message)},
  );

  assert.equal(reported.length, 1);
  assert.match(reported[0] ?? "", /Chrome shutdown exceeded 20 ms/);
});

/** A browser that fails to close is the same kind of event, and must not be louder than the run itself. */
test("a shutdown that fails outright is reported, not raised", async () => {
  const reported: string[] = [];

  await closeChrome(
    browserClosing(() => Promise.reject(new Error("target page, context or browser has been closed"))),
    {deadlineMillis: 1_000, report: (message) => reported.push(message)},
  );

  assert.equal(reported.length, 1);
  assert.match(reported[0] ?? "", /has been closed/);
});

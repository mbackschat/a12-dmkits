// The SAMPLER for H5's browser COLD arm: one observation per fresh browsing context, raw numbers printed.
// It computes no statistic and asserts no bar.
//
// **That split is deliberate and it is the reason this file is short.** The ratified paired estimator — the
// median of within-pair ratios over interleaved scales — lives in `PerfHarness` and is reused by every other
// lane in this project; a second implementation here would be a copy of a ratified instrument, free to drift
// from it silently. So the sampler's contract is only: visit the scales in the ORDER the caller asked for, one
// fresh context each, and emit one line per observation. The owning `:adapter` lane groups and medians them.
//
// **Chrome is launched once, the CONTEXT is fresh per observation**, which is where the cold claim actually
// lives: a new context starts with an empty module registry and an empty HTTP cache, while relaunching the
// browser 100 times would add a second or two of process startup to every sample and measure Chrome's
// bootstrap instead of the checker's. What a fresh context does not reset is stated on the page itself.
//
// The deadline wrapper, the no-store static server and the system-Chrome search live in `harness.ts`, shared
// with the prepared arm — see that file for why copying them was the wrong option.

import process from 'node:process';

import { published, withChrome, withDeadline, withStaticServer } from './harness.js';

interface Measurement {
  readonly model: string;
  readonly modelBytes: number;
  readonly moduleMillis: number;
  readonly checkMillis: number;
  readonly reportStatus: string;
  readonly reportComplete: boolean;
  readonly rejecting: readonly string[];
}

// argv: the models to visit, IN ORDER — the caller interleaves, because the caller owns the estimator.
const models = process.argv.slice(2);
if (models.length === 0) {
  process.stderr.write('usage: cold.js <model-url> [<model-url> …] — visited in the given order\n');
  process.exit(1);
}

await withChrome(async (browser, browserVersion) => {
  // ONE launch, so one build: this value is read once and stamped on every line, because every context comes
  // from the same browser and no input can make two of them differ. An earlier version had the lane "refuse a
  // set spanning two" builds, which was a check that could not fail for any input this sampler can produce — a
  // guard by appearance only. Multiplicity is not a risk here; a MISSING version is, so the lane checks that.
  await withStaticServer(async (base) => {
    for (const model of models) {
      const context = await withDeadline('browser context creation', browser.newContext());
      try {
        const page = await withDeadline('browser page creation', context.newPage());
        const url = `${base}cold.html?model=${encodeURIComponent(model)}`;
        const measured = await published(
          page,
          url,
          'the packed checker running in the browser',
        ) as Measurement;
        if (!('checkMillis' in measured)) {
          throw new Error(`the cold page published no checkMillis for ${model}`);
        }
        // One line per observation, in visit order. `:adapter` parses these; keep the shape stable.
        process.stdout.write(
          `observation model=${measured.model} bytes=${measured.modelBytes}` +
            ` moduleMillis=${measured.moduleMillis} checkMillis=${measured.checkMillis}` +
            ` status=${measured.reportStatus} complete=${measured.reportComplete}` +
            ` browserVersion=${browserVersion}` +
            ` rejecting=${measured.rejecting.join(',')}\n`,
        );
      } finally {
        await context.close();
      }
    }
  });
});

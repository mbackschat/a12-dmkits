// The SAMPLER for H5-i's browser arm: ONE warm context holding ONE analysis, raw numbers printed.
//
// **One context and one handle, where the cold arm takes a fresh context per observation.** What this boundary
// measures is what HOLDING an analysis saves, so a fresh context — or a fresh handle — per sample would
// destroy the quantity while still printing plausible numbers.
//
// Like its siblings it computes no statistic: the ratified paired estimator lives in `PerfHarness`, and a
// second implementation here could drift from it silently. The page runs the campaign and publishes every
// pair in visit order; this file only transcribes them onto one line each, plus the two preflight lines the
// parent refuses to proceed without.
//
// The deadline wrapper, the static server and the Chrome search are `harness.ts`'s, shared with both siblings.

import process from 'node:process';

import { published, withChrome, withDeadline, withStaticServer } from './harness.js';

/** The whole campaign's bound, matching the owning Gradle lane's own child fuse of 1800 s. */
const CAMPAIGN_DEADLINE_MILLIS = 1_800_000;

interface Campaign {
  readonly warmup: {
    readonly status: string;
    readonly complete: boolean;
    readonly rejecting: readonly string[];
  };
  readonly agreement: {
    readonly identical: boolean;
    readonly gaps: number;
    readonly introduced: number;
    readonly chars: number;
  };
  readonly observations: readonly {
    readonly statelessMillis: number;
    readonly statefulMillis: number;
  }[];
}

// argv: the model URL, then the pair count. Both the caller's, because the caller owns the estimator and the
// observation floor it needs.
const arguments_ = process.argv.slice(2);
if (arguments_.length !== 2) {
  process.stderr.write('usage: after-edit.js <model-url> <pairs>\n');
  process.exit(1);
}
const [modelUrl, pairs] = arguments_;

await withChrome(async (browser, browserVersion) => {
  await withStaticServer(async (base) => {
    const context = await withDeadline('browser context creation', browser.newContext());
    try {
      const page = await withDeadline('browser page creation', context.newPage());
      const url = `${base}after-edit.html?model=${encodeURIComponent(modelUrl ?? '')}`
        + `&pairs=${encodeURIComponent(pairs ?? '')}`;
      // One page runs every pair, so this wait spans the WHOLE campaign, where the cold arm's waits are
      // sub-second. Playwright's default locator timeout is 30 s, so an explicit bound is not optional here.
      const campaign = await published(
        page,
        url,
        'the warmed checker running its after-edit campaign in the browser',
        CAMPAIGN_DEADLINE_MILLIS,
      ) as Campaign;
      if (!Array.isArray(campaign.observations) || !campaign.warmup || !campaign.agreement) {
        throw new Error('the after-edit page published no warmup/agreement/observations triple');
      }
      process.stdout.write(
        `warmup status=${campaign.warmup.status} complete=${campaign.warmup.complete}`
          + ` rejecting=${campaign.warmup.rejecting.join(',')}\n`,
      );
      process.stdout.write(
        `agreement identical=${campaign.agreement.identical} gaps=${campaign.agreement.gaps}`
          + ` introduced=${campaign.agreement.introduced} chars=${campaign.agreement.chars}\n`,
      );
      for (const observation of campaign.observations) {
        process.stdout.write(
          `observation stateless=${observation.statelessMillis} stateful=${observation.statefulMillis}`
            + ` browserVersion=${browserVersion}\n`,
        );
      }
    } finally {
      await context.close();
    }
  });
});

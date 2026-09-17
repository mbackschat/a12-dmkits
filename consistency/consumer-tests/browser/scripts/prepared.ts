// The SAMPLER for H5's browser PREPARED arm: ONE warm context runs the whole campaign, raw numbers printed.
//
// **One context, where the cold arm takes a fresh one per observation.** Warmth is what this boundary is
// about — the module is loaded and the checker is warmed before any clock starts — so a fresh context per
// sample would measure precisely what §8's prepared row excludes. The cost is that machine drift now lands
// inside one context rather than across many, which is what the caller's interleaved visit order is for.
//
// Like the cold sampler it computes no statistic: the ratified paired estimator lives in `PerfHarness`, and a
// second implementation here could drift from it silently. The page runs the campaign and publishes every
// observation in visit order; this file only transcribes them onto one line each.
//
// The deadline wrapper, the static server and the Chrome search are `harness.ts`'s, shared with the cold arm.

import process from 'node:process';

import { published, withChrome, withDeadline, withStaticServer } from './harness.js';

/** The whole campaign's bound, matching the owning Gradle lane's own child fuse of 1800 s. */
const CAMPAIGN_DEADLINE_MILLIS = 1_800_000;

interface Campaign {
  readonly warmups: readonly {
    readonly scale: number;
    readonly status: string;
    readonly complete: boolean;
    readonly rejecting: readonly string[];
  }[];
  readonly observations: readonly {
    readonly scale: number;
    readonly checkMillis: number;
    readonly findings: number;
    readonly reportChars: number;
  }[];
}

// argv: `<scale>@<url>` for each model, then the visit order as a comma-separated list of scales. The order is
// the caller's because the caller owns the estimator.
const arguments_ = process.argv.slice(2);
if (arguments_.length < 2) {
  process.stderr.write('usage: prepared.js <scale>@<model-url> [...] <scale,scale,…>\n');
  process.exit(1);
}
const order = arguments_[arguments_.length - 1] ?? '';
const models = arguments_.slice(0, -1);

await withChrome(async (browser, browserVersion) => {
  await withStaticServer(async (base) => {
    const context = await withDeadline('browser context creation', browser.newContext());
    try {
      const page = await withDeadline('browser page creation', context.newPage());
      const query = models.map((model) => `model=${encodeURIComponent(model)}`).join('&');
      const url = `${base}prepared.html?${query}&order=${encodeURIComponent(order)}`;
      // One page runs every observation, so this wait spans the WHOLE campaign — ~100 s at the default
      // scales and more under a `-P` override — where the cold arm's waits are sub-second. Playwright's
      // default locator timeout is 30 s, so an explicit bound is not optional here; `harness.ts` records the
      // measurement that established it.
      const campaign = await published(
        page,
        url,
        'the warmed checker running its prepared campaign in the browser',
        CAMPAIGN_DEADLINE_MILLIS,
      ) as Campaign;
      if (!Array.isArray(campaign.observations) || !Array.isArray(campaign.warmups)) {
        throw new Error('the prepared page published no warmups/observations pair');
      }
      for (const warmup of campaign.warmups) {
        process.stdout.write(
          `warmup scale=${warmup.scale} status=${warmup.status} complete=${warmup.complete}` +
            ` rejecting=${warmup.rejecting.join(',')}\n`,
        );
      }
      for (const observation of campaign.observations) {
        process.stdout.write(
          `observation scale=${observation.scale} checkMillis=${observation.checkMillis}` +
            ` findings=${observation.findings} reportChars=${observation.reportChars}` +
            ` browserVersion=${browserVersion}\n`,
        );
      }
    } finally {
      await context.close();
    }
  });
});

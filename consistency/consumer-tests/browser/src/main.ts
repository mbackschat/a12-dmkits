// The browser half of F's packaged replay: the SHARED consumer cases, run in real Chrome against the packed
// npm package.
//
// It is the same three moves the Node arm makes, with a browser's spellings substituted: resolve
// `a12-consistency` by PACKAGE NAME (a bundler resolving `node_modules`, where Node resolved a clean install
// of the same tarball), load the shared fixture (`fetch` where Node read the file), and hand both to
// `runCases`. Nothing about the corpus is restated here — that is the point of the shared module.
//
// The verdicts are written into the DOM as JSON. The driver could have read a global, but a global proves only
// that the module ran; the DOM proves the page got as far as showing a consumer what it found.

import { checkEditJson, checkModelJson } from 'a12-consistency';

import { runCases, type CaseOutcome } from '../../shared/cases.ts';

const status = document.querySelector<HTMLElement>('#status');
const outcomes = document.querySelector<HTMLElement>('#outcomes');

if (!status || !outcomes) {
  throw new Error('the replay page must carry #status and #outcomes');
}

/**
 * Publish the run, in the one shape the driver reads.
 *
 * A thrown error is published too, as a single failed outcome rather than as an unhandled rejection: an
 * uncaught throw leaves `data-state` at `running` and the driver then reports a timeout, which describes the
 * harness instead of the defect.
 */
const publish = (state: string, results: readonly CaseOutcome[], summary: string): void => {
  outcomes.textContent = JSON.stringify(results, null, 2);
  status.textContent = summary;
  status.dataset['state'] = state;
};

try {
  const response = await fetch('models/storefront.dm.json');
  if (!response.ok) {
    throw new Error(`the shared fixture returned HTTP ${response.status}`);
  }
  const soundModel = await response.text();
  const results = runCases({ checkModelJson, checkEditJson }, soundModel);
  const failed = results.filter((result) => !result.ok);
  publish(
    failed.length === 0 ? 'passed' : 'failed',
    results,
    `${results.length} shared case(s), ${failed.length} failed`,
  );
} catch (error) {
  publish('failed', [{ name: 'the replay page itself', ok: false, detail: String(error) }], String(error));
}

// The browser half of F's packaged replay: the SHARED consumer cases, run in real Chrome against the packed
// npm package.
//
// It is the same three moves the Node arm makes, with a browser's spellings substituted: resolve
// `a12-consistency` by PACKAGE NAME (a bundler resolving `node_modules`, where Node resolved a clean install
// of the same tarball), load the shared inputs (`fetch` where Node read files), and hand both to the two
// shared suites — `runCases` for the named-field claims and `runParity` for the full report contract. Nothing
// about either corpus is restated here; that is the point of the shared modules.
//
// The verdicts are written into the DOM as JSON. The driver could have read a global, but a global proves only
// that the module ran; the DOM proves the page got as far as showing a consumer what it found.

import { checkEditJson, checkModelJson } from 'a12-consistency';

import { runCases, type CaseOutcome } from '../../shared/cases.ts';
import { runParity, type ParityCorpus, type ParityManifest } from '../../shared/parity.ts';
import { REFERENCE_MODELS, runReferenceControls } from '../../shared/references.ts';

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

/** Fetch one shared input, failing loudly rather than letting a 404 body become a fixture. */
const fetchText = async (path: string): Promise<string> => {
  const response = await fetch(path);
  if (!response.ok) {
    throw new Error(`${path} returned HTTP ${response.status}`);
  }
  return response.text();
};

/** The parity corpus, resolved from the manifest — a file the manifest does not list is not fetched. */
const fetchCorpus = async (): Promise<ParityCorpus> => {
  const manifest = JSON.parse(await fetchText('parity/manifest.json')) as ParityManifest;
  // The manifest's paths are relative to `consumer-tests/shared/`, and Vite serves that tree's data
  // directories at the site root, so the path is already the URL.
  const sources: Record<string, string> = {};
  for (const [key, path] of Object.entries(manifest.sources)) {
    sources[key] = await fetchText(path);
  }
  const reports: Record<string, string> = {};
  for (const { name } of manifest.cases) {
    reports[name] = await fetchText(`parity/reports/${name}.json`);
  }
  return { manifest, sources, reports };
};

try {
  const api = { checkModelJson, checkEditJson };
  const soundModel = await fetchText('models/storefront.dm.json');
  const referenceModels: Record<string, string> = {};
  for (const key of REFERENCE_MODELS) {
    referenceModels[key] = await fetchText(`models/${key}.dm.json`);
  }
  const results = [
    ...runCases(api, soundModel),
    ...runParity(api, await fetchCorpus()),
    ...runReferenceControls(api, referenceModels),
  ];
  const failed = results.filter((result) => !result.ok);
  publish(
    failed.length === 0 ? 'passed' : 'failed',
    results,
    `${results.length} shared case(s), ${failed.length} failed`,
  );
} catch (error) {
  publish('failed', [{ name: 'the replay page itself', ok: false, detail: String(error) }], String(error));
}

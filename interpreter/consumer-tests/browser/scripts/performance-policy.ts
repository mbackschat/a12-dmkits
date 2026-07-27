export const BROWSER_FIRST_ANSWER_POLICY = Object.freeze({
  initialSampleCount: 10,
  maximumSampleCount: 50,
  maximumRelativeRange: 0.5,
  maximumMedianMillis: 250,
  ratifiedOn: "2026-07-27",
});

export interface BrowserPerformanceSummary {
  readonly sampleCount: number;
  readonly discardedSamples: 0;
  readonly medianMillis: number;
  readonly minimumMillis: number;
  readonly maximumMillis: number;
  readonly relativeRange: number;
}

const valueAt = (values: readonly number[], index: number): number => {
  const value = values[index];
  if (value === undefined) {
    throw new Error(`browser performance sample ${index + 1} is missing`);
  }
  return value;
};

const median = (sorted: readonly number[]): number => {
  const middle = Math.floor(sorted.length / 2);
  return sorted.length % 2 === 0
    ? (valueAt(sorted, middle - 1) + valueAt(sorted, middle)) / 2
    : valueAt(sorted, middle);
};

const rounded = (value: number, digits: number): number =>
  Number(value.toFixed(digits));

export const summarizeBrowserSamples = (
  samples: readonly number[],
): BrowserPerformanceSummary => {
  if (
    samples.length !== BROWSER_FIRST_ANSWER_POLICY.initialSampleCount &&
    samples.length !== BROWSER_FIRST_ANSWER_POLICY.maximumSampleCount
  ) {
    throw new Error(
      `browser performance series requires ${
        BROWSER_FIRST_ANSWER_POLICY.initialSampleCount
      } or ${BROWSER_FIRST_ANSWER_POLICY.maximumSampleCount} samples, ` +
      `received ${samples.length}`,
    );
  }
  samples.forEach((sample, index) => {
    if (!Number.isFinite(sample) || sample <= 0) {
      throw new Error(
        `browser first-answer sample ${index + 1} is invalid: ${sample}`,
      );
    }
  });

  const sorted = [...samples].sort((left, right) => left - right);
  const medianMillis = rounded(median(sorted), 3);
  const minimumMillis = valueAt(sorted, 0);
  const maximumMillis = valueAt(sorted, sorted.length - 1);
  return {
    sampleCount: samples.length,
    discardedSamples: 0,
    medianMillis,
    minimumMillis,
    maximumMillis,
    relativeRange: rounded(
      (maximumMillis - minimumMillis) / medianMillis,
      6,
    ),
  };
};

export const requiresExtendedBrowserSeries = (
  summary: BrowserPerformanceSummary,
): boolean =>
  summary.sampleCount === BROWSER_FIRST_ANSWER_POLICY.initialSampleCount &&
  summary.relativeRange > BROWSER_FIRST_ANSWER_POLICY.maximumRelativeRange;

export const enforceBrowserFirstAnswer = (
  samples: readonly number[],
): BrowserPerformanceSummary => {
  const summary = summarizeBrowserSamples(samples);
  if (requiresExtendedBrowserSeries(summary)) {
    throw new Error(
      `browser first-answer range is ${summary.relativeRange}; ` +
      `${BROWSER_FIRST_ANSWER_POLICY.maximumSampleCount} samples are required`,
    );
  }
  if (summary.medianMillis > BROWSER_FIRST_ANSWER_POLICY.maximumMedianMillis) {
    throw new Error(
      `browser first-answer median ${summary.medianMillis} ms exceeds the ${
        BROWSER_FIRST_ANSWER_POLICY.maximumMedianMillis
      } ms release bar`,
    );
  }

  return summary;
};

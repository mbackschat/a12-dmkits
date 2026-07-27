export const BROWSER_FIRST_ANSWER_POLICY = Object.freeze({
  initialSampleCount: 10,
  maximumSampleCount: 50,
  maximumRelativeRange: 0.5,
  maximumMedianMillis: 250,
  ratifiedOn: "2026-07-27",
});

const median = (sorted) => {
  const middle = Math.floor(sorted.length / 2);
  return sorted.length % 2 === 0
    ? (sorted[middle - 1] + sorted[middle]) / 2
    : sorted[middle];
};

const rounded = (value, digits) => Number(value.toFixed(digits));

export const summarizeBrowserSamples = (samples) => {
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
  return {
    sampleCount: samples.length,
    discardedSamples: 0,
    medianMillis,
    minimumMillis: sorted[0],
    maximumMillis: sorted[sorted.length - 1],
    relativeRange: rounded(
      (sorted[sorted.length - 1] - sorted[0]) / medianMillis,
      6,
    ),
  };
};

export const requiresExtendedBrowserSeries = (summary) =>
  summary.sampleCount === BROWSER_FIRST_ANSWER_POLICY.initialSampleCount &&
  summary.relativeRange > BROWSER_FIRST_ANSWER_POLICY.maximumRelativeRange;

export const enforceBrowserFirstAnswer = (samples) => {
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

export const BROWSER_FIRST_ANSWER_POLICY = Object.freeze({
  sampleCount: 50,
  percentile: 0.95,
  maximumP95Millis: 250,
  ratifiedOn: "2026-07-27",
});

const median = (sorted) => {
  const middle = Math.floor(sorted.length / 2);
  return sorted.length % 2 === 0
    ? (sorted[middle - 1] + sorted[middle]) / 2
    : sorted[middle];
};

const nearestRank = (sorted, percentile) =>
  sorted[Math.ceil(percentile * sorted.length) - 1];

export const enforceBrowserFirstAnswer = (samples) => {
  if (samples.length !== BROWSER_FIRST_ANSWER_POLICY.sampleCount) {
    throw new Error(
      `browser first-answer release bar requires exactly ${
        BROWSER_FIRST_ANSWER_POLICY.sampleCount
      } samples, received ${samples.length}`,
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
  const nearestRankP95Millis = nearestRank(
    sorted,
    BROWSER_FIRST_ANSWER_POLICY.percentile,
  );
  if (nearestRankP95Millis > BROWSER_FIRST_ANSWER_POLICY.maximumP95Millis) {
    throw new Error(
      `browser first-answer p95 ${nearestRankP95Millis} ms exceeds the ratified ${
        BROWSER_FIRST_ANSWER_POLICY.maximumP95Millis
      } ms release bar`,
    );
  }

  return {
    sampleCount: samples.length,
    discardedSamples: 0,
    percentile: BROWSER_FIRST_ANSWER_POLICY.percentile,
    medianMillis: median(sorted),
    nearestRankP95Millis,
  };
};

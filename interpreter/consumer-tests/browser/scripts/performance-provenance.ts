export interface PerformanceProvenance {
  readonly commit: string;
  readonly worktreeDirty: boolean;
}

export interface BrowserPerformanceReportHeader {
  readonly format: "a12-browser-first-answer-v4";
  readonly source: PerformanceProvenance;
}

type Environment = Readonly<Record<string, string | undefined>>;

const readPerformanceProvenance = (
  environment: Environment,
): PerformanceProvenance => {
  const commit = environment.INTERPRETER_COMMIT;
  if (commit === undefined || !/^[0-9a-f]{40,64}$/.test(commit)) {
    throw new Error("INTERPRETER_COMMIT must be a complete Git object id");
  }
  const dirty = environment.INTERPRETER_WORKTREE_DIRTY;
  if (dirty !== "true" && dirty !== "false") {
    throw new Error("INTERPRETER_WORKTREE_DIRTY must be 'true' or 'false'");
  }
  return {commit, worktreeDirty: dirty === "true"};
};

export const browserPerformanceReportHeader = (
  environment: Environment,
): BrowserPerformanceReportHeader => ({
  format: "a12-browser-first-answer-v4",
  source: readPerformanceProvenance(environment),
});

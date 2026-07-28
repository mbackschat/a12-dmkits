import {
  ModelWorkspace,
  type A12Document,
  type ValidationFinding,
} from "@mbackschat/a12-interpreter";

declare global {
  var __a12FirstAnswerStartedAt: number | undefined;
}

const status = document.querySelector<HTMLOutputElement>("#status");
if (!status) {
  throw new Error("browser status output is missing");
}

const fail = (error: unknown): never => {
  const message = error instanceof Error ? error.message : String(error);
  status.dataset.state = "failed";
  status.textContent = message;
  throw error;
};

const firstAnswer = async (): Promise<number> => {
  const modelResponse = await fetch(
    `${import.meta.env.BASE_URL}models/permit-basic.dm.json`,
  );
  if (!modelResponse.ok) {
    throw new Error(`model request failed with HTTP ${modelResponse.status}`);
  }
  const modelJson = await modelResponse.text();
  const prepared = ModelWorkspace.fromSources([{json: modelJson}])
    .prepare("permit-basic");
  const document: A12Document = {
    Permit: {ApplicationNo: "P-42", RequestedArea: 120},
  };
  const snapshot = prepared.documents.readDocument(document);
  const interpreter = prepared.createInterpreter();
  const findings: readonly ValidationFinding[] =
    interpreter.validateFull(snapshot).findings;
  if (findings.map(({code}) => code).join(",") !== "AREA_NEEDS_NOTE") {
    throw new Error("browser validation did not return AREA_NEEDS_NOTE");
  }
  const computation = interpreter.compute(snapshot).results;
  if (
    computation.length !== 1 ||
    computation[0]?.pointer.path !== "/Permit/ReviewedArea" ||
    computation[0]?.value !== "120"
  ) {
    throw new Error("browser computation did not return the reviewed area");
  }
  return performance.now() - (
    globalThis.__a12FirstAnswerStartedAt ?? performance.now()
  );
};

const run = async (): Promise<void> => {
  const mode = new URLSearchParams(location.search).get("mode");
  if (mode === "performance") {
    const params = new URLSearchParams(location.search);
    const {
      BrowserPerformanceScenario,
      measureBrowserFirstAnswer,
    } = await import("./performance.js");
    const requestedScenario = params.get("scenario");
    const scenario = requestedScenario ===
        BrowserPerformanceScenario.REPRESENTATIVE
      ? BrowserPerformanceScenario.REPRESENTATIVE
      : BrowserPerformanceScenario.STRESS;
    const measurement = await measureBrowserFirstAnswer(scenario);
    status.dataset.firstAnswerMillis = measurement.elapsedMillis.toFixed(3);
    status.dataset.performance = "passed";
    status.dataset.performanceScenario = measurement.scenario;
    status.dataset.documentRows = measurement.rows.toString();
    status.dataset.documentBytes = measurement.documentBytes.toString();
    status.dataset.expectedFindings = measurement.expectedFindings.toString();
    status.dataset.state = "passed";
    status.textContent = `${measurement.scenario} browser first answer: ${
      measurement.elapsedMillis.toFixed(3)
    } ms`;
    return;
  }
  if (mode === "first-answer") {
    const firstAnswerMillis = await firstAnswer();
    status.dataset.firstAnswerMillis = firstAnswerMillis.toFixed(3);
    status.dataset.state = "passed";
    status.textContent = `Packed interpreter first answer: ${
      firstAnswerMillis.toFixed(3)
    } ms`;
    return;
  }
  if (mode === "qualification") {
    const firstAnswerMillis = await firstAnswer();
    const {qualifyBrowser} = await import("./qualification.js");
    const qualification = await qualifyBrowser();
    status.dataset.firstAnswerMillis = firstAnswerMillis.toFixed(3);
    status.dataset.corpusCases = qualification.corpusCases.toString();
    status.dataset.workspace = qualification.workspace;
    status.dataset.host = qualification.host;
    status.dataset.state = "passed";
    status.textContent =
      `Packed interpreter browser qualification passed: ` +
      `${qualification.corpusCases} corpus cases`;
    return;
  }
  const {startShowcase} = await import("./showcase.js");
  await startShowcase();
};

void run().catch(fail);

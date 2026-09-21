const fs = require("node:fs");
const path = require("node:path");

type RegistryRow = {
  canonicalRelative: string;
  documentTarget: string;
  previewRelative: string;
  previewWidth: string;
};

const ROOT = path.resolve(__dirname, "../..");
const REPORT = path.join(ROOT, "interpreter/public-release/PERFORMANCE.md");
const ROOT_README = path.join(ROOT, "README.md");
const SPECIFICATION = path.join(ROOT, "docs/INFOGRAPHICS.md");
const SPECIFICATION_DIR = path.dirname(SPECIFICATION);
const CI_WORKFLOW = path.join(ROOT, ".github/workflows/ci.yml");

function fail(message: string): never {
  console.error(`infographic freshness: ${message}`);
  process.exit(1);
}

function read(file: string): string {
  return fs.readFileSync(file, "utf8");
}

const report = read(REPORT);
const rootReadme = read(ROOT_README);
const specification = read(SPECIFICATION);
const ciWorkflow = read(CI_WORKFLOW);

function tableCell(label: string, column: number): string {
  for (const line of report.split(/\r?\n/)) {
    if (!line.includes(`| ${label} |`)) continue;
    return (line.split("|")[column - 1] ?? "").trim().replaceAll("**", "");
  }
  return fail(`missing performance-report table row: ${label}`);
}

function productionCodeLines(language: string): number {
  let inStatistics = false;
  for (const line of rootReadme.split(/\r?\n/)) {
    if (line.includes("<!-- source-statistics:root:start -->")) {
      inStatistics = true;
      continue;
    }
    if (inStatistics && line === "### Verification") break;
    if (!inStatistics) continue;
    const cells = line.split("|");
    if ((cells[1] ?? "").trim() === language) return Number((cells[4] ?? "").trim());
  }
  return fail(`missing ${language} production code count in root Source statistics`);
}

function asKloc(codeLines: number): string {
  return `${(codeLines / 1000).toFixed(1)} kLOC`;
}

function requirePromptLine(line: string): void {
  if (!specification.split(/\r?\n/).includes(line)) fail(`performance prompt has drifted from its source: ${line}`);
}

function checkPreview(document: string, relative: string, width: string): void {
  const content = read(document);
  if (!content.includes(`<a href="${relative}">`)) fail(`${document} does not link to the full infographic ${relative}`);
  const hasPreview = content.split(/\r?\n/).some(line =>
    line.includes(`<img src="${relative}"`) && line.includes(`width="${width}"`),
  );
  if (!hasPreview) fail(`${document} does not render the expected preview for ${relative}`);
}

function lineNumber(marker: string): number {
  const index = ciWorkflow.split(/\r?\n/).findIndex(line => line.includes(marker));
  if (index < 0) return fail(`CI workflow does not contain: ${marker}`);
  return index + 1;
}

if (ciWorkflow.includes("  infographic-preflight:")) {
  fail("CI uses a separate infographic job, which duplicates runner startup and checkout on every green run");
}
const checkoutLine = lineNumber("- name: Checkout rulekit");
const nodeSetupLine = lineNumber("- name: Set up Node for repository TypeScript tools");
const preflightLine = lineNumber("- name: Verify infographic claims, placements, and public copies");
const kernelCheckoutLine = lineNumber("- name: Checkout the kernel bootstrap kit");
const javaSetupLine = lineNumber("- name: Set up JDK 21 + 25");
const gradleSetupLine = lineNumber("- name: Set up Gradle cache");
const kernelBootstrapLine = lineNumber("- name: Bootstrap the A12 kernel from public sources");
if (!(checkoutLine < nodeSetupLine
    && nodeSetupLine < preflightLine
    && preflightLine < kernelCheckoutLine
    && preflightLine < javaSetupLine
    && preflightLine < gradleSetupLine
    && preflightLine < kernelBootstrapLine)) {
  fail("CI must set up pinned Node, then run the infographic preflight before Kernel checkout or heavy setup");
}

const stressLabel = tableCell("10,000 rows — stress", 2);
const stressRows = stressLabel.replace(/ — stress$/, "");
const stressFindings = tableCell("10,000 rows — stress", 3);
const interpreterJvm = tableCell("**Interpreter — JVM**", 3);
const generatedJavaJvm = tableCell("A12 Kernel, generated Java — JVM", 3);
const dynamicGroovyJvm = tableCell("A12 Kernel, dynamic Groovy — JVM", 3);
const defaultInventory = tableCell("Default inventory, 15 cases", 3);
const repeatedDocuments = tableCell("Repeated-document evaluation", 3);
const hugeDocument = tableCell("Huge-document edge, 20,000 rows", 3);
const hardestCase = tableCell("Hardest case — 384 concentrated low-firing rules", 3);
const interpreterMemory = tableCell("**Interpreter** — prepared model", 3);
const generatedJavaMemory = tableCell("A12 Kernel, generated Java", 3);
const generatedJavaMemoryRatio = tableCell("A12 Kernel, generated Java", 4);
const dynamicGroovyMemory = tableCell("A12 Kernel, dynamic Groovy", 3);
const dynamicGroovyMemoryRatio = tableCell("A12 Kernel, dynamic Groovy", 4);
const browserFirst = tableCell("200 rows — representative", 4).replace(/ median$/, "");
const browserWarm = tableCell("200 rows — representative", 5);
const javaKloc = asKloc(productionCodeLines("Java"));
const kotlinKloc = asKloc(productionCodeLines("Kotlin"));

const comparisonLine = report.split(/\r?\n/).find(line => line.includes("the interpreter is about **"))
  ?? fail("missing current JVM comparison line");
const generatedRatio = comparisonLine.match(/interpreter is about \*\*([^ ]+) the A12 Kernel's generated Java\*\*/)?.[1];
const dynamicRatio = comparisonLine.match(/and about \*\*([^ ]+) its dynamic Groovy\*\*/)?.[1];
if (!generatedRatio || !dynamicRatio) fail("missing current JVM comparison ratios");

const evidenceLine = report.split(/\r?\n/).filter(line => line.includes("<sub>Measured ")).at(-1)
  ?? fail("missing performance evidence footer");
const evidence = evidenceLine.replace(/^<sub>Measured /, "").split(/\s*·\s*/);
const measurementDate = (evidence[0] ?? "").trim();
const machine = (evidence[1] ?? "").trim();
const browser = (evidence[2] ?? "").trim();
const jdk = (evidence[3] ?? "").trim();

const promptLines = [
  `- Section title: "WARM VALIDATION • ${stressRows} • ${stressFindings} findings"`,
  `- Bar value: "${interpreterJvm}"`,
  `- Bar value: "${generatedJavaJvm}"`,
  `- Bar value: "${dynamicGroovyJvm}"`,
  `- Callout: "≈${generatedRatio} faster vs generated Java"`,
  `- Callout: "≈${dynamicRatio} faster vs dynamic Groovy"`,
  `- Card value: "${defaultInventory} faster"`,
  `- Card value: "${repeatedDocuments} faster"`,
  `- Card value: "${hardestCase} faster"`,
  `- Card value: "${hugeDocument} faster"`,
  `- Memory value: "${interpreterMemory}"`,
  `- Memory value: "${generatedJavaMemory}"`,
  `- Memory callout: "${generatedJavaMemoryRatio}"`,
  `- Memory value: "${dynamicGroovyMemory}"`,
  `- Memory callout: "${dynamicGroovyMemoryRatio}"`,
  `- Browser value: "${browserFirst} first answer"`,
  `- Browser value: "${browserWarm} warm"`,
  `- Bottom evidence: "Measured ${measurementDate} • ${machine} • ${jdk} • ${browser}"`,
  `- Java size: "${javaKloc}"`,
  `- Kotlin size: "${kotlinKloc}"`,
];
for (const promptLine of promptLines) requirePromptLine(promptLine);

function registryRows(): RegistryRow[] {
  const rows: RegistryRow[] = [];
  let inRegistry = false;
  for (const line of specification.split(/\r?\n/)) {
    if (line.includes("<!-- infographic-registry:start -->")) {
      inRegistry = true;
      continue;
    }
    if (line.includes("<!-- infographic-registry:end -->")) inRegistry = false;
    if (!inRegistry || !line.startsWith("|")) continue;
    const cells = line.split("|");
    const canonicalRelative = (cells[2] ?? "").trim().replaceAll("`", "");
    if (canonicalRelative === "Canonical asset" || /^-+$/.test(canonicalRelative)) continue;
    rows.push({
      canonicalRelative,
      documentTarget: (cells[3] ?? "").replace(/^.*\]\(/, "").replace(/\).*$/, ""),
      previewRelative: (cells[4] ?? "").trim().replaceAll("`", ""),
      previewWidth: (cells[5] ?? "").trim().replaceAll("`", ""),
    });
  }
  return rows;
}

function resolveThroughExistingParent(candidate: string, description: string): string {
  try {
    return path.join(fs.realpathSync(path.dirname(candidate)), path.basename(candidate));
  } catch {
    return fail(`${description} does not exist: ${candidate}`);
  }
}

const registeredAssets = new Set<string>();
const registeredPlacements = new Set<string>();
const rows = registryRows();
for (const row of rows) {
  if (!row.canonicalRelative.startsWith("assets/infographics/") || !row.canonicalRelative.endsWith(".avif")) {
    fail(`registry canonical asset must be an AVIF under assets/infographics: ${row.canonicalRelative}`);
  }
  const canonical = path.join(ROOT, row.canonicalRelative);
  const document = resolveThroughExistingParent(
    path.join(SPECIFICATION_DIR, row.documentTarget),
    "registry document directory",
  );
  const linked = resolveThroughExistingParent(
    path.join(path.dirname(document), row.previewRelative),
    "registry preview directory",
  );
  if (!fs.existsSync(canonical) || !fs.statSync(canonical).isFile()) fail(`missing canonical infographic: ${row.canonicalRelative}`);
  if (!fs.existsSync(document) || !fs.statSync(document).isFile()) fail(`missing infographic preview document: ${row.documentTarget}`);
  if (!fs.existsSync(linked) || !fs.statSync(linked).isFile()) fail(`missing linked infographic: ${row.documentTarget} -> ${row.previewRelative}`);
  checkPreview(document, row.previewRelative, row.previewWidth);

  const placement = `${document}:${row.previewRelative}`;
  if (registeredPlacements.has(placement)) fail(`duplicate infographic registry placement: ${row.documentTarget} -> ${row.previewRelative}`);
  registeredPlacements.add(placement);
  registeredAssets.add(row.canonicalRelative);
  if (linked !== canonical && !fs.readFileSync(canonical).equals(fs.readFileSync(linked))) {
    fail(`${linked} has drifted from ${row.canonicalRelative}`);
  }
}

if (rows.length === 0) fail("INFOGRAPHICS.md contains no parseable infographic registry placements");
for (const name of fs.readdirSync(path.join(ROOT, "assets/infographics"))) {
  if (!name.endsWith(".avif")) continue;
  const relative = `assets/infographics/${name}`;
  if (!registeredAssets.has(relative)) fail(`canonical infographic is not registered in INFOGRAPHICS.md: ${relative}`);
}

console.log("infographic freshness: OK");

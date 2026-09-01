const fs = require("node:fs");
const path = require("node:path");
const { spawnSync } = require("node:child_process");

type InitInspection = {
  ownsRepositoryInit: boolean;
  unhonoured: string[];
};

const OWN_INIT = "public-repos.init.gradle";
const MARKER = ".no-global-gradle-repos";

function isFile(file: string): boolean {
  try {
    return fs.statSync(file).isFile();
  } catch {
    return false;
  }
}

function isDirectory(directory: string): boolean {
  try {
    return fs.statSync(directory).isDirectory();
  } catch {
    return false;
  }
}

function initScriptsOf(base: string): string[] {
  const scripts: string[] = [];
  const initDirectory = path.join(base, "init.d");
  if (isDirectory(initDirectory)) {
    for (const name of fs.readdirSync(initDirectory).sort()) {
      if (/\.gradle(?:\.kts)?$/.test(name) && isFile(path.join(initDirectory, name))) {
        scripts.push(path.join(initDirectory, name));
      }
    }
  }
  for (const name of ["init.gradle", "init.gradle.kts"]) {
    const candidate = path.join(base, name);
    if (isFile(candidate)) scripts.push(candidate);
  }
  return scripts;
}

function inspectInitScripts(base: string, ownInit: string): InitInspection {
  const inspection: InitInspection = { ownsRepositoryInit: false, unhonoured: [] };
  for (const candidate of initScriptsOf(base)) {
    const name = path.basename(candidate);
    if (name === ownInit) {
      inspection.ownsRepositoryInit = true;
    } else if (!fs.readFileSync(candidate, "utf8").includes(MARKER)) {
      inspection.unhonoured.push(name);
    }
  }
  return inspection;
}

function executablePath(command: string, environment: NodeJS.ProcessEnv): string | undefined {
  const candidates = command.includes(path.sep)
    ? [command]
    : (environment.PATH ?? "").split(path.delimiter).filter(Boolean).map(directory => path.join(directory, command));
  for (const candidate of candidates) {
    try {
      fs.accessSync(candidate, fs.constants.X_OK);
      if (fs.statSync(candidate).isFile()) return candidate;
    } catch {
      // An absent or non-executable candidate is not selected by a shell's command lookup either.
    }
  }
  return undefined;
}

function canonical(directory: string): string {
  try {
    return fs.realpathSync(directory);
  } catch {
    return path.resolve(directory);
  }
}

class Report {
  failures = 0;

  pass(message: string): void {
    console.log(`  \u001b[32mPASS\u001b[0m  ${message}`);
  }

  warn(message: string): void {
    console.log(`  \u001b[33mWARN\u001b[0m  ${message}`);
  }

  fail(message: string): void {
    console.log(`  \u001b[31mFAIL\u001b[0m  ${message}`);
    this.failures++;
  }

  note(message: string): void {
    console.log(`        ${message}`);
  }
}

function reportGradleHome(report: Report, label: string, base: string): void {
  const inspection = inspectInitScripts(base, OWN_INIT);
  if (inspection.unhonoured.length === 0) {
    if (inspection.ownsRepositoryInit) report.pass(`${label} runs only this project's own init script`);
    else report.pass(`${label} injects nothing into this build`);
    return;
  }
  report.warn(`${label} runs init script(s) that ignore the marker: ${inspection.unhonoured.join(" ")}`);
  report.note("a build using that home resolves through whatever they inject; ./build.sh avoids it");
  report.note("detection is textual: a script is treated as honouring the marker if it names it at all");
}

function gitHooksPath(root: string): string {
  const result = spawnSync("git", ["-C", root, "config", "--get", "core.hooksPath"], {
    encoding: "utf8",
    env: process.env,
  });
  return result.status === 0 ? result.stdout.trim() : "";
}

function main(): number {
  const root = path.resolve(__dirname, "../..");
  const home = process.env.HOME ?? "";
  const report = new Report();

  console.log(`\nBuild environment for ${root}\n`);
  console.log("Toolchain");
  report.pass("POSIX shell launcher reached the typed doctor — /bin/sh contract, Dash oracle");
  report.pass(`Node.js runtime meets the >= 24.12.0 baseline — ${process.versions.node} (${process.env.A12_NODE ?? "node"})`);

  if (executablePath("/usr/libexec/java_home", process.env) !== undefined) {
    const jdk21 = process.env.A12_DOCTOR_PINNED_JDK21 ?? "";
    if (process.env.A12_DOCTOR_PINNED_JDK21_AVAILABLE === "1" && jdk21 !== "") {
      report.pass(`JDK 21 available — ${jdk21}`);
    } else {
      report.fail("no JDK 21 registered; build.sh pins the toolchain with java_home -v 21");
      report.note("install one, e.g. brew install openjdk@21, then link it so java_home sees it");
    }
  } else if ((process.env.JAVA_HOME ?? "") !== "") {
    report.pass(`JAVA_HOME set — ${process.env.JAVA_HOME} (no java_home on this platform; you own the pin)`);
  } else {
    report.warn("no java_home and no JAVA_HOME; the build will use whatever java is on PATH");
  }
  if ((process.env.JAVA_HOME ?? "") !== "") report.note(`current JAVA_HOME: ${process.env.JAVA_HOME}`);

  console.log("\nGradle home");
  const gradleHome = process.env.A12_DOCTOR_GRADLE_HOME;
  if (gradleHome === undefined || gradleHome === "") {
    report.fail("the POSIX launcher did not provide the shared Gradle-home policy");
  } else if ((process.env.GRADLE_USER_HOME ?? "") !== "") {
    report.pass(`GRADLE_USER_HOME set explicitly — ${gradleHome}`);
  } else {
    report.note(`GRADLE_USER_HOME unset; ./build.sh would use ${gradleHome}, a bare ./gradlew would use $HOME/.gradle`);
  }
  report.note("a bare ./gradlew would use $HOME/.gradle regardless");

  console.log("\nPublic-only resolution");
  if (isFile(path.join(root, MARKER))) report.pass(`the ${MARKER} marker is present in this checkout`);
  else report.fail(`the ${MARKER} marker is missing; an init script that honours it would inject again`);

  const machineGradleHome = path.join(home, ".gradle");
  reportGradleHome(report, "the machine-default home", machineGradleHome);
  if (gradleHome !== undefined && gradleHome !== "" && canonical(gradleHome) !== canonical(machineGradleHome)) {
    reportGradleHome(report, "the home this build would use", gradleHome);
  }

  if (gitHooksPath(root) === ".githooks") {
    report.pass("the commit and push hooks are wired (core.hooksPath=.githooks)");
  } else {
    report.warn("the committed Git hooks are not wired in this clone");
    report.note(`run: git -C "${root}" config core.hooksPath .githooks`);
    report.note("the hooks guard commit messages, staged output/doc drift, and the pre-push CI preflight");
  }

  const actionlint = process.env.ACTIONLINT ?? "actionlint";
  const actionlintPath = executablePath(actionlint, process.env);
  if (actionlintPath !== undefined) {
    report.pass(`actionlint is available for the pre-push workflow check (${actionlintPath})`);
  } else {
    report.warn("actionlint is unavailable, so the pre-push hook will refuse rather than skip workflow validation");
    report.note("install it on macOS with: brew install actionlint");
  }

  console.log("\nKernel");
  if (isDirectory(path.join(home, ".m2/repository/com/mgmtp"))) {
    report.pass("kernel artifacts present in the local Maven repository");
  } else {
    report.fail("no kernel in $HOME/.m2; publish it once with the sibling a12-kernel-bootstrap kit");
    report.note("without it every module fails to resolve, which reads as a dependency error");
  }

  console.log("\nOptional");
  if (executablePath("direnv", process.env) !== undefined) {
    const profiles = [".zshrc", ".bashrc", ".zprofile"].map(name => path.join(home, name));
    const hooked = profiles.some(profile => isFile(profile) && fs.readFileSync(profile, "utf8").includes("direnv hook"));
    if (hooked) report.pass("direnv installed and hooked into the shell");
    else {
      report.warn("direnv installed but no shell hook found; .envrc will never apply");
      report.note('add: eval "$(direnv hook zsh)" to your shell profile, then re-open the shell');
    }
  } else {
    report.note("direnv not installed; optional — ./build.sh sets the same two variables explicitly");
  }

  console.log("");
  if (report.failures > 0) {
    console.log(`${report.failures} check(s) failed. See docs/BUILD-ENVIRONMENT.md.\n`);
    return 1;
  }
  console.log("Environment looks good.\n");
  return 0;
}

module.exports = { inspectInitScripts };

if (require.main === module) process.exitCode = main();

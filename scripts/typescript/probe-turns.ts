const fs = require("node:fs");
const path = require("node:path");

/**
 * Build a probe session's turns for `clean-agent.ts --turns`: the agent-facing body of probe/agent-preamble.md
 * with TURN 1, every later turn alone, and the body of probe/report-pass.md last.
 *
 * Only the text between a file's first two `---` lines reaches the agent; the runner notes above and below it
 * stay out, and from the task only the quoted `**Turn n**` blocks are taken, never the gold or the mining notes.
 *
 *   scripts/run-typescript.sh probe-turns scripts/typescript/probe-turns.ts probe/tasks/08-booking-date-ranges.md > turns.json
 */

const DEFAULT_SANDBOX = "/tmp/rk-probe-sandbox";

/** The agent-facing part of a harness file: the text between its first and second `---` line. */
function body(markdown: string): string {
  return markdown.split(/^---$/m)[1].trim();
}

/** The task's turn prompts, in order: each `**Turn n**` heading's `> ` lines, unquoted. */
function taskTurns(task: string): string[] {
  const turns: string[] = [];
  let current: string[] | undefined;
  for (const line of task.split("\n")) {
    if (/^\*\*Turn \d+\*\*$/.test(line)) {
      current = [];
      turns.push("");
    } else if (current && /^>( |$)/.test(line)) {
      current.push(line.replace(/^> ?/, ""));
      turns[turns.length - 1] = current.join("\n");
    } else if (current && line.trim() !== "") {
      current = undefined;
    }
  }
  return turns;
}

function probeTurns(preamble: string, task: string, reportPass: string, sandbox: string = DEFAULT_SANDBOX): string[] {
  const turns = taskTurns(task).map((text, i) => `TURN ${i + 1}\n${text}`);
  const session = [`${body(preamble)}\n\n${turns[0]}`, ...turns.slice(1), body(reportPass)];
  return session.map(turn => turn.split(DEFAULT_SANDBOX).join(sandbox));
}

module.exports = { probeTurns };

if (require.main === module) {
  const task = process.argv[2];
  if (!task) {
    console.error("usage: probe-turns.ts probe/tasks/<task>.md > turns.json");
    process.exit(2);
  }
  const probe = path.resolve(__dirname, "../../probe");
  const read = (file: string): string => fs.readFileSync(file, "utf8");
  const turns = probeTurns(read(path.join(probe, "agent-preamble.md")), read(task), read(path.join(probe, "report-pass.md")),
    process.env.RK_PROBE_SANDBOX ?? DEFAULT_SANDBOX);
  process.stdout.write(JSON.stringify(turns, null, 2) + "\n");
}

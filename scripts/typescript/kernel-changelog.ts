// The A12 kernel changelog, read from the published release-notes site and rendered as markdown, optionally
// narrowed to the releases of one upgrade. The site is a single-page app, so the changelog is not served as a
// page: it is a markdown syntax tree compiled into the main JavaScript bundle as one JSON.parse('…') literal.
// That literal is decoded here rather than evaluated, because the bundle is downloaded and untrusted.
const fs = require("node:fs");

const SITE = "https://geta12.com/";
const PAGE = "https://geta12.com/#/releases/releases-overview/kernel";
const MARKER = "Kernel Changelog";

type Node = { type: string; value?: string; depth?: number; url?: string; ordered?: boolean; children?: Node[] };

/** The main bundle's URL from the site's index page; refuses a page that no longer names one. */
function bundleUrl(indexHtml: string, base: string = SITE): string {
  const src = indexHtml.match(/<script[^>]*\ssrc="(main\.bundle\.[^"]+\.js[^"]*)"/)?.[1];
  if (!src) throw new Error("the site's index page names no main.bundle.*.js script; its layout changed");
  return new URL(src, base).toString();
}

/** Decodes a single-quoted JavaScript string literal body, the escapes a minifier emits. */
function unescapeJsString(raw: string): string {
  let out = "";
  for (let i = 0; i < raw.length; i++) {
    const c = raw[i];
    if (c !== "\\") { out += c; continue; }
    const e = raw[++i];
    if (e === "n") out += "\n";
    else if (e === "t") out += "\t";
    else if (e === "r") out += "\r";
    else if (e === "b") out += "\b";
    else if (e === "f") out += "\f";
    else if (e === "v") out += "\v";
    else if (e === "0" && !/[0-9]/.test(raw[i + 1] ?? "")) out += "\0";
    else if (e === "x") { out += String.fromCharCode(parseInt(raw.slice(i + 1, i + 3), 16)); i += 2; }
    else if (e === "u" && raw[i + 1] === "{") {
      const end = raw.indexOf("}", i);
      out += String.fromCodePoint(parseInt(raw.slice(i + 2, end), 16));
      i = end;
    } else if (e === "u") { out += String.fromCharCode(parseInt(raw.slice(i + 1, i + 5), 16)); i += 4; }
    else if (e === "\n") { /* line continuation */ }
    else out += e;
  }
  return out;
}

/** The changelog's syntax tree: the one JSON.parse('…') literal in the bundle whose tree carries the heading. */
function changelogTree(bundle: string): Node {
  const at = bundle.indexOf(`"value":"${MARKER}"`);
  if (at < 0) throw new Error(`the bundle carries no "${MARKER}" heading; the site's layout changed`);
  const open = "JSON.parse('";
  const start = bundle.lastIndexOf(open, at);
  if (start < 0) throw new Error("the changelog heading sits in no JSON.parse literal; the site's layout changed");
  let end = start + open.length;
  while (end < bundle.length && bundle[end] !== "'") end += bundle[end] === "\\" ? 2 : 1;
  const tree = JSON.parse(unescapeJsString(bundle.slice(start + open.length, end)));
  if (tree?.type !== "root") throw new Error("the changelog literal is not a markdown syntax tree");
  return tree;
}

function inline(nodes: Node[] = []): string {
  return nodes.map(node => {
    switch (node.type) {
      // a soft line break inside a paragraph is a space, so every paragraph stays on one line
      case "text": return (node.value ?? "").replace(/\n/g, " ");
      case "html": return node.value ?? "";
      case "inlineCode": return "`" + (node.value ?? "") + "`";
      case "strong": return "**" + inline(node.children) + "**";
      case "emphasis": return "*" + inline(node.children) + "*";
      case "delete": return "~~" + inline(node.children) + "~~";
      case "link": return "[" + inline(node.children) + "](" + (node.url ?? "") + ")";
      case "linkReference": return "[" + inline(node.children) + "]";
      case "break": return "\n";
      default: return inline(node.children);
    }
  }).join("");
}

function block(node: Node, indent: string): string[] {
  switch (node.type) {
    case "heading": return ["", "#".repeat(node.depth ?? 1) + " " + inline(node.children)];
    case "paragraph": return [indent + inline(node.children)];
    case "code": return [indent + "```", ...(node.value ?? "").split("\n").map(line => indent + line), indent + "```"];
    case "thematicBreak": return ["---"];
    case "list": return (node.children ?? []).flatMap((item, n) => {
      const lines = (item.children ?? []).flatMap(child => block(child, indent + "  "));
      const bullet = node.ordered ? `${n + 1}. ` : "- ";
      return lines.length ? [indent + bullet + lines[0].slice(indent.length + 2), ...lines.slice(1)] : [];
    });
    default: return (node.children ?? []).flatMap(child => block(child, indent));
  }
}

/** The tree as markdown, one paragraph per line. */
function render(tree: Node): string {
  return block(tree, "").join("\n").replace(/^\n+/, "") + "\n";
}

function version(text: string): number[] | undefined {
  return text.match(/^\[?(\d+)\.(\d+)\.(\d+)\]?/)?.slice(1).map(Number);
}

function compare(a: number[], b: number[]): number {
  for (let i = 0; i < 3; i++) if (a[i] !== b[i]) return a[i] - b[i];
  return 0;
}

/**
 * The release sections of `markdown` after `from` up to and including `to` (each `X.Y.Z`), newest first as the
 * changelog orders them. Either bound may be omitted; an unparseable bound is refused rather than read as open.
 */
function releasesBetween(markdown: string, from?: string, to?: string): string {
  const parse = (label: string, value?: string) => {
    if (value === undefined) return undefined;
    const parsed = version(value);
    if (!parsed) throw new Error(`${label} must be a version X.Y.Z, got '${value}'`);
    return parsed;
  };
  const lower = parse("--from", from);
  const upper = parse("--to", to);
  const kept: string[] = [];
  let keep = false;
  for (const line of markdown.split("\n")) {
    const release = line.startsWith("## ") ? version(line.slice(3)) : undefined;
    if (line.startsWith("## ")) {
      keep = release !== undefined && (!lower || compare(release, lower) > 0) && (!upper || compare(release, upper) <= 0);
      if (keep && kept.length) kept.push("");
    }
    if (keep) kept.push(line);
  }
  return kept.join("\n").replace(/\n{3,}/g, "\n\n").trim() + "\n";
}

function option(args: string[], name: string): string | undefined {
  const at = args.indexOf(name);
  return at < 0 ? undefined : args[at + 1];
}

async function main(args: string[]): Promise<void> {
  if (args.includes("--help")) {
    console.log(`usage: kernel-changelog.ts [--from X.Y.Z] [--to X.Y.Z] [--bundle <main.bundle.js>]\n`
      + `Prints the A12 kernel changelog published at ${PAGE} as markdown; --from (exclusive) and --to\n`
      + `(inclusive) narrow it to the releases of one upgrade. --bundle reads a saved bundle instead of fetching.`);
    return;
  }
  const saved = option(args, "--bundle");
  let bundle: string;
  let source: string;
  if (saved) {
    bundle = fs.readFileSync(saved, "utf8");
    source = saved;
  } else {
    const index = await fetch(SITE);
    if (!index.ok) throw new Error(`${SITE} answered ${index.status}`);
    source = bundleUrl(await index.text());
    const response = await fetch(source);
    if (!response.ok) throw new Error(`${source} answered ${response.status}`);
    bundle = await response.text();
  }
  const markdown = render(changelogTree(bundle));
  const from = option(args, "--from");
  const to = option(args, "--to");
  process.stdout.write(`<!-- source: ${PAGE} (bundle ${source}) -->\n\n`
    + (from || to ? releasesBetween(markdown, from, to) : markdown));
}

if (require.main === module) {
  main(process.argv.slice(2)).catch(error => {
    // Node's fetch reports every network failure as "fetch failed" and keeps the reason in `cause`
    const cause = error instanceof Error && error.cause instanceof Error ? ` (${error.cause.message})` : "";
    console.error(`kernel-changelog: ${error instanceof Error ? error.message : String(error)}${cause}`);
    if (cause) console.error(`kernel-changelog: if curl reaches ${SITE} where Node cannot, save the bundle the index names `
      + "with curl and pass it with --bundle");
    process.exitCode = 1;
  });
}
module.exports = { bundleUrl, unescapeJsString, changelogTree, render, releasesBetween };

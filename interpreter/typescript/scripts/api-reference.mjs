import {writeFileSync} from "node:fs";
import {fileURLToPath} from "node:url";

import {describePublicApi} from "./public-api.mjs";

const escapeHtml = (value) =>
  value
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;");

export async function renderTypeScriptReference(
  typescriptPath,
  indexPath,
  packageName,
  version,
) {
  const declarations = await describePublicApi(typescriptPath, indexPath);
  const undocumented = declarations
    .filter(({documentation}) => documentation.length === 0)
    .map(({name}) => name);
  if (undocumented.length > 0) {
    throw new Error(
      `undocumented public TypeScript exports: ${undocumented.join(", ")}`,
    );
  }

  const navigation = declarations
    .map(({name}) => `<li><a href="#${encodeURIComponent(name)}">${escapeHtml(name)}</a></li>`)
    .join("\n");
  const sections = declarations
    .map(({name, documentation, signature}) => [
      `<section id="${encodeURIComponent(name)}">`,
      `<h2>${escapeHtml(name)}</h2>`,
      `<p>${escapeHtml(documentation)}</p>`,
      `<pre><code>${escapeHtml(signature)}</code></pre>`,
      "</section>",
    ].join("\n"))
    .join("\n");

  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${escapeHtml(packageName)} ${escapeHtml(version)} TypeScript API</title>
<style>
:root { color-scheme: light dark; font-family: system-ui, sans-serif; line-height: 1.5; }
body { margin: 0 auto; max-width: 76rem; padding: 2rem; }
h1, h2 { line-height: 1.2; }
nav ul { columns: 3 14rem; padding-left: 1.25rem; }
section { border-top: 1px solid currentColor; margin-top: 2rem; padding-top: 1rem; }
pre { overflow-x: auto; padding: 1rem; background: color-mix(in srgb, CanvasText 6%, Canvas); }
code { font-family: ui-monospace, SFMono-Regular, Consolas, monospace; }
@media (max-width: 40rem) { body { padding: 1rem; } nav ul { columns: 1; } }
</style>
</head>
<body>
<header>
<h1>${escapeHtml(packageName)} TypeScript API</h1>
<p>Version ${escapeHtml(version)}. Generated from the declarations shipped in the release package.</p>
</header>
<nav aria-label="Public declarations"><ul>
${navigation}
</ul></nav>
<main>
${sections}
</main>
</body>
</html>
`;
}

const invokedPath = process.argv[1] && fileURLToPath(import.meta.url);
if (invokedPath && invokedPath === process.argv[1]) {
  const [typescriptPath, indexPath, packageName, version, outputPath] =
    process.argv.slice(2);
  if (!typescriptPath || !indexPath || !packageName || !version || !outputPath) {
    throw new Error(
      "usage: api-reference.mjs <typescript.js> <dist/index.d.ts> <package> <version> <output.html>",
    );
  }
  writeFileSync(
    outputPath,
    await renderTypeScriptReference(
      typescriptPath,
      indexPath,
      packageName,
      version,
    ),
    "utf8",
  );
}

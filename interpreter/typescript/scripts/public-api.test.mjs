import assert from "node:assert/strict";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { renderPublicApi } from "./public-api.mjs";

const typescriptPath = process.env.INTERPRETER_TYPESCRIPT_MODULE;
const fixtureRoot = (t) => {
  const root = mkdtempSync(join(tmpdir(), "a12-interpreter-api-"));
  t.after(() => rmSync(root, { recursive: true }));
  return root;
};

test("rejects a public declaration that references a package-local non-export", async (t) => {
  assert.ok(typescriptPath, "INTERPRETER_TYPESCRIPT_MODULE is required");
  const root = fixtureRoot(t);
  const index = join(root, "index.d.ts");
  writeFileSync(
    join(root, "api.d.ts"),
    [
      "interface Hidden { readonly value: string }",
      "export interface PublicApi { readonly hidden: Hidden }",
      "",
    ].join("\n"),
  );
  writeFileSync(index, 'export type { PublicApi } from "./api.js";\n');

  await assert.rejects(
    renderPublicApi(typescriptPath, index),
    /root does not export: Hidden/,
  );
});

test("renders the same declaration when every supporting type is exported", async (t) => {
  assert.ok(typescriptPath, "INTERPRETER_TYPESCRIPT_MODULE is required");
  const root = fixtureRoot(t);
  const index = join(root, "index.d.ts");
  writeFileSync(
    join(root, "api.d.ts"),
    [
      "export interface Hidden { readonly value: string }",
      "export interface PublicApi { readonly hidden: Hidden }",
      "",
    ].join("\n"),
  );
  writeFileSync(
    index,
    'export type { Hidden, PublicApi } from "./api.js";\n',
  );

  const output = await renderPublicApi(typescriptPath, index);

  assert.match(output, /## Hidden/);
  assert.match(output, /## PublicApi/);
});

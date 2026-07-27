import assert from "node:assert/strict";
import {mkdtempSync, rmSync, writeFileSync} from "node:fs";
import {tmpdir} from "node:os";
import {join} from "node:path";
import test from "node:test";

import {renderTypeScriptReference} from "./api-reference.mjs";

const typescriptPath = process.env.INTERPRETER_TYPESCRIPT_MODULE;
const fixtureVersion = "7.8.9";

const fixture = (t, declaration) => {
  const root = mkdtempSync(join(tmpdir(), "a12-interpreter-reference-"));
  t.after(() => rmSync(root, {recursive: true}));
  const index = join(root, "index.d.ts");
  writeFileSync(join(root, "api.d.ts"), `${declaration}\n`);
  writeFileSync(index, 'export type {PublicApi} from "./api.js";\n');
  return index;
};

test("renders documented root exports as escaped deterministic HTML", async (t) => {
  assert.ok(typescriptPath, "INTERPRETER_TYPESCRIPT_MODULE is required");
  const index = fixture(
    t,
    [
      "/** Returns values without exposing the private implementation. */",
      "export interface PublicApi<T> {",
      "  /** The stable public value. */",
      "  readonly value: T;",
      "}",
    ].join("\n"),
  );

  const output = await renderTypeScriptReference(
    typescriptPath,
    index,
    "@mbackschat/a12-interpreter",
    fixtureVersion,
  );

  assert.match(output, /Returns values without exposing the private implementation/);
  assert.match(output, /The stable public value/);
  assert.match(output, /export interface PublicApi/);
  assert.match(output, /&lt;/);
  assert.doesNotMatch(output, /<script src=/);
  assert.equal(
    output,
    await renderTypeScriptReference(
      typescriptPath,
      index,
      "@mbackschat/a12-interpreter",
      fixtureVersion,
    ),
  );
});

test("omits private class members from the documented signature", async (t) => {
  assert.ok(typescriptPath, "INTERPRETER_TYPESCRIPT_MODULE is required");
  const index = fixture(
    t,
    [
      "/** Public class with an implementation-only field. */",
      "export declare class PublicApi {",
      "  private readonly core;",
      "  /** Returns the public value. */",
      "  value(): string;",
      "}",
    ].join("\n"),
  );

  const output = await renderTypeScriptReference(
    typescriptPath,
    index,
    "@mbackschat/a12-interpreter",
    fixtureVersion,
  );

  assert.match(output, /Returns the public value/);
  assert.doesNotMatch(output, /private readonly core/);
});

test("rejects an undocumented public export", async (t) => {
  assert.ok(typescriptPath, "INTERPRETER_TYPESCRIPT_MODULE is required");
  const index = fixture(
    t,
    "export interface PublicApi { readonly value: string; }",
  );

  await assert.rejects(
    renderTypeScriptReference(
      typescriptPath,
      index,
      "@mbackschat/a12-interpreter",
      fixtureVersion,
    ),
    /undocumented public TypeScript exports: PublicApi/,
  );
});

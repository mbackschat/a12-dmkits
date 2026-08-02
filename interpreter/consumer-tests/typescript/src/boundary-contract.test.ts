import {mkdirSync, mkdtempSync, rmSync, writeFileSync} from "node:fs";
import {tmpdir} from "node:os";
import {join} from "node:path";
import {strict as assert} from "node:assert";
import test from "node:test";

import {
  assertBoundaryClassifications,
  assertHostileClassificationsExecuted,
  deriveInboundNumberPaths,
} from "./boundary-contract.js";

test("derives inbound numeric leaves without treating outputs as inputs", () => {
  const directory = mkdtempSync(join(tmpdir(), "a12-boundary-contract-"));
  const surface = join(directory, "surface");
  const declaration = join(surface, "index.d.ts");
  try {
    mkdirSync(surface);
    writeFileSync(join(directory, "external.d.ts"), `
      export interface ExternalOptions { readonly limit: number; }
    `);
    writeFileSync(declaration, `
      import type {ExternalOptions} from "../external.js";
      export interface RecursiveDocument {
        readonly [name: string]: string | number | RecursiveDocument;
      }
      export interface Options { readonly nested?: {readonly maximum?: number}; }
      export declare class Fault extends Error {
        readonly position: number;
        constructor(position: number);
      }
      export declare class Surface {
        static readonly Widget: new (size: number) => object;
        static set threshold(value: number);
        static direct(values: readonly number[], options?: Options): number;
        static factory(): Promise<{readonly nested: {run(limit: number): void}}>;
        static external(options: ExternalOptions): void;
        static builtin(date: Date): void;
        set quota(value: number);
        read(document: RecursiveDocument): void;
        callback(run: (outbound: {
          readonly ignored: number;
          use(limit: number, report: (ignored: number) => {readonly answer: number}): void;
        }) => {readonly answer: number}): void;
        readonly outputOnly: number;
      }
      declare function hidden(value: number): void;
      export {hidden as visible};
    `);
    assert.deepEqual(deriveInboundNumberPaths(declaration), [
      "Fault.constructor.position",
      "Surface.Widget.constructor.size",
      "Surface.callback.run.outbound.use.limit",
      "Surface.callback.run.outbound.use.report.return.answer",
      "Surface.callback.run.return.answer",
      "Surface.direct.options.nested.maximum",
      "Surface.direct.values[]",
      "Surface.external.options.limit",
      "Surface.factory.return.nested.run.limit",
      "Surface.quota.value",
      "Surface.read.document.*",
      "Surface.threshold.value",
      "visible.value",
    ]);
  } finally {
    rmSync(directory, {recursive: true, force: true});
  }
});

test("an unclassified derived path fails", () => {
  assert.throws(
    () => assertBoundaryClassifications(["Surface.call.value"], {}),
    /missing: Surface\.call\.value/,
  );
});

test("a stale authored classification fails", () => {
  assert.throws(
    () => assertBoundaryClassifications([], {
      "Removed.call.value": {disposition: "safe", reason: "positive control"},
    }),
    /stale: Removed\.call\.value/,
  );
});

test("a classified hostile boundary without executed evidence fails", () => {
  assert.throws(
    () => assertHostileClassificationsExecuted({
      "Surface.call.value": {disposition: "hostile-packed-test", reason: "numeric domain"},
    }, []),
    /missing: Surface\.call\.value/,
  );
});

test("executed evidence for a safe boundary fails", () => {
  assert.throws(
    () => assertHostileClassificationsExecuted({
      "Surface.call.value": {disposition: "safe", reason: "data payload"},
    }, ["Surface.call.value"]),
    /stale: Surface\.call\.value/,
  );
});

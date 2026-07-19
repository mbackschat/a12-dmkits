// dmtool custom-construct worker — evaluates a project's JS custom conditions / custom field types for the
// native CLI, which cannot host JS itself (the "escape to JS", FEATURE-GAPS G31). dmtool spawns ONE persistent
// worker and streams NDJSON requests on stdin; one response line per request on stdout. The worker NEVER outlives
// dmtool: it exits on stdin EOF (the OS closes the pipe the instant the parent dies — even on SIGKILL) and on an
// idle timeout (a belt-and-suspenders backstop). It is bundled in the binary and extracted to a temp file at run.
//
// Protocol (one JSON object per line):
//   request   { "id": <n>, "kind": "condition"|"fieldType", "name": "<TypeOrConditionName>", ...payload }
//   response  { "id": <n>, "result": <bool> [, "errorKey": <str>, "errorMessage": <str>] }   |   { "id": <n>, "error": "<message>" }
//
// A `condition` request carries the kernel's 4-param ICustomCondition contract (data view + relevant /
// formallyIncorrect sets + errorField); a `fieldType` request carries just the cell value. A field-type
// validator may return a plain boolean, OR an object { valid, errorKey?, errorMessage? } so a rejection can
// carry the project-specific error code (IF150 — the kernel's ICustomFieldValidator getErrorKey). The user's
// `<Name>.js` is imported once and cached.
//
// argv: [node, thisScript, <userDir>, <idleMs>]

import { createInterface } from 'node:readline';
import { pathToFileURL } from 'node:url';
import { resolve } from 'node:path';

const USER_DIR = process.argv[2];
const IDLE_MS = Number(process.argv[3] || 30000);

const moduleCache = new Map();   // name -> the resolved check/validate function

/** Import `<name>.js` from the user dir (once) and resolve its callable — tolerating the common export shapes. */
async function impl(name) {
  if (moduleCache.has(name)) return moduleCache.get(name);
  const mod = await import(pathToFileURL(resolve(USER_DIR, name + '.js')).href);
  const cand = mod.check ?? mod.validate ?? mod.default ?? mod;
  const fn = typeof cand === 'function' ? cand
    : (typeof cand?.check === 'function' ? cand.check.bind(cand)
      : (typeof cand?.validate === 'function' ? cand.validate.bind(cand) : null));
  if (!fn) throw new Error(`${name}.js exports no check(...) / validate(...) function`);
  moduleCache.set(name, fn);
  return fn;
}

const key = (name, indices) => name + '#' + indices.join(',');

/** A kernel-shaped IIdentifier ({ getName(), getIndices() }) from a { path, indices } pointer. */
function ident(p) {
  return { getName: () => p.path, getIndices: () => p.indices.slice() };
}

/** A kernel-shaped IData ({ getValue(id), iterator() }) over the request's serialized cells. */
function dataView(cells) {
  const values = new Map(cells.map(c => [key(c.path, c.indices), c.value]));
  const ids = cells.map(ident);
  return {
    getValue: (id) => { const v = values.get(key(id.getName(), id.getIndices())); return v == null ? null : v; },
    iterator: () => { let i = 0; return { hasNext: () => i < ids.length, next: () => ids[i++] }; },
    [Symbol.iterator]: function* () { yield* ids; },
  };
}

/** A kernel-shaped Set<IIdentifier> (value-equality has() + iteration); `undefined` for full validation. */
function idSet(list) {
  if (list == null) return undefined;
  const keys = new Set(list.map(p => key(p.path, p.indices)));
  const ids = list.map(ident);
  return { has: (id) => keys.has(key(id.getName(), id.getIndices())), size: ids.length, [Symbol.iterator]: function* () { yield* ids; } };
}

async function handle(req) {
  const fn = await impl(req.name);
  if (req.kind === 'fieldType') {
    const r = fn(req.value);
    // A field-type validator may return a boolean, or { valid, errorKey?, errorMessage? } to carry a project code.
    if (r && typeof r === 'object') {
      return { result: Boolean(r.valid ?? r.ok ?? false), errorKey: r.errorKey ?? r.code, errorMessage: r.errorMessage ?? r.message };
    }
    return { result: Boolean(r) };
  }
  // condition: data, relevantEntities (undefined = full), formallyIncorrectFields (never null), errorField
  return { result: Boolean(fn(dataView(req.data), idSet(req.relevant), idSet(req.formallyIncorrect) ?? idSet([]), ident(req.errorField))) };
}

let idle = setTimeout(() => process.exit(0), IDLE_MS);
const rl = createInterface({ input: process.stdin });
rl.on('line', async (line) => {
  clearTimeout(idle);
  let req = null;
  try { req = JSON.parse(line); } catch { idle = setTimeout(() => process.exit(0), IDLE_MS); return; }
  let resp;
  try { resp = { id: req.id, ...(await handle(req)) }; }
  catch (e) { resp = { id: req.id, error: String((e && e.message) || e) }; }
  process.stdout.write(JSON.stringify(resp) + '\n');
  idle = setTimeout(() => process.exit(0), IDLE_MS);
});
rl.on('close', () => process.exit(0));   // stdin EOF — the parent is gone

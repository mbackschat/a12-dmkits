import {readFile, writeFile} from "node:fs/promises";
import {gzipSync} from "node:zlib";

const manifest = JSON.parse(
  await readFile("dist/.vite/manifest.json", "utf8"),
);
const entries = Object.values(manifest).filter(({isEntry}) => isEntry);
if (entries.length !== 1 || !entries[0].file?.endsWith(".js")) {
  throw new Error(
    `expected one JavaScript entry bundle, found ${entries.length}`,
  );
}

const entry = entries[0].file;
const bytes = await readFile(`dist/${entry}`);
await writeFile(
  "dist/bundle-size.json",
  `${JSON.stringify({
    schemaVersion: 1,
    asset: entry,
    uncompressedBytes: bytes.byteLength,
    gzipBytes: gzipSync(bytes).byteLength,
  })}\n`,
  "utf8",
);

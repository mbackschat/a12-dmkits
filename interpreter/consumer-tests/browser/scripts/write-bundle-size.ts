import {readFile, writeFile} from "node:fs/promises";
import {gzipSync} from "node:zlib";

interface ViteManifestEntry {
  readonly file: string;
  readonly isEntry?: boolean;
}

const manifest = JSON.parse(
  await readFile("dist/.vite/manifest.json", "utf8"),
) as Record<string, ViteManifestEntry>;
const entries = Object.values(manifest).filter(({isEntry}) => isEntry);
const entry = entries[0];
if (
  entries.length !== 1 ||
  entry === undefined ||
  !entry.file.endsWith(".js")
) {
  throw new Error(
    `expected one JavaScript entry bundle, found ${entries.length}`,
  );
}

const bytes = await readFile(`dist/${entry.file}`);
await writeFile(
  "dist/bundle-size.json",
  `${JSON.stringify({
    schemaVersion: 1,
    asset: entry.file,
    uncompressedBytes: bytes.byteLength,
    gzipBytes: gzipSync(bytes).byteLength,
  })}\n`,
  "utf8",
);

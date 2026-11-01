# Checker browser showcase

The public [showcase](https://mbackschat.github.io/a12-consistency-releases/showcase/) consumes the packed `a12-consistency` JavaScript artifact. It supports model JSON, existing Storefront fixtures, local files and candidate draft text. It displays model findings, source diagnostics, draft attribution and coverage separately. It does not upload model contents or persist inputs.

Build from the repository root:

```sh
./build.sh :consistency:packConsistencyNpmPackage
scripts/run-typescript.sh "checker showcase" scripts/typescript/checker-showcase.ts
cd build/consistency-showcase
pnpm run verify
```

The builder derives the version from the root build unless an explicit version is supplied. It stages sources and the existing consumer fixtures under `build/`, installs the packed artifact, checks its package identity, type-checks and bundles with Vite. Source maps are not published. The browser verification uses system Chrome (`CHROME_BIN` overrides its path), exercises scoped pass, rejection, malformed input, draft attribution and local-file privacy, and saves desktop/mobile screenshots in the build directory.

Publication uses the separate presentation refresh described in the [release specification](../../docs/RELEASE-SPEC.md#checker-presentation). Public templates live in [the release README](../public-release/README.md) and its sibling `site/` directory. The wider [API contract](../../docs/CONSISTENCY-API.md) remains authoritative for report semantics. This showcase adds no checker semantics and runs no performance measurements.

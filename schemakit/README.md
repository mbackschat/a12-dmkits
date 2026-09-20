# `:schemakit` — JSON Schema and OpenAPI ⇄ DocumentModel

Published as `io.github.mbackschat.a12.dm:dmkits-schemakit` (MIT). Package `io.github.mbackschat.a12.dm.schemakit`.

The transcoder between A12 DocumentModels and the schema formats the rest of the world writes: [`JsonSchemaImporter`](src/main/java/io/github/mbackschat/a12/dm/schemakit/JsonSchemaImporter.java) turns a JSON Schema — or an OpenAPI 3.0/3.1/3.2 document, dialect auto-detected — into a kernel-valid model, and [`JsonSchemaExporter`](src/main/java/io/github/mbackschat/a12/dm/schemakit/JsonSchemaExporter.java) goes back the other way on a best-effort basis. `dmtool model import jsonschema` / `model import openapi` / `model export jsonschema` are this module behind a verb.

**Import is lossy in one direction and honest about it.** A schema carries less than a DocumentModel does, so [`TranscodeReport`](src/main/java/io/github/mbackschat/a12/dm/schemakit/TranscodeReport.java) reports what was mapped, what was approximated and what was dropped, rather than producing a model that looks complete. [`MappingProfile`](src/main/java/io/github/mbackschat/a12/dm/schemakit/MappingProfile.java) and [`TierAProjection`](src/main/java/io/github/mbackschat/a12/dm/schemakit/TierAProjection.java) decide how far the mapping reaches; [`RuleSynthesis`](src/main/java/io/github/mbackschat/a12/dm/schemakit/RuleSynthesis.java) derives the rules a schema's constraints imply.

**It never touches the raw kernel jars.** It reaches A12 only through this project's own mediated modules — the kits, [`:adapter`](../adapter)'s type-converting boundary, and the kernel-free [`:interpreter`](../interpreter). `verifySchemakitPurity` holds its compiled classes to that, and `verifyPublishedClasspathsAreKernelFree` holds what its POM resolves.

Build and test: `./build.sh :schemakit:check` — `check`, not `test`, because the purity lint is what holds the claim above. Contract: [`docs/SCHEMAKIT-SPEC.md`](../docs/SCHEMAKIT-SPEC.md) · open items: [`docs/SCHEMAKIT-GAPS.md`](../docs/SCHEMAKIT-GAPS.md).

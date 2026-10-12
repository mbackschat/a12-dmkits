# `:schemakit` — JSON Schema and OpenAPI ⇄ DocumentModel

## The problem

A12 speaks DocumentModel. Almost everything else speaks JSON Schema or OpenAPI. So two things keep coming up, and neither is a mechanical translation.

**Starting a model from a schema you already have.** An API's `components/schemas` entry describes the same domain the model needs to describe, and retyping it by hand is both slow and where the mistakes come from. But a schema's vocabulary does not line up one-to-one: `format: "date"` has to become a DATE field *with a format and a precision*, an `enum` has to become an enum type definition with categories, and `pattern`, `minLength`, `minimum` and `dependentRequired` are **constraints** that map to A12 field configuration or validation rules.

**Publishing a model as a schema.** Going back out is *lossy*, and that is the part that bites. A DocumentModel carries more than a schema can hold — iteration scope, computed fields, per-locale error text, error-target containment. An exporter that silently drops those produces a schema that looks authoritative and is quietly narrower than the model.

## What this module does about it

**Import** — [`JsonSchemaImporter`](src/main/java/io/github/mbackschat/a12/dm/schemakit/JsonSchemaImporter.java) builds a kernel-valid model from a JSON Schema, or from an OpenAPI 3.0/3.1/3.2 document with the [`Dialect`](src/main/java/io/github/mbackschat/a12/dm/schemakit/Dialect.java) auto-detected. [`RuleSynthesis`](src/main/java/io/github/mbackschat/a12/dm/schemakit/RuleSynthesis.java) turns the schema's constraints into the rules they imply, while native string `pattern` maps to declared field configuration.

**Export** — [`JsonSchemaExporter`](src/main/java/io/github/mbackschat/a12/dm/schemakit/JsonSchemaExporter.java) goes the other way on a best-effort basis, and [`TierAProjection`](src/main/java/io/github/mbackschat/a12/dm/schemakit/TierAProjection.java) is the part that reads the model's *existing rules* to project the ones a schema can actually express (a whole unguarded fixed-root row-count rule onto `minItems` plus `required`). Rule patterns stay carried; recognized former native candidates receive a diagnostic; native field patterns remain declaration-owned. It reads them through `ConditionReadBack`, which is why it works with no kernel present — see [`:interpreter-bridge`](../interpreter-bridge).

**And both directions report what they could not do.** [`TranscodeReport`](src/main/java/io/github/mbackschat/a12/dm/schemakit/TranscodeReport.java) names what was mapped, what was approximated and what was dropped, with a diagnostic per case. That is the answer to the lossiness problem: not a cleverer mapping, but a mapping that tells you its own limits. [`MappingProfile`](src/main/java/io/github/mbackschat/a12/dm/schemakit/MappingProfile.java) decides how far it reaches.

A12-origin schemas carry unchanged root names and group index references alongside native object/array structure. Root identity and repetition survive independently of the model id and carried rules; untagged foreign objects use `/Root`. The [mapping contract](../docs/SCHEMAKIT-SPEC.md) defines the annotations and their guards.

`dmtool model import jsonschema` / `model import openapi` / `model export jsonschema` are this module behind a verb, including importing a whole OpenAPI document as a bundle — one model per component, wired by includes.

## It never touches the raw kernel jars

It needs kernel *authority* (an imported model must be accepted, not merely produced) but reaches it only through this project's own mediated modules: the kits, [`:adapter`](../adapter)'s type-converting boundary, and the kernel-free [`:interpreter`](../interpreter). `verifySchemakitPurity` holds its compiled classes to that, and `verifyPublishedClasspathsAreKernelFree` holds what its published POM resolves — the second being the claim a consumer can actually feel.

## Reference

Its publication coordinate is `io.github.mbackschat.a12.dm:dmkits-schemakit` (MIT). Package `io.github.mbackschat.a12.dm.schemakit`. The publish wiring is landed and the public deploy is **deferred** ([`../docs/RELEASE-SPEC.md`](../docs/RELEASE-SPEC.md) §11), so today you get it by `./build.sh publishToMavenLocal` and `mavenLocal()`, not from a registry.

```sh
./build.sh :schemakit:check   # check, not test — verifySchemakitPurity is the guard behind the claim above
```

Contract: [`docs/SCHEMAKIT-SPEC.md`](../docs/SCHEMAKIT-SPEC.md) · open items: [`docs/SCHEMAKIT-GAPS.md`](../docs/SCHEMAKIT-GAPS.md) · worked walkthrough: [`examples/cli-jsonschema.md`](../examples/cli-jsonschema.md).

Scalar arrays preserve present empty strings through the default repeatable single-field strategy. Multi-select/value-list templates that cannot preserve empty admission are explicitly omitted with a corrective; explicit nonempty item constraints retain mandatory validation. See the [scalar-array contract](../docs/SCHEMAKIT-SPEC.md#4f-synthesized-rules--the-exact-a12-fire-condition-tier-a).

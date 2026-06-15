# `rulekit-manual` — rule-based native-image reflection metadata (generated)

`reachability-metadata.json` here is **generated, not hand-edited** — by
[`scripts/gen-native-reflect-metadata.sh`](../../../../../../../scripts/gen-native-reflect-metadata.sh) — and
kept **separate** from the captured `../rulekit-agent/` file so a re-capture never clobbers it. GraalVM
merges every `META-INF/native-image/**/reachability-metadata.json` on the classpath.

## Why — the two surfaces the kernel reaches *by name* (capture-bound otherwise)

GraalVM static analysis can't see reflection resolved by name at runtime, and the tracing agent only records
the *shapes a traced run touches*. Two kernel surfaces are reached this way, and both live in **bounded,
enumerable packages**, so we register every class in them **by rule** — coverage is then shape-independent:

| Surface | Reached by | Package | #classes |
|---|---|---|---|
| Jackson model / field-type POJOs | `FieldtypeDeserializer` reflects **setters** | `md.model.a12internal.*` | 102 |
| StringTemplate codegen (`checkConsistency`) | `ObjectModelAdaptor` reflects **getters by name** | `core.codegen.internal.generator.*` | 297 |

This is the durable fix for the v0.1.0/v0.1.1 native bugs (a patterned STRING field crashed on
`StringType.setErrorMessage`, then failed validation on `CodeGenMetaFields.metaFelderCode`). Registering the
*whole packages* means a new field constraint / construct can't reintroduce the gap. Cost: ~+0.8 MB (the
classes are already in the image; only metadata is added).

## On a kernel bump — REGENERATE

These entries are derived from the kernel's own classes, so a kernel bump can change the set. Re-run
`scripts/gen-native-reflect-metadata.sh` (it reads the pinned kernel jars), rebuild, re-pass the native gate.
Full procedure + the failure taxonomy + the residual gate: [`docs/NATIVE-IMAGE-GRAALVM.md`](../../../../../../../docs/NATIVE-IMAGE-GRAALVM.md) §3.4–§3.6, and the coupling row in [`docs/KERNEL-UPGRADE.md`](../../../../../../../docs/KERNEL-UPGRADE.md).

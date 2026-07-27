# `@mbackschat/a12-interpreter`

Kernel-free Kotlin Multiplatform interpreter for A12 document models, packaged for TypeScript and JavaScript.

The first package surface supports finite and on-demand model workspaces, include/type-definition expansion, strict preparation, lossless placement-document decoding, full/partial validation, and computation. It performs no filesystem or network I/O: callers provide model sources through a finite array or `ModelSourceProvider`.

```ts
import {
  ModelWorkspace,
  type DocumentInputV1,
} from "@mbackschat/a12-interpreter";

const workspace = ModelWorkspace.fromSources([{json: rawModelJson}]);
const prepared = workspace.prepare("policy");
const input: DocumentInputV1 = {
  version: 1,
  groups: [{path: "/Policy", coordinates: [1]}],
  fields: [],
};
const snapshot = prepared.documents.decodeDocument(input);
const report = prepared.createInterpreter().validateFull(snapshot);
```

This prerelease package is MIT-licensed. The canonical guide and full generated API reference are added by the release program’s documentation milestone.

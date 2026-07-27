// tag::interpreter-typescript-provider[]
import {
  ModelWorkspace,
  type ModelSourceProvider,
  type WorkspaceModelSource,
} from "@mbackschat/a12-interpreter";

export async function collectWorkspace(
  entryModelId: string,
  sourcesById: ReadonlyMap<string, WorkspaceModelSource>,
): Promise<ModelWorkspace> {
  const provider: ModelSourceProvider = {
    snapshotRevision: "example-v1",
    async load(modelId, {signal}) {
      if (signal.aborted) {
        throw new DOMException("Collection cancelled", "AbortError");
      }
      return sourcesById.get(modelId);
    },
  };
  return ModelWorkspace.collect(entryModelId, provider);
}
// end::interpreter-typescript-provider[]

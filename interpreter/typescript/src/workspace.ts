import type {InterpreterBridgeWorkspace} from "../internal/a12-interpreter-core.mjs";

import {
  type A12Result,
  ModelPreparationError,
  resultOf,
  throwBridgeFailure,
} from "./errors.js";
import {bridge} from "./internal/bridge.js";
import {
  type ModelPreparationLimits,
  preparationLimitsJson,
} from "./limits.js";
import {PreparedModel} from "./prepared-model.js";

export interface WorkspaceModelSource {
  readonly json: string;
  readonly diagnosticLabel?: string;
  readonly expectedSha256?: string;
}

export interface ModelSourceLoadContext {
  readonly maximumBytes: number;
  readonly signal: AbortSignal;
}

export interface ModelSourceProvider {
  readonly snapshotRevision?: string;
  load(
    modelId: string,
    context: ModelSourceLoadContext,
  ): Promise<WorkspaceModelSource | undefined>;
}

export interface ModelCollectionOptions {
  readonly limits?: ModelPreparationLimits;
  readonly signal?: AbortSignal;
}

const bridgeSource = (
  source: WorkspaceModelSource,
): InstanceType<typeof bridge.InterpreterBridgeWorkspaceSource> =>
  new bridge.InterpreterBridgeWorkspaceSource(
    source.json,
    source.diagnosticLabel,
    source.expectedSha256,
  );

const defaultSignal = (): AbortSignal => new AbortController().signal;

export class ModelWorkspace {
  readonly modelIds: readonly string[];
  readonly snapshotRevision: string | undefined;

  /** @internal */
  constructor(private readonly core: InterpreterBridgeWorkspace) {
    this.modelIds = Object.freeze([...core.modelIds]);
    this.snapshotRevision = core.snapshotRevision ?? undefined;
  }

  static fromSources(
    sources: readonly WorkspaceModelSource[],
    limits?: ModelPreparationLimits,
  ): ModelWorkspace {
    const result = bridge.interpreterBridgeWorkspaceFromSources(
      sources.map(bridgeSource),
      preparationLimitsJson(limits),
    );
    if (result.failure !== null && result.failure !== undefined) {
      throwBridgeFailure(result.failure);
    }
    if (result.value === null || result.value === undefined) {
      throw new Error("interpreter bridge returned no workspace");
    }
    return new ModelWorkspace(result.value);
  }

  static async collect(
    entryModelId: string,
    provider: ModelSourceProvider,
    options?: ModelCollectionOptions,
  ): Promise<ModelWorkspace> {
    const signal = options?.signal ?? defaultSignal();
    const result = await bridge.interpreterBridgeCollectWorkspace(
      entryModelId,
      provider.snapshotRevision,
      preparationLimitsJson(options?.limits),
      async (modelId, maximumBytes) => {
        const source = await provider.load(modelId, {maximumBytes, signal});
        return source === undefined ? undefined : bridgeSource(source);
      },
      () => signal.aborted,
    );
    if (result.failure !== null && result.failure !== undefined) {
      throwBridgeFailure(result.failure);
    }
    if (result.value === null || result.value === undefined) {
      throw new Error("interpreter bridge returned no collected workspace");
    }
    return new ModelWorkspace(result.value);
  }

  expand(entryModelId: string): string {
    const result = this.core.expand(entryModelId);
    if (result.failure !== null && result.failure !== undefined) {
      throwBridgeFailure(result.failure);
    }
    if (result.value === null || result.value === undefined) {
      throw new Error("interpreter bridge returned no expanded model");
    }
    return result.value;
  }

  tryExpand(
    entryModelId: string,
  ): A12Result<string, ModelPreparationError> {
    return resultOf(
      () => this.expand(entryModelId),
      ModelPreparationError,
    );
  }

  prepare(entryModelId: string): PreparedModel {
    const result = this.core.prepare(entryModelId);
    if (result.failure !== null && result.failure !== undefined) {
      throwBridgeFailure(result.failure);
    }
    if (result.value === null || result.value === undefined) {
      throw new Error("interpreter bridge returned no prepared model");
    }
    return new PreparedModel(result.value);
  }

  tryPrepare(
    entryModelId: string,
  ): A12Result<PreparedModel, ModelPreparationError> {
    return resultOf(
      () => this.prepare(entryModelId),
      ModelPreparationError,
    );
  }
}

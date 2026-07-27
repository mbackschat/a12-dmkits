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

/** Raw DM-JSON plus optional safe label and expected SHA-256 integrity. */
export interface WorkspaceModelSource {
  readonly json: string;
  readonly diagnosticLabel?: string;
  readonly expectedSha256?: string;
}

/** Remaining byte ceiling and cancellation signal for one provider request. */
export interface ModelSourceLoadContext {
  readonly maximumBytes: number;
  readonly signal: AbortSignal;
}

/** Application-owned asynchronous source acquisition port. */
export interface ModelSourceProvider {
  readonly snapshotRevision?: string;
  load(
    modelId: string,
    context: ModelSourceLoadContext,
  ): Promise<WorkspaceModelSource | undefined>;
}

/** Resource limits and cancellation for on-demand workspace collection. */
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

/** Immutable finite source snapshot that expands or prepares one selected model closure. */
export class ModelWorkspace {
  /** Exact model ids frozen into this workspace. */
  readonly modelIds: readonly string[];
  /** Optional sanitized provider revision; excluded from prepared-model identity. */
  readonly snapshotRevision: string | undefined;

  /** @internal */
  constructor(private readonly core: InterpreterBridgeWorkspace) {
    this.modelIds = Object.freeze([...core.modelIds]);
    this.snapshotRevision = core.snapshotRevision ?? undefined;
  }

  /** Admit and freeze a caller-supplied finite model source set. */
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

  /** Load one discovered dependency closure through an application-owned provider. */
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

  /** Return self-contained DM-JSON without applying evaluator support checks. */
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

  /** Return expansion success or the same typed preparation error without throwing. */
  tryExpand(
    entryModelId: string,
  ): A12Result<string, ModelPreparationError> {
    return resultOf(
      () => this.expand(entryModelId),
      ModelPreparationError,
    );
  }

  /** Resolve and load one entry model as a reusable prepared model. */
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

  /** Return preparation success or the same typed preparation error without throwing. */
  tryPrepare(
    entryModelId: string,
  ): A12Result<PreparedModel, ModelPreparationError> {
    return resultOf(
      () => this.prepare(entryModelId),
      ModelPreparationError,
    );
  }
}

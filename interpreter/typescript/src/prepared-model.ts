import type {InterpreterBridgePreparedModel} from "../internal/a12-interpreter-core.mjs";

import {PreparedDocumentApi} from "./documents.js";
import {
  customConditionRegistrations,
  customFieldTypeRegistrations,
  evaluationInstantMillis,
  type InterpreterConfiguration,
} from "./customization.js";
import {
  type A12Result,
  ModelPreparationError,
  resultOf,
  throwBridgeFailure,
} from "./errors.js";
import {Interpreter} from "./evaluation.js";
import {bridge} from "./internal/bridge.js";
import {ModelMetadata} from "./model.js";

/** One distinct resolved workspace dependency in deterministic discovery order. */
export interface ModelDependency {
  readonly fromModelId: string;
  readonly toModelId: string;
  readonly purpose: "include" | "type-definitions";
  readonly diagnosticLabel?: string;
  readonly sha256: string;
}

const dependency = (
  source: InterpreterBridgePreparedModel["dependencies"][number],
): ModelDependency =>
  Object.freeze({
    fromModelId: source.fromModelId,
    toModelId: source.toModelId,
    purpose: source.purpose as "include" | "type-definitions",
    ...(source.diagnosticLabel === null || source.diagnosticLabel === undefined
      ? {}
      : {diagnosticLabel: source.diagnosticLabel}),
    sha256: source.sha256,
  });

/** Immutable prepared model that owns metadata, Documents, pointers, and interpreters. */
export class PreparedModel {
  /** Exact id of the selected entry model. */
  readonly entryModelId: string;
  /** Deterministic identity of the preparation inputs and contract version. */
  readonly fingerprint: string;
  /** Distinct resolved dependency edges in deterministic discovery order. */
  readonly dependencies: readonly ModelDependency[];
  /** Model-aware Document decoder, encoder, and ownership boundary. */
  readonly documents: PreparedDocumentApi;
  /** Checked metadata and pointer factories for this model owner. */
  readonly model: ModelMetadata;

  /** @internal */
  constructor(private readonly core: InterpreterBridgePreparedModel) {
    this.entryModelId = core.entryModelId;
    this.fingerprint = core.fingerprint;
    this.dependencies = Object.freeze(core.dependencies.map(dependency));
    this.documents = new PreparedDocumentApi(core.documents);
    this.model = new ModelMetadata(core.model);
  }

  /** Load already-expanded DM-JSON and apply evaluator support checks. */
  static loadExpandedJson(source: string): PreparedModel {
    const result = bridge.interpreterBridgeLoadExpandedJson(source);
    if (result.failure !== null && result.failure !== undefined) {
      throwBridgeFailure(result.failure);
    }
    if (result.value === null || result.value === undefined) {
      throw new Error("interpreter bridge returned no prepared model");
    }
    return new PreparedModel(result.value);
  }

  /** Return expanded-input loading success or the same typed error without throwing. */
  static tryLoadExpandedJson(
    source: string,
  ): A12Result<PreparedModel, ModelPreparationError> {
    return resultOf(
      () => PreparedModel.loadExpandedJson(source),
      ModelPreparationError,
    );
  }

  /** Create a reusable evaluator with optional project callbacks. */
  createInterpreter(configuration?: InterpreterConfiguration): Interpreter {
    const result = this.core.createInterpreter(
      customFieldTypeRegistrations(configuration),
      customConditionRegistrations(configuration),
      evaluationInstantMillis(configuration),
    );
    if (result.failure !== null && result.failure !== undefined) {
      throwBridgeFailure(result.failure);
    }
    if (result.value === null || result.value === undefined) {
      throw new Error("interpreter bridge returned no active interpreter");
    }
    return new Interpreter(result.value);
  }
}

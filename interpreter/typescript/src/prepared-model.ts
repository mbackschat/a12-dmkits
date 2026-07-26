import type {InterpreterBridgePreparedModel} from "../internal/a12-interpreter-core.mjs";

import {PreparedDocumentApi} from "./documents.js";
import {
  customConditionRegistrations,
  customFieldTypeRegistrations,
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

export interface ModelDependency {
  readonly fromModelId: string;
  readonly toModelId: string;
  readonly purpose: "include" | "type-definitions";
  readonly alias?: string;
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
    ...(source.alias === null || source.alias === undefined
      ? {}
      : {alias: source.alias}),
    ...(source.diagnosticLabel === null || source.diagnosticLabel === undefined
      ? {}
      : {diagnosticLabel: source.diagnosticLabel}),
    sha256: source.sha256,
  });

export class PreparedModel {
  readonly entryModelId: string;
  readonly fingerprint: string;
  readonly dependencies: readonly ModelDependency[];
  readonly documents: PreparedDocumentApi;
  readonly model: ModelMetadata;

  /** @internal */
  constructor(private readonly core: InterpreterBridgePreparedModel) {
    this.entryModelId = core.entryModelId;
    this.fingerprint = core.fingerprint;
    this.dependencies = Object.freeze(core.dependencies.map(dependency));
    this.documents = new PreparedDocumentApi(core.documents);
    this.model = new ModelMetadata(core.model);
  }

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

  static tryLoadExpandedJson(
    source: string,
  ): A12Result<PreparedModel, ModelPreparationError> {
    return resultOf(
      () => PreparedModel.loadExpandedJson(source),
      ModelPreparationError,
    );
  }

  createInterpreter(configuration?: InterpreterConfiguration): Interpreter {
    const result = this.core.createInterpreter(
      customFieldTypeRegistrations(configuration),
      customConditionRegistrations(configuration),
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

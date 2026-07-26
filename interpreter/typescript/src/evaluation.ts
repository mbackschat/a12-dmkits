import type {
  InterpreterBridgeComputationReport,
  InterpreterBridgeInterpreter,
  InterpreterBridgeSupportReport,
  InterpreterBridgeValidationReport,
} from "../internal/a12-interpreter-core.mjs";

import {DocumentSnapshot} from "./documents.js";
import {throwBridgeFailure} from "./errors.js";
import {
  type FieldPointer,
  pointerFromBridge,
  relevantBridgeValue,
  type RelevantEntityPointer,
} from "./model.js";

export type ValidationSeverity = "error" | "warning" | "info";
export type ValidationFindingType = "value" | "omission";

export interface ValidationFinding {
  readonly code: string;
  readonly rulePath: string;
  readonly errorPath: string;
  readonly severity: ValidationSeverity;
  readonly type: ValidationFindingType;
  readonly text: string;
  readonly referenced: readonly string[];
  readonly fillToFix: readonly string[];
}

export interface UnsupportedProgram {
  readonly path: string;
  readonly reason: string;
}

export interface InterpreterSupportReport {
  readonly unsupported: readonly UnsupportedProgram[];
  readonly fullySupported: boolean;
}

export interface ValidationReport {
  readonly findings: readonly ValidationFinding[];
  readonly unsupported: readonly UnsupportedProgram[];
  readonly noErrorOccurred: boolean;
  readonly fullySupported: boolean;
}

export type ComputationOutcome = "value" | "cleared" | "errored";

export interface ComputationResult {
  readonly pointer: FieldPointer;
  readonly outcome: ComputationOutcome;
  readonly value?: string;
}

export interface ComputationReport {
  readonly results: readonly ComputationResult[];
  readonly unsupported: readonly UnsupportedProgram[];
  readonly fullySupported: boolean;
}

export interface ValidationOptions {
  readonly locale?: string;
}

export interface ComputeOptions {
  readonly locale?: string;
}

const unsupportedPrograms = (
  values: readonly {readonly path: string; readonly reason: string}[],
): readonly UnsupportedProgram[] =>
  Object.freeze(values.map(({path, reason}) => Object.freeze({path, reason})));

const supportReport = (
  report: InterpreterBridgeSupportReport,
): InterpreterSupportReport =>
  Object.freeze({
    unsupported: unsupportedPrograms(report.unsupported),
    fullySupported: report.fullySupported,
  });

const validationReport = (
  report: InterpreterBridgeValidationReport,
): ValidationReport =>
  Object.freeze({
    findings: Object.freeze(report.findings.map((finding) =>
      Object.freeze({
        code: finding.code,
        rulePath: finding.rulePath,
        errorPath: finding.errorPath,
        severity: finding.severity as ValidationSeverity,
        type: finding.type as ValidationFindingType,
        text: finding.text,
        referenced: Object.freeze([...finding.referenced]),
        fillToFix: Object.freeze([...finding.fillToFix]),
      }))),
    unsupported: unsupportedPrograms(report.unsupported),
    noErrorOccurred: report.noErrorOccurred,
    fullySupported: report.fullySupported,
  });

const computationReport = (
  report: InterpreterBridgeComputationReport,
): ComputationReport =>
  Object.freeze({
    results: Object.freeze(report.results.map((result) => {
      const pointer = pointerFromBridge(result.pointer);
      if (pointer.entityKind !== "field") {
        throw new Error("computation bridge returned a non-field target");
      }
      return Object.freeze({
        pointer: pointer as FieldPointer,
        outcome: result.outcome as ComputationOutcome,
        ...(result.value === null || result.value === undefined
          ? {}
          : {value: result.value}),
      });
    })),
    unsupported: unsupportedPrograms(report.unsupported),
    fullySupported: report.fullySupported,
  });

export class Interpreter {
  /** @internal */
  constructor(private readonly core: InterpreterBridgeInterpreter) {}

  supportReport(): InterpreterSupportReport {
    return supportReport(this.core.supportReport());
  }

  validateFull(
    snapshot: DocumentSnapshot,
    options?: ValidationOptions,
  ): ValidationReport {
    const result = this.core.validateFull(
      snapshot.bridgeValue(),
      options?.locale ?? "en_US",
    );
    if (result.failure !== null && result.failure !== undefined) {
      throwBridgeFailure(result.failure);
    }
    if (result.value === null || result.value === undefined) {
      throw new Error("interpreter bridge returned no validation report");
    }
    return validationReport(result.value);
  }

  validatePart(
    snapshot: DocumentSnapshot,
    relevant: readonly RelevantEntityPointer[],
    options?: ValidationOptions,
  ): ValidationReport {
    const result = this.core.validatePart(
      snapshot.bridgeValue(),
      relevant.map(relevantBridgeValue),
      options?.locale ?? "en_US",
    );
    if (result.failure !== null && result.failure !== undefined) {
      throwBridgeFailure(result.failure);
    }
    if (result.value === null || result.value === undefined) {
      throw new Error("interpreter bridge returned no validation report");
    }
    return validationReport(result.value);
  }

  compute(
    snapshot: DocumentSnapshot,
    options?: ComputeOptions,
  ): ComputationReport {
    const result = this.core.compute(
      snapshot.bridgeValue(),
      options?.locale ?? "en_US",
    );
    if (result.failure !== null && result.failure !== undefined) {
      throwBridgeFailure(result.failure);
    }
    if (result.value === null || result.value === undefined) {
      throw new Error("interpreter bridge returned no computation report");
    }
    return computationReport(result.value);
  }
}

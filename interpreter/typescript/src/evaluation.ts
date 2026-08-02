import type {
  InterpreterBridgeComputationReport,
  InterpreterBridgeInterpreter,
  InterpreterBridgeSupportReport,
  InterpreterBridgeValidationReport,
} from "../internal/a12-interpreter-core.mjs";

import {DocumentSnapshot} from "./documents.js";
import {throwBridgeFailure} from "./errors.js";
import {
  type A12PartiallyKnownMultiPointer,
  type A12Pointer,
  frozenPartiallyKnown,
} from "./pointers.js";
import {
  type FieldPointer,
  pointerFromBridge,
  relevantBridgeValue,
  type RelevantEntityPointer,
} from "./model.js";

/** Presentation severity of one validation finding. */
export type ValidationSeverity = "error" | "warning" | "info";
/** Whether a firing reports an invalid value or a required omission. */
export type ValidationFindingType = "value" | "omission";

/**
 * Detached projection of one firing validation rule.
 *
 * Its three addresses are A12's message pointer — `IMessage` types `getErrorFieldPointer()`,
 * `getReferencedFieldsPointers()` and `getRefOmissionErrorResponsiblePointers()` as
 * `PartiallyKnownDocumentMultiPointer`, and so does this. They arrive branded, so a finding's address is
 * accepted wherever an {@link A12PartiallyKnownMultiPointer} is required and rejected where an
 * {@link A12Pointer} is. Use {@link A12PartiallyKnownMultiPointer.toExactPointer} for the exact pointer when
 * the address names one cell; it answers `null` when it does not.
 */
export interface ValidationFinding {
  readonly code: string;
  readonly rulePath: string;
  readonly errorPath: A12PartiallyKnownMultiPointer;
  readonly severity: ValidationSeverity;
  readonly type: ValidationFindingType;
  readonly text: string;
  readonly referenced: readonly A12PartiallyKnownMultiPointer[];
  readonly fillToFix: readonly A12PartiallyKnownMultiPointer[];
}

/** What kind of address an {@link UnsupportedProgram.path} carries — the two are not interchangeable. */
export type UnsupportedProgramSubject = "element" | "cell";

/**
 * One construct the configured interpreter cannot evaluate — a stored rule, an implicit rule, a computation, a
 * field declaration whose custom type is unregistered, or a single document cell.
 *
 * `subject` says which of those `path` addresses: `"element"` is a model path you can look up in the model,
 * `"cell"` is an instance pointer into the document being evaluated. A support audit reports only elements;
 * validation and computation report both kinds in one list, so a consumer that ignores `subject` cannot render
 * either correctly.
 */
export interface UnsupportedProgram {
  readonly path: string;
  readonly reason: string;
  readonly subject: UnsupportedProgramSubject;
}

/** Document-independent support audit for a prepared model and callback configuration. */
export interface InterpreterSupportReport {
  readonly unsupported: readonly UnsupportedProgram[];
  readonly fullySupported: boolean;
}

/** Detached validation findings and the independent support status. */
export interface ValidationReport {
  readonly findings: readonly ValidationFinding[];
  readonly unsupported: readonly UnsupportedProgram[];
  readonly noErrorOccurred: boolean;
  readonly fullySupported: boolean;
}

/** Whether one computed field received a value, was cleared, or could not be computed. */
export type ComputationOutcome = "value" | "cleared" | "errored";

/** Detached outcome for one computed field cell. */
export interface ComputationResult {
  readonly pointer: FieldPointer;
  readonly outcome: ComputationOutcome;
  readonly value?: string;
  /** Target-owned formal diagnostic; present exactly when outcome is "errored". */
  readonly diagnostic?: ValidationFinding;
}

/** Retained computation result: report-all outcomes, source-relative actions, and support status. */
export interface ComputationReport {
  /** Every computed cell, including successful values unchanged from the computation source. */
  readonly results: readonly ComputationResult[];
  /** Source-relative VALUE/CLEARED/ERRORED actions retained for application. */
  readonly actions: readonly ComputationResult[];
  /** Eager formal diagnostics from selected computation operands. */
  readonly formalErrorsInOperands: readonly ValidationFinding[];
  readonly unsupported: readonly UnsupportedProgram[];
  /** True exactly when there are no errored targets and no formal operand errors. */
  readonly noErrorOccurred: boolean;
  readonly fullySupported: boolean;
  /** Apply the retained source actions to another snapshot owned by the same prepared model. */
  applyTo(destination: DocumentSnapshot): DocumentSnapshot;
}

/** Per-call locale for full or partial validation. */
export interface ValidationOptions {
  readonly locale?: string;
}

/** Per-call locale for computation. */
export interface ComputeOptions {
  readonly locale?: string;
}

const unsupportedPrograms = (
  values: readonly {readonly path: string; readonly reason: string; readonly subject: string}[],
): readonly UnsupportedProgram[] =>
  Object.freeze(values.map(({path, reason, subject}) =>
    Object.freeze({path, reason, subject: subject as UnsupportedProgramSubject})));

const supportReport = (
  report: InterpreterBridgeSupportReport,
): InterpreterSupportReport =>
  Object.freeze({
    unsupported: unsupportedPrograms(report.unsupported),
    fullySupported: report.fullySupported,
  });

type BridgeMessagePointer = {
  readonly fullName: string;
  readonly repetitionIndexes: readonly number[];
};

const validationFinding = (
  finding: {
    readonly code: string;
    readonly rulePath: string;
    readonly errorPath: BridgeMessagePointer;
    readonly severity: string;
    readonly type: string;
    readonly text: string;
    readonly referenced: readonly BridgeMessagePointer[];
    readonly fillToFix: readonly BridgeMessagePointer[];
  },
): ValidationFinding =>
  Object.freeze({
    code: finding.code,
    rulePath: finding.rulePath,
    errorPath: frozenPartiallyKnown(finding.errorPath),
    severity: finding.severity as ValidationSeverity,
    type: finding.type as ValidationFindingType,
    text: finding.text,
    referenced: Object.freeze(finding.referenced.map(frozenPartiallyKnown)),
    fillToFix: Object.freeze(finding.fillToFix.map(frozenPartiallyKnown)),
  });

const validationReport = (
  report: InterpreterBridgeValidationReport,
): ValidationReport =>
  Object.freeze({
    findings: Object.freeze(report.findings.map(validationFinding)),
    unsupported: unsupportedPrograms(report.unsupported),
    noErrorOccurred: report.noErrorOccurred,
    fullySupported: report.fullySupported,
  });

const computationReport = (
  report: InterpreterBridgeComputationReport,
): ComputationReport => {
  const computedFields = (
    values: typeof report.results,
  ): readonly ComputationResult[] =>
    Object.freeze(values.map((result) => {
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
        ...(result.diagnostic === null || result.diagnostic === undefined
          ? {}
          : {diagnostic: validationFinding(result.diagnostic)}),
      });
    }));
  return Object.freeze({
    results: computedFields(report.results),
    actions: computedFields(report.actions),
    formalErrorsInOperands: Object.freeze(
      report.formalErrorsInOperands.map(validationFinding),
    ),
    unsupported: unsupportedPrograms(report.unsupported),
    noErrorOccurred: report.noErrorOccurred,
    fullySupported: report.fullySupported,
    applyTo(destination: DocumentSnapshot): DocumentSnapshot {
      const result = report.applyTo(destination.bridgeValue());
      if (result.failure !== null && result.failure !== undefined) {
        throwBridgeFailure(result.failure);
      }
      if (result.value === null || result.value === undefined) {
        throw new Error("interpreter bridge returned no applied document");
      }
      return DocumentSnapshot.fromBridge(result.value);
    },
  });
};

/** Reusable evaluator bound to one prepared model and callback configuration. */
export class Interpreter {
  /** @internal */
  constructor(private readonly core: InterpreterBridgeInterpreter) {}

  /** Report unsupported rules or computations without reading a Document. */
  supportReport(): InterpreterSupportReport {
    return supportReport(this.core.supportReport());
  }

  /** Validate every applicable rule against one owned Document snapshot. */
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

  /** Validate only rules relevant to the supplied exact or partial pointers. */
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

  /** Evaluate every supported computation against one owned Document snapshot. */
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

  /** Compute once and apply that retained result back to the same immutable snapshot. */
  applyComputations(
    snapshot: DocumentSnapshot,
    options?: ComputeOptions,
  ): DocumentSnapshot {
    return this.compute(snapshot, options).applyTo(snapshot);
  }
}

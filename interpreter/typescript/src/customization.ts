import type {
  InterpreterBridgeCustomConditionInvocation,
  InterpreterBridgeCustomConditionRegistration,
  InterpreterBridgeCustomFieldTypeRegistration,
} from "../internal/a12-interpreter-core.mjs";

import {InterpreterIntegrationError} from "./errors.js";
import {bridge} from "./internal/bridge.js";
import {
  type FieldPointer,
  pointerFromBridge,
  relevantPointerFromBridge,
  type RelevantEntityPointer,
} from "./model.js";

/** Locale, declaration bounds, and value mode supplied to a custom field validator. */
export interface CustomFieldValidationParameters {
  readonly locale: string;
  readonly minLength?: number;
  readonly maxLength?: number;
  readonly isDisplayValue: boolean;
}

/** Accepted custom field text or a project-owned rejection key and optional message. */
export type CustomFieldValidationResult =
  | {readonly accepted: true}
  | {
      readonly accepted: false;
      readonly errorKey: string;
      readonly message?: string;
    };

/** Synchronous validator for one named custom field type. */
export type CustomFieldValidator = (
  value: string,
  parameters: CustomFieldValidationParameters,
) => CustomFieldValidationResult;

/** Read-only model-owned Document access available during a custom condition call. */
export interface CustomConditionDocumentView {
  value(pointer: FieldPointer): string | undefined;
  fields(): readonly FieldPointer[];
}

/** Document, relevance, formal-error, and error-pointer context for one custom condition. */
export interface CustomConditionInvocation {
  readonly document: CustomConditionDocumentView;
  readonly relevantEntities?: readonly RelevantEntityPointer[];
  readonly formallyIncorrectEntities: readonly FieldPointer[];
  readonly errorPointer: FieldPointer;
}

/** Synchronous error condition; returning true fires the owning rule. */
export type CustomCondition = (invocation: CustomConditionInvocation) => boolean;

/** Named project callbacks bound while creating an interpreter, and the instant it evaluates at. */
export interface InterpreterConfiguration {
  readonly customFieldTypes?: Readonly<Record<string, CustomFieldValidator>>;
  readonly customConditions?: Readonly<Record<string, CustomCondition>>;
  /**
   * The instant `Today` and `Now` read, as A12's `currentDateForTest`, read as wall time in the model's zone.
   * Omitted, every validation or computation call reads the current instant.
   */
  readonly evaluationInstant?: Date;
}

const isThenable = (value: unknown): value is PromiseLike<unknown> =>
  value !== null &&
  (typeof value === "object" || typeof value === "function") &&
  typeof (value as {readonly then?: unknown}).then === "function";

const requireSynchronous = <T>(value: T): T => {
  if (isThenable(value)) {
    throw new InterpreterIntegrationError(
      "evaluate",
      "thenable-returned",
      "interpreter customization callbacks must return synchronously",
    );
  }
  return value;
};

const fieldPointer = (
  core: Parameters<typeof pointerFromBridge>[0],
): FieldPointer => {
  const pointer = pointerFromBridge(core);
  if (pointer.entityKind !== "field") {
    throw new Error("customization bridge returned a non-field pointer");
  }
  return pointer as FieldPointer;
};

const conditionInvocation = (
  core: InterpreterBridgeCustomConditionInvocation,
): CustomConditionInvocation => {
  const document: CustomConditionDocumentView = Object.freeze({
    value(pointer: FieldPointer): string | undefined {
      return core.value(pointer.bridgeValue()) ?? undefined;
    },
    fields(): readonly FieldPointer[] {
      return Object.freeze(core.fields().map(fieldPointer));
    },
  });
  return Object.freeze({
    document,
    ...(core.relevantEntities === null || core.relevantEntities === undefined
      ? {}
      : {
          relevantEntities: Object.freeze(
            core.relevantEntities.map(relevantPointerFromBridge),
          ),
        }),
    formallyIncorrectEntities: Object.freeze(
      core.formallyIncorrectEntities.map(fieldPointer),
    ),
    errorPointer: fieldPointer(core.errorPointer),
  });
};

const requireName = (name: string): string => {
  if (name.length === 0) {
    throw new TypeError("interpreter customization names must not be empty");
  }
  return name;
};

/** @internal */
export const customFieldTypeRegistrations = (
  configuration: InterpreterConfiguration | undefined,
): InterpreterBridgeCustomFieldTypeRegistration[] =>
  Object.entries(configuration?.customFieldTypes ?? {}).map(
    ([name, validator]) =>
      new bridge.InterpreterBridgeCustomFieldTypeRegistration(
        requireName(name),
        (value, locale, minLength, maxLength, isDisplayValue) => {
          const result = requireSynchronous(validator(
            value,
            Object.freeze({
              locale,
              ...(minLength === null || minLength === undefined
                ? {}
                : {minLength}),
              ...(maxLength === null || maxLength === undefined
                ? {}
                : {maxLength}),
              isDisplayValue,
            }),
          ));
          return result.accepted
            ? new bridge.InterpreterBridgeCustomFieldValidation(true, undefined, undefined)
            : new bridge.InterpreterBridgeCustomFieldValidation(
                false,
                result.errorKey,
                result.message,
              );
        },
      ),
  );

/** @internal */
export const customConditionRegistrations = (
  configuration: InterpreterConfiguration | undefined,
): InterpreterBridgeCustomConditionRegistration[] =>
  Object.entries(configuration?.customConditions ?? {}).map(
    ([name, condition]) =>
      new bridge.InterpreterBridgeCustomConditionRegistration(
        requireName(name),
        (invocation) =>
          requireSynchronous(condition(conditionInvocation(invocation))),
      ),
  );

/** @internal */
export const evaluationInstantMillis = (
  configuration: InterpreterConfiguration | undefined,
): number | null => {
  const instant = configuration?.evaluationInstant;
  if (instant === undefined) {
    return null;
  }
  const millis = instant instanceof Date ? instant.getTime() : Number.NaN;
  if (!Number.isFinite(millis)) {
    throw new TypeError("evaluationInstant must be a valid Date");
  }
  return millis;
};

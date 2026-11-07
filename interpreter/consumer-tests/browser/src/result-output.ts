import {
  type A12PartiallyKnownMultiPointer,
  A12PartiallyKnownMultiPointer as A12MessagePointer,
  A12PointerFormat,
  type ComputationReport,
  type ValidationFinding,
  type ValidationReport,
} from "@mbackschat/a12-interpreter";

/**
 * One message address as the showcase publishes it: the pointer VALUE a finding carries, plus its canonical A12
 * spelling when it names exactly one cell.
 *
 * Both halves are shown for the same reason the computation results show both: the value is what the API hands
 * over and the string is what a user can paste back into A12. `a12Pointer` is `null` when the address carries a
 * wildcard above the last level or an unresolved one — A12 defines no pointer string for that, and a demo must
 * not invent one. A possibly-causing entity at a row no key matched also carries that key, which is what tells two
 * such members of one group apart.
 */
const messageAddressOutput = (pointer: A12PartiallyKnownMultiPointer) => {
  const exact = A12MessagePointer.toExactPointer(pointer);
  return {
    a12Pointer: exact === null
      ? null
      : A12PointerFormat.format(exact.fullName, exact.repetitionIndexes),
    fullName: pointer.fullName,
    repetitionIndexes: pointer.repetitionIndexes,
    ...(pointer.semanticIndexValueForUnknownIndexGroup === undefined
      ? {}
      : {semanticIndexValueForUnknownIndexGroup: pointer.semanticIndexValueForUnknownIndexGroup}),
  };
};

const findingOutput = (finding: ValidationFinding) => ({
  ...finding,
  errorPath: messageAddressOutput(finding.errorPath),
  referenced: finding.referenced.map(messageAddressOutput),
  fillToFix: finding.fillToFix.map(messageAddressOutput),
  possiblyCausing: finding.possiblyCausing.map((group) => group.map(messageAddressOutput)),
});

export const validationOutput = (report: ValidationReport) => ({
  ...report,
  findings: report.findings.map(findingOutput),
});

const computationResultsOutput = (
  entries: ComputationReport["results"],
) =>
  entries.map((entry) => ({
    // A repeated computed target produces several results whose `path` is the SAME string; only the
    // coordinates tell the rows apart. Joining them into one canonical A12 pointer is what makes the
    // list readable, so the showcase publishes both: the raw address and its rendered form.
    a12Pointer: A12PointerFormat.format(
      entry.pointer.path,
      entry.pointer.coordinates,
    ),
    pointer: {
      path: entry.pointer.path,
      coordinates: entry.pointer.coordinates,
    },
    outcome: entry.outcome,
    value: entry.value,
    diagnostic: entry.diagnostic === undefined ? undefined : findingOutput(entry.diagnostic),
  }));

export const computationOutput = (report: ComputationReport) => ({
  results: computationResultsOutput(report.results),
  actions: computationResultsOutput(report.actions),
  formalErrorsInOperands: report.formalErrorsInOperands.map(findingOutput),
  noErrorOccurred: report.noErrorOccurred,
  unsupported: report.unsupported,
  fullySupported: report.fullySupported,
});

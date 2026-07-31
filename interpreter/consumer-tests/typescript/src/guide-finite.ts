// tag::interpreter-typescript-start[]
import {
  type DocumentSnapshot,
  type Interpreter,
  ModelWorkspace,
  type A12Document,
} from "@mbackschat/a12-interpreter";

export function runInterpreter(rawModelJson: string) {
  const workspace = ModelWorkspace.fromSources([{json: rawModelJson}]);
  const prepared = workspace.prepare("permit-basic");
  const starter = prepared.documents.encodeDocument(
    prepared.documents.seedDocument(),
  );
  const input: A12Document = {
    Permit: {ApplicationNo: "P-42", RequestedArea: 120},
  };
  const document = prepared.documents.readDocument(input);
  const interpreter = prepared.createInterpreter();
  const relevant = prepared.model.relevantPointer(
    "/Permit/RequestedArea",
    [1, 1],
  );

  return {
    full: interpreter.validateFull(document),
    partial: interpreter.validatePart(document, [relevant]),
    computation: interpreter.compute(document),
    starter,
    document,
    prepared,
  };
}
// end::interpreter-typescript-start[]

// tag::interpreter-typescript-form-pass[]
export function computeApplyValidate(
  interpreter: Interpreter,
  document: DocumentSnapshot,
) {
  const computation = interpreter.compute(document);
  const applied = computation.applyTo(document);
  const validation = interpreter.validateFull(applied);
  return {computation, applied, validation};
}
// end::interpreter-typescript-form-pass[]

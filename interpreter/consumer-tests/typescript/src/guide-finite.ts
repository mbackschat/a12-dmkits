// tag::interpreter-typescript-start[]
import {
  ModelWorkspace,
  type DocumentInputV1,
} from "@mbackschat/a12-interpreter";

export function runInterpreter(rawModelJson: string) {
  const workspace = ModelWorkspace.fromSources([{json: rawModelJson}]);
  const prepared = workspace.prepare("permit-basic");
  const input: DocumentInputV1 = {
    version: 1,
    groups: [{path: "/Permit", coordinates: [1]}],
    fields: [
      {path: "/Permit/ApplicationNo", coordinates: [1, 1], raw: "P-42"},
      {path: "/Permit/RequestedArea", coordinates: [1, 1], raw: "120"},
    ],
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
    document,
    prepared,
  };
}
// end::interpreter-typescript-start[]

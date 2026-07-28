// tag::interpreter-typescript-customization[]
import {
  PreparedModel,
  type A12Document,
} from "@mbackschat/a12-interpreter";

export function validateWithProjectCondition(
  expandedModelJson: string,
  input: A12Document,
) {
  const prepared = PreparedModel.loadExpandedJson(expandedModelJson);
  const document = prepared.documents.readDocument(input);
  const interpreter = prepared.createInterpreter({
    customConditions: {
      ExternalEligibility({document: view, errorPointer}) {
        return view.value(errorPointer) === "ACME";
      },
    },
  });
  return interpreter.validateFull(document);
}
// end::interpreter-typescript-customization[]

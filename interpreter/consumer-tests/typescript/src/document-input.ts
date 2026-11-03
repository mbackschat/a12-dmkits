import {DocumentInputError, type PreparedModel} from "@mbackschat/a12-interpreter";

/** How Document reading converts a host value: what it discards, what it refuses, and the refusal's address. */
export function assertDocumentInputConversions(prepared: PreparedModel): void {
  const unknownKey = prepared.documents.tryReadDocument(
    '{"Permit":[{"ApplicationNo":"P-2","Unexpected":"discarded"}]}',
  );
  if (!unknownKey.ok || prepared.documents.encodeDocumentJson(unknownKey.value) !== '{"Permit":{"ApplicationNo":"P-2"}}') {
    throw new Error("unknown Document key was not discarded by canonical conversion");
  }

  // Kernel 31.1.1 reads no array holding a non-object (KERNEL-FINDINGS KF664).
  const strayElement = prepared.documents.tryReadDocument('{"Permit":[null,{"ApplicationNo":"P-2"}]}');
  if (strayElement.ok || strayElement.error.diagnostics[0]?.reasonCode !== "malformed-json") {
    throw new Error("a non-object repetition element did not refuse the Document");
  }

  try {
    prepared.documents.readDocument({Permit: {RequestedArea: Number.NaN}});
    throw new Error("Document reading accepted a value JSON cannot carry");
  } catch (error) {
    if (!(error instanceof DocumentInputError) ||
        error.diagnostics[0]?.reasonCode !== "unrepresentable-native-value" ||
        error.diagnostics[0]?.inputPath?.join("/") !== "Permit/RequestedArea") {
      throw error;
    }
  }
}

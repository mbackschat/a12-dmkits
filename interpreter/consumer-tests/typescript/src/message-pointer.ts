import {A12PartiallyKnownMultiPointer, A12PointerFormat} from "@mbackschat/a12-interpreter";

/** What the two pointer types admit: A12's reserved repetitions, and which domain each one belongs to. */
export function assertMessagePointerDomains(): void {
  if (A12PointerFormat.LAST_PART_ZERO !== 0 ||
      A12PartiallyKnownMultiPointer.WILDCARD !== 0 ||
      A12PartiallyKnownMultiPointer.UNKNOWN !== -5 ||
      A12PartiallyKnownMultiPointer.PARALLEL_ITERATION !== -6) {
    throw new Error("the pointer types do not publish A12's reserved repetitions");
  }
  // A12's runtime leaves its parallel-iteration index on some message references, so a reader must hold it.
  const parallel = A12PartiallyKnownMultiPointer.of("/Order/Lines/Slots/SlotNo", [1, 1, -6, 1]);
  if (parallel.repetitionIndexes.join(",") !== "1,1,-6,1" ||
      A12PartiallyKnownMultiPointer.toExactPointer(parallel) !== null) {
    throw new Error("a message pointer with A12's parallel-iteration index did not read as unresolved");
  }
  // The two domains really are different: the unknown index is a message value and nothing else.
  const unresolved = A12PartiallyKnownMultiPointer.of("/Plan/Demand/Headcount", [1, -5, 1]);
  if (unresolved.repetitionIndexes.join(",") !== "1,-5,1" ||
      A12PartiallyKnownMultiPointer.toExactPointer(unresolved) !== null) {
    throw new Error("a message pointer with A12's unknown index did not read as unresolved");
  }
  try {
    A12PointerFormat.parse("Plan/Demand[-5]/Headcount");
    throw new Error("the exact pointer type accepted A12's unknown repetition");
  } catch (error) {
    if (!(error instanceof TypeError)) {
      throw error;
    }
  }
  try {
    A12PointerFormat.parse("Permit/SiteSections[0]/Area");
    throw new Error("the exact pointer accepted a non-final zero repetition");
  } catch (error) {
    if (!(error instanceof TypeError)) {
      throw error;
    }
  }
  // …while the message channel's wildcard IS legal at every part.
  if (A12PartiallyKnownMultiPointer.of("/Permit/SiteSections/Area", [1, 0, 1])
        .repetitionIndexes.join(",") !== "1,0,1") {
    throw new Error("a message pointer refused the wildcard A12 allows at every part");
  }
  // The ROOT conversion raises, exactly as A12's own toDocumentPointer() does.
  try {
    A12PartiallyKnownMultiPointer.toExactPointer(A12PartiallyKnownMultiPointer.of("", []));
    throw new Error("the root conversion answered where A12 raises");
  } catch (error) {
    if (!(error instanceof TypeError)) {
      throw error;
    }
  }
  // A12's PathPart accepts any non-empty name; only the string CODEC restricts what it will write.
  if (A12PartiallyKnownMultiPointer.of("/Order$/Order Item", [1, 2]).fullName !== "/Order$/Order Item") {
    throw new Error("the message pointer refused a fullName its owner accepts");
  }
}

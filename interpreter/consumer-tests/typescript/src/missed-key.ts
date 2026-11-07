import {PreparedModel} from "@mbackschat/a12-interpreter";

/**
 * A possibly-causing entity at a row no key selected carries that key, and every other pointer carries none
 * (KERNEL-FINDINGS KF683). The order model gains a rule reading an item by a SKU the document does not hold.
 */
export function assertMissedKeyCrosses(orderModelJson: string): void {
  const order = JSON.parse(orderModelJson);
  order.content.modelRoot.rootGroups[0].Group.elements.push({
    type: "Rule",
    id: "/Order/BackorderedSku",
    name: "BackorderedSku",
    Rule: {
      errorEntityRelPath: "../CustomerName",
      errorCode: "BACKORDERED_SKU",
      errorCondition: "[Items/Count For \"SKU-9\"] > 1 Or FieldNotFilled(CustomerName)",
      severity: "ERROR",
      errorMessage: [{locale: "en_US", text: "A backordered item needs a customer."}],
    },
  });
  const prepared = PreparedModel.loadExpandedJson(JSON.stringify(order));
  const [finding] = prepared.createInterpreter({customConditions: {ExternalEligibility: () => false}})
    .validateFull(prepared.documents.readDocument({Order: {Items: [{Sku: "SKU-1", Count: 5}]}}))
    .findings.filter(({code}) => code === "BACKORDERED_SKU");
  if (finding === undefined) {
    throw new Error("the backordered-SKU rule must fire");
  }
  const causes = finding.possiblyCausing.flat();
  const keyed = causes.filter((pointer) => pointer.semanticIndexValueForUnknownIndexGroup !== undefined);
  if (keyed.length === 0 || keyed.some((pointer) => pointer.semanticIndexValueForUnknownIndexGroup !== "SKU-9")) {
    throw new Error(`the item read by its missing SKU must carry that SKU: ${JSON.stringify(causes)}`);
  }
  const unkeyed = [finding.errorPath, ...finding.referenced, ...finding.fillToFix, ...causes.filter((p) => !keyed.includes(p))];
  if (unkeyed.some((pointer) => "semanticIndexValueForUnknownIndexGroup" in pointer)) {
    throw new Error(`only a missed row's cause carries a key: ${JSON.stringify(unkeyed)}`);
  }
}

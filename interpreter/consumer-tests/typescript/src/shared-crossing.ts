import {PreparedModel} from "@mbackschat/a12-interpreter";

/**
 * A duplicate cluster's findings publish one shared `referenced` array and one shared cause group, as the engine
 * shares them, so the packed package allocates them once rather than once per finding (INTERPRETER-FINDINGS IF256).
 * The order model gains a uniqueness rule over its items' counts, and four items with one count form the cluster.
 */
export function assertClusterCrossesOnce(orderModelJson: string): void {
  const order = JSON.parse(orderModelJson);
  order.content.modelRoot.rootGroups[0].Group.elements.push({
    type: "Rule",
    id: "/Order/DistinctCounts",
    name: "DistinctCounts",
    Rule: {
      errorEntityRelPath: "../Items/Count",
      errorCode: "DISTINCT_COUNTS",
      errorCondition: "RepetitionNotUnique(Items/Count)",
      severity: "ERROR",
      errorMessage: [{locale: "en_US", text: "Each item needs its own count."}],
    },
  });
  const prepared = PreparedModel.loadExpandedJson(JSON.stringify(order));
  const items = ["SKU-1", "SKU-2", "SKU-3", "SKU-4"].map((Sku) => ({Sku, Count: 5}));
  const findings = prepared.createInterpreter({customConditions: {ExternalEligibility: () => false}})
    .validateFull(prepared.documents.readDocument({Order: {Items: items}}))
    .findings.filter(({code}) => code === "DISTINCT_COUNTS");
  const [first] = findings;
  if (first === undefined || findings.length !== 4) {
    throw new Error(`the duplicate cluster must fire once per item, got ${findings.length}`);
  }
  if (findings.some((finding) => finding.referenced !== first.referenced ||
      finding.possiblyCausing[0] !== first.possiblyCausing[0])) {
    throw new Error("a duplicate cluster's shared pointers were copied per finding");
  }
}

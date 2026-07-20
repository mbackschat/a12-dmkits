package io.github.mbackschat.a12.dm.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * {@link ModelIndex#computations()} — the kernel-free enumeration of a model's authored computations with
 * their <b>resolved absolute target field paths</b>. A {@code computedFieldRelPath} is relative to the
 * computation <em>element</em> (a sibling field in the same group is {@code "../Field"} — the form
 * {@code dm.rulekit}'s {@code Computation.computedFieldRelPath()} derives); {@code ..} pops one level,
 * a plain segment descends. Consumers that must reason about computation targets without the kernel
 * (the capture scenario validator's v1 non-repeatable-target gate) read this instead of raw DM-JSON.
 */
class ModelIndexComputationsTest {

    private static final String MODEL = """
            {
              "header": { "id": "claim-payout", "modelType": "document", "modelVersion": "28.4.0",
                          "locales": [ { "code": "en_US" } ] },
              "content": {
                "modelInfo": { "name": "claim-payout" },
                "modelRoot": {
                  "rootGroups": [
                    {
                      "type": "Group", "id": "/Claim", "name": "Claim",
                      "Group": {
                        "repeatability": 1,
                        "elements": [
                          { "type": "Field", "id": "/Claim/Damage", "name": "Damage",
                            "Field": { "fieldType": { "type": "NumberType",
                                                      "NumberType": { "maxFractionalDigits": 2 } } } },
                          { "type": "Field", "id": "/Claim/Payout", "name": "Payout",
                            "Field": { "fieldType": { "type": "NumberType",
                                                      "NumberType": { "maxFractionalDigits": 2 } } } },
                          { "type": "Computation", "id": "/Claim/PayoutCalc", "name": "PayoutCalc",
                            "Computation": {
                              "computedFieldRelPath": "../Payout",
                              "computationAlternatives": [ { "operation": "[Damage] * 2" } ],
                              "errorMessage": [ { "locale": "en_US", "text": "x" } ] } },
                          { "type": "Group", "id": "/Claim/Repairs", "name": "Repairs",
                            "Group": {
                              "repeatability": 10,
                              "elements": [
                                { "type": "Field", "id": "/Claim/Repairs/Hours", "name": "Hours",
                                  "Field": { "fieldType": { "type": "NumberType",
                                                            "NumberType": { "maxFractionalDigits": 0 } } } },
                                { "type": "Field", "id": "/Claim/Repairs/Cost", "name": "Cost",
                                  "Field": { "fieldType": { "type": "NumberType",
                                                            "NumberType": { "maxFractionalDigits": 2 } } } },
                                { "type": "Computation", "id": "/Claim/Repairs/CostCalc", "name": "CostCalc",
                                  "Computation": {
                                    "computedFieldRelPath": "../Cost",
                                    "computationAlternatives": [ { "operation": "[Hours] * 80" } ],
                                    "errorMessage": [ { "locale": "en_US", "text": "x" } ] } }
                              ] } }
                        ] } } ] } } }
            """;

    private static final String MODEL_WITHOUT_COMPUTATIONS = """
            {
              "header": { "id": "claim-flat", "modelType": "document", "modelVersion": "28.4.0",
                          "locales": [ { "code": "en_US" } ] },
              "content": {
                "modelInfo": { "name": "claim-flat" },
                "modelRoot": {
                  "rootGroups": [
                    { "type": "Group", "id": "/Claim", "name": "Claim",
                      "Group": {
                        "repeatability": 1,
                        "elements": [
                          { "type": "Field", "id": "/Claim/Damage", "name": "Damage",
                            "Field": { "fieldType": { "type": "StringType" } } }
                        ] } } ] } } }
            """;

    @Test
    void enumeratesComputationsWithResolvedAbsoluteTargets() {
        String dmJson = MODEL;
        // tag::model-computations[]
        // every authored computation, its computedFieldRelPath RESOLVED to the absolute target field:
        List<ComputationInfo> computations = ModelIndex.ofSelfContained(dmJson).computations();
        ComputationInfo payout = computations.get(0);
        payout.path();              // "/Claim/PayoutCalc"  — the computation element's name-path
        payout.groupPath();         // "/Claim"             — its containing group
        payout.targetFieldPath();   // "/Claim/Payout"      — "../Payout" resolved (rel base = the element)
        String resolved = ModelIndex.resolvePath(payout.path(), "../Payout"); // shared rule/computation anchor resolver
        // end::model-computations[]
        assertEquals(2, computations.size());
        assertEquals("/Claim/PayoutCalc", payout.path());
        assertEquals("/Claim", payout.groupPath());
        assertEquals("PayoutCalc", payout.name());
        assertEquals("/Claim/Payout", payout.targetFieldPath());
        assertEquals("/Claim/Payout", resolved);

        ComputationInfo cost = computations.get(1);
        assertEquals("/Claim/Repairs/CostCalc", cost.path());
        assertEquals("/Claim/Repairs", cost.groupPath());
        assertEquals("/Claim/Repairs/Cost", cost.targetFieldPath());
    }

    @Test
    void parentStepsPopFromTheComputationElement() {
        // "../../Payout" from the element /Claim/Repairs/CostCalc: pop → /Claim/Repairs, pop → /Claim,
        // descend → /Claim/Payout (the rel-path base is the computation ELEMENT, not its group).
        ModelIndex index = ModelIndex.ofSelfContained(MODEL.replace("\"computedFieldRelPath\": \"../Cost\"",
                "\"computedFieldRelPath\": \"../../Payout\""));
        ComputationInfo crossGroup = index.computations().get(1);
        assertEquals("/Claim/Payout", crossGroup.targetFieldPath());
    }

    @Test
    void modelWithoutComputationsYieldsEmptyList() {
        assertTrue(ModelIndex.ofSelfContained(MODEL_WITHOUT_COMPUTATIONS).computations().isEmpty());
    }
}

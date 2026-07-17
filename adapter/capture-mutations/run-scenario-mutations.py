#!/usr/bin/env python3
"""The string-direct-cascade-v1 SCENARIO-SEMANTIC mutation harness (CONFORMANCE-CORPUS-SPEC §15f's
deferred map; the external client's Prompt-2 dispatch).

The boundary harness (run-mutations.py) proves the capture MACHINERY; this one proves the five-case
separating matrix OBSERVES the scenario semantics: each mutation corrupts one semantic aspect in capture
or interpreter source, the REAL `capture --development` runs the exact external scenario, and the REAL
`diff-packets` against a same-revision reference development packet must report EXACTLY the predicted
per-case/per-runner observation drift (plus the inevitable PACKET.json provenance drift from the dirty
tree). Predictions are recorded per mutation BEFORE it executes; source bytes are restored exactly
(pre/post classified tree digests); mutant packets are disposable and NEVER see portable qualification.
Finishes with the natural gate green and writes the scenario receipt beside this script.

Run from the repo root on a CLEAN committed tree:
    python3 adapter/capture-mutations/run-scenario-mutations.py
The external scenario root is resolved from $CASCADE_SCENARIO_JSON or the sibling checkout
`../a12-kernel-lean/evidence/scenarios/string-direct-cascade-v1/scenarios.json`; its bytes (and the
model's) must match the pinned request identity — the scenario stays external and unmodified.

The receipt is the Set Z4 SUCCESSOR (`capture-scenario-mutation-receipt-v2`): it binds the FROZEN V1
capabilities digest (refusing to run on any other) and explicitly references its rejected Set Z3
predecessor receipt, which stays committed untouched as version-identity history — this harness never
writes to the predecessor's path.
"""

import hashlib
import importlib.util
import json
import sys
from pathlib import Path

_HERE = Path(__file__).resolve().parent
_spec = importlib.util.spec_from_file_location("run_mutations", _HERE / "run-mutations.py")
_rm = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_rm)

REPO = _rm.REPO
RECEIPT = _HERE / "scenario-mutation-receipt-string-direct-cascade-v1-r2.json"
SCRATCH = REPO / "build" / "capture-scenario-mutations"

SCENARIO_SHA256 = "4c5d4911ecacde819618b3b921b0bd30aa34b514cb0bbf3f0d2b735f21a0fd43"
MODEL_SHA256 = "3d21add02d259a8d1ad2e14475582513aec2f4e60176f1c02c81d40de88a895d"
KERNEL_BEHAVIOR_VERSION = "30.8.1"
PROJECTION = "compute-projection-kernel-route-v1"

# The FROZEN V1 capability identity (CONFORMANCE-CORPUS-SPEC §15a) — the successor receipt binds it and
# the harness refuses to run against any other capabilities bytes.
FROZEN_CAPABILITIES_SHA256 = "b87d381e7f43446bc886292766e34beab72ca3ec179be5f0f614975e66d603ca"

# The rejected Set Z3 predecessor (version-identity rejection): committed, never rewritten, referenced.
PREDECESSOR = _HERE / "scenario-mutation-receipt-string-direct-cascade-v1.json"
PREDECESSOR_SHA256 = "045aa25e27a82bf97c83bccaa89ffa3052a8a429bd4f9f96747f67f80c7addbc"

CAPTURE = "adapter/src/capture/java/io/github/mbackschat/a12/dm/adapter/capture"
ENGINE = "interpreter/src/commonMain/kotlin/io/github/mbackschat/a12/dm/interpreter/ComputationEngine.kt"
GROOVY = "kernel-groovy-dynamic"
STATIC = "kernel-java-static"
INTERP = "a12-dmkits-interpreter"
KERNEL_ROUTES = [GROOVY, STATIC]


def scenario_file():
    import os
    override = os.environ.get("CASCADE_SCENARIO_JSON")
    path = Path(override) if override else REPO.parent / "a12-kernel-lean/evidence/scenarios/string-direct-cascade-v1/scenarios.json"
    return path.resolve()


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


# ---- structured field assertions over a mutant packet's observations ----------------------------------

def obs(packet, case, runner):
    return json.loads((packet / "observations" / case / f"{runner}.json").read_text())


def targets(observation, channel):
    return [e["target"] for e in observation["channels"][channel]["entries"]]


def rendered(observation, channel, target):
    for e in observation["channels"][channel]["entries"]:
        if e["target"] == target:
            return e["value"]["rendered"].get("value")
    return None


def applied(observation, pointer):
    for e in observation["channels"]["appliedState"]["entries"]:
        if e["pointer"] == pointer:
            return e
    return None


def cause(observation):
    return observation["channels"]["withErrors"]["entries"][0]["cause"]


def attempted(observation):
    return observation["channels"]["withErrors"]["entries"][0]["attempted"]


MID = "/Cascade[1]/Mid"
OUT = "/Cascade[1]/Out"


def check_s01(p):
    return all(targets(obs(p, "source-abc-mid-abc", r), "withoutErrors") == [OUT] for r in KERNEL_ROUTES)


def check_s02(p):
    return all(MID in targets(obs(p, "source-abc-mid-abc", r), "changedSubset") for r in KERNEL_ROUTES)


def check_s03(p):
    o = obs(p, "source-absent-mid-old", INTERP)
    return rendered(o, "withoutErrors", OUT) == "OLD-X" \
        and applied(o, OUT)["value"]["rendered"]["value"] == "OLD-X"


def check_s04(p):
    o = obs(p, "source-abcd-mid-old", INTERP)
    return rendered(o, "withoutErrors", OUT) == "ABCD-X" \
        and targets(o, "cleared") == [] \
        and applied(o, OUT)["value"]["rendered"]["value"] == "ABCD-X"


def check_s05(p):
    return all(attempted(obs(p, "source-abcd-mid-old", r))["typed"]["availability"] != "available"
               and attempted(obs(p, "source-abcd-mid-old", r))["rendered"]["availability"] != "available"
               for r in KERNEL_ROUTES)


def check_s06(p):
    return all(cause(obs(p, "source-abcd-mid-old", r))["code"] == "reconstructed" for r in KERNEL_ROUTES)


def check_s07(p):
    return all(cause(obs(p, "source-abcd-mid-old", r))["messageType"] == "INFO" for r in KERNEL_ROUTES)


def check_s08(p):
    return all(cause(obs(p, "source-abcd-mid-old", r))["errorPointer"] == "/Cascade/Mid" for r in KERNEL_ROUTES)


def check_s09(p):
    return all(applied(obs(p, "source-absent-mid-old", r), MID)["state"] == "absent"
               and applied(obs(p, "source-abcd-mid-old", r), MID)["state"] == "absent"
               and applied(obs(p, "source-abcd-mid-old", r), OUT)["state"] == "absent"
               for r in KERNEL_ROUTES)


# ---- the scenario-semantic mutations (prediction recorded before execution) ----------------------------
#
# Every prediction names the EXACT observation artifacts (caseId x runner) that must drift and the
# field-level shape of the drift; PACKET.json provenance drift is inevitable (dirty tree + closure digest)
# and predicted for every mutation. Anything beyond the prediction fails the harness.

def drift(cases_runners):
    return sorted(f"observations/{c}/{r}.json" for c, r in cases_runners)


MUTATIONS = [
    {
        "id": "S01-accepted-equal-vanishes-from-all-clean",
        "description": "the kernel mapper reads the CHANGED collection as withoutErrors — an accepted-equal"
                       " computation (Mid re-computing its own prior value) vanishes from the all-clean channel",
        "replaces": [(f"{CAPTURE}/runner/kernel/KernelRouteSupport.java",
                      "        for (IComputedFieldInstance c : result.getComputedFieldInstancesWithoutErrors()) {",
                      "        for (IComputedFieldInstance c : result.getComputedFieldInstancesWithChanges()) {")],
        "observationDrift": drift([("source-abc-mid-abc", r) for r in KERNEL_ROUTES]),
        "fieldAssert": "withoutErrors targets == [Out] (Mid gone) on both kernel routes in source-abc-mid-abc",
        "check": check_s01,
    },
    {
        "id": "S02-accepted-equal-enters-changed-subset",
        "description": "the kernel mapper reads the all-clean collection as changedSubset — the accepted-equal"
                       " Mid incorrectly enters the changed subset",
        "replaces": [(f"{CAPTURE}/runner/kernel/KernelRouteSupport.java",
                      "        for (IComputedFieldInstance c : result.getComputedFieldInstancesWithChanges()) {",
                      "        for (IComputedFieldInstance c : result.getComputedFieldInstancesWithoutErrors()) {")],
        "observationDrift": drift([("source-abc-mid-abc", r) for r in KERNEL_ROUTES]),
        "fieldAssert": "changedSubset gains Mid on both kernel routes in source-abc-mid-abc",
        "check": check_s02,
    },
    {
        "id": "S03-source-absent-stale-cascade",
        "description": "the interpreter's cascade stops recording a CLEARED dependency — the dependent reads"
                       " the stale stored Mid and Out computes \"OLD-X\" instead of the observed \"-X\"",
        "replaces": [(ENGINE,
                      "                            ComputeOutcome.CLEARED -> cleared += targetKey",
                      "                            ComputeOutcome.CLEARED -> {}")],
        "observationDrift": drift([("source-absent-mid-old", INTERP)]),
        "fieldAssert": "interpreter source-absent-mid-old: Out == \"OLD-X\" in withoutErrors and applied state",
        "check": check_s03,
    },
    {
        "id": "S04-rejected-value-reaches-dependent",
        "description": "the interpreter's poison mechanism dies (ERRORED overlays its attempted value, the"
                       " poisoning read gate never fires) — the rejected attempted \"ABCD\" reaches Out",
        "replaces": [(ENGINE,
                      "                            ComputeOutcome.ERRORED -> { poisoned += targetKey; cleared -= targetKey; decided += targetKey }",
                      "                            ComputeOutcome.ERRORED -> { overlay[targetKey] = v; cleared -= targetKey; decided += targetKey }"),
                     (ENGINE,
                      "                    invalid = { path -> if (operandNotCheckRelevant(path, ectx)) throw PoisonedReadException(path) else false })",
                      "                    invalid = { _ -> false })")],
        "observationDrift": drift([("source-abcd-mid-old", INTERP)]),
        "fieldAssert": "interpreter source-abcd-mid-old: Out == \"ABCD-X\" (no longer cleared), applied \"ABCD-X\"",
        "check": check_s04,
    },
    {
        "id": "S05-attempted-value-dropped",
        "description": "the kernel mapper drops the errored instance's attempted value from transport",
        "replaces": [(f"{CAPTURE}/runner/kernel/KernelRouteSupport.java",
                      "                    execution.declaredComputationOf(pointerOf(c)), observedValue(c), causeOf(c)));",
                      "                    execution.declaredComputationOf(pointerOf(c)),\n"
                      "                    new ObservedValue(Availability.NOT_APPLICABLE, null, Availability.NOT_APPLICABLE, null),\n"
                      "                    causeOf(c)));")],
        "observationDrift": drift([("source-abcd-mid-old", r) for r in KERNEL_ROUTES]),
        "fieldAssert": "withErrors attempted value unavailable on both kernel routes in source-abcd-mid-old",
        "check": check_s05,
    },
    {
        "id": "S06-cause-code-altered",
        "description": "the kernel mapper rewrites the structured cause CODE (stringZuLang is reconstructed)",
        "replaces": [(f"{CAPTURE}/runner/kernel/KernelRouteSupport.java",
                      "                .map(m -> ComputeObservation.CauseObservation.of(m.getErrorCode(), m.getMessageType().name(),",
                      "                .map(m -> ComputeObservation.CauseObservation.of(\"reconstructed\", m.getMessageType().name(),")],
        "observationDrift": drift([("source-abcd-mid-old", r) for r in KERNEL_ROUTES]),
        "fieldAssert": "cause code == \"reconstructed\" (not stringZuLang) on both kernel routes",
        "check": check_s06,
    },
    {
        "id": "S07-cause-message-type-altered",
        "description": "the kernel mapper rewrites the structured cause MESSAGE TYPE (VALUE_ERROR is lost)",
        "replaces": [(f"{CAPTURE}/runner/kernel/KernelRouteSupport.java",
                      "                .map(m -> ComputeObservation.CauseObservation.of(m.getErrorCode(), m.getMessageType().name(),",
                      "                .map(m -> ComputeObservation.CauseObservation.of(m.getErrorCode(), \"INFO\",")],
        "observationDrift": drift([("source-abcd-mid-old", r) for r in KERNEL_ROUTES]),
        "fieldAssert": "cause messageType == \"INFO\" (not VALUE_ERROR) on both kernel routes",
        "check": check_s07,
    },
    {
        "id": "S08-cause-pointer-altered",
        "description": "the kernel mapper drops the cause pointer's repetition indices (/Cascade[1]/Mid"
                       " degrades to /Cascade/Mid)",
        "replaces": [(f"{CAPTURE}/runner/kernel/KernelRouteSupport.java",
                      "                                m.getErrorFieldPointer().repetitionIndexes())))",
                      "                                java.util.List.<Integer>of())))")],
        "observationDrift": drift([("source-abcd-mid-old", r) for r in KERNEL_ROUTES]),
        "fieldAssert": "cause errorPointer == \"/Cascade/Mid\" (indices dropped) on both kernel routes",
        "check": check_s08,
    },
    {
        "id": "S09-absent-present-empty-collapsed",
        "description": "the kernel applied-state read-back collapses present-empty into absent — the"
                       " target-error and stale-clear cases lose the distinction the matrix separates",
        "replaces": [(f"{CAPTURE}/runner/kernel/KernelRouteSupport.java",
                      "            return new AppliedState.PresentEmpty();",
                      "            return new AppliedState.Absent();")],
        "observationDrift": drift([(c, r) for c in ("source-absent-mid-old", "source-abcd-mid-old")
                                   for r in KERNEL_ROUTES]),
        "fieldAssert": "applied Mid (and Out in the target-error case) report absent where the reference"
                       " reports presentEmpty, on both kernel routes",
        "check": check_s09,
    },
]


def run_capture(scenario, out_dir, log_name):
    code, output = _rm.gradle([":adapter:capture", f"-Pcapture.scenarios={scenario}",
                               f"-Pcapture.output={out_dir}", "-Pcapture.development"], log_name)
    if code != 0:
        raise RuntimeError(f"capture --development failed ({log_name}):\n" + output[-3000:])
    for line in output.splitlines():
        if line.startswith("RECEIPT.json sha256: "):
            return line.split(": ", 1)[1].strip()
    raise RuntimeError(f"no receipt digest printed ({log_name})")


def run_diff(before, after, out_dir, log_name):
    code, output = _rm.gradle([":adapter:captureDiffPackets", f"-Pcapture.before={before}",
                               f"-Pcapture.after={after}", f"-Pcapture.projection={PROJECTION}",
                               f"-Pcapture.output={out_dir}"], log_name)
    if code != 0:
        raise RuntimeError(f"diff-packets failed ({log_name}):\n" + output[-3000:])
    return json.loads((out_dir / "DIFF.json").read_text())


def assert_predicted_drift(mutation, diff):
    if diff["identical"]:
        return False, "packets IDENTICAL — the mutation was not observed"
    actual_obs = sorted(e["artifact"] for e in diff["drift"] if e["class"] == "observation")
    others = sorted(e["artifact"] for e in diff["drift"] if e["class"] != "observation")
    ok = actual_obs == mutation["observationDrift"] and others == ["PACKET.json"]
    return ok, {"observation": actual_obs, "other": others}


def main():
    status = _rm.sh(["git", "status", "--porcelain"]).stdout
    if status.strip():
        print("the working tree must be CLEAN before the scenario mutation suite:\n" + status, file=sys.stderr)
        return 1
    scenario = scenario_file()
    model = scenario.parent / "models/string-direct-cascade.json"
    if sha256(scenario) != SCENARIO_SHA256 or sha256(model) != MODEL_SHA256:
        print("external scenario pack bytes do not match the pinned request identity — refusing", file=sys.stderr)
        return 1
    if SCRATCH.exists():
        import shutil
        shutil.rmtree(SCRATCH)
    head = _rm.sh(["git", "rev-parse", "HEAD"]).stdout.strip()
    capabilities_sha = sha256(REPO / "adapter/src/test/resources/capture/capabilities-v1.json")
    if capabilities_sha != FROZEN_CAPABILITIES_SHA256:
        print("capabilities-v1.json does not match the FROZEN V1 digest"
              f" ({capabilities_sha} != {FROZEN_CAPABILITIES_SHA256}) — refusing", file=sys.stderr)
        return 1
    if sha256(PREDECESSOR) != PREDECESSOR_SHA256:
        print("the rejected Set Z3 predecessor receipt was altered — it is immutable history; refusing",
              file=sys.stderr)
        return 1

    print("== reference development capture (clean tree)", flush=True)
    reference = SCRATCH / "reference/packet"
    reference_sha = run_capture(scenario, reference, "scenario-reference.log")

    results = []
    for mutation in MUTATIONS:
        mid = mutation["id"]
        prediction = {"observationDrift": mutation["observationDrift"],
                      "provenanceDrift": ["PACKET.json"],
                      "fieldAssert": mutation["fieldAssert"]}
        print(f"== {mid}\n   predicted drift: {prediction['observationDrift']} + PACKET.json", flush=True)
        pre = _rm.tree_digest()
        files = _rm.apply_replaces(mutation["replaces"])
        try:
            mutant = SCRATCH / mid / "packet"
            run_capture(scenario, mutant, f"{mid}-capture.log")
            diff = run_diff(reference, mutant, SCRATCH / mid / "diff", f"{mid}-diff.log")
            drift_ok, actual = assert_predicted_drift(mutation, diff)
            field_ok = mutation["check"](mutant)
        finally:
            _rm.restore(files)
        post = _rm.tree_digest()
        fired = drift_ok and field_ok
        results.append({
            "id": mid,
            "description": mutation["description"],
            "mutatedFiles": files,
            "prediction": prediction,
            "actualDrift": actual,
            "fieldAssertHeld": field_ok,
            "guardFired": fired,
            "preTreeDigest": pre,
            "postTreeDigest": post,
            "restoredExactBytes": pre == post,
        })
        if not fired or pre != post:
            raise RuntimeError(f"{mid}: driftAsPredicted={drift_ok} fieldAssert={field_ok}"
                               f" restored={pre == post} — scenario mutation suite FAILED")

    print("== natural gate", flush=True)
    code, _ = _rm.gradle([":adapter:test", "--tests", "io.github.mbackschat.a12.dm.adapter.capture.*",
                          ":adapter:verifyCapturePurity", ":interpreter:jvmTest"], "scenario-natural-gate.log")
    natural_gate = "BUILD SUCCESSFUL" if code == 0 else "BUILD FAILED"

    receipt = {
        "schema": "capture-scenario-mutation-receipt-v2",
        "scenarioSet": {"id": "string-direct-cascade-v1", "version": "1"},
        "predecessor": {
            "receipt": PREDECESSOR.name,
            "sha256": PREDECESSOR_SHA256,
            "disposition": "rejected-version-identity",
            "note": "the Set Z3 run executed against in-place-mutated V1 capabilities bytes"
                    " (5bde25f5722acb1e14d9caa4d8757eca442f30a5c2f887774dcc886d4a282ee3); its observations"
                    " passed the independent audit, but one published capability tuple has one immutable"
                    " meaning and byte identity — this successor run binds the frozen V1 digest",
        },
        "scenarioSha256": SCENARIO_SHA256,
        "modelSha256": MODEL_SHA256,
        "capabilitiesSha256": capabilities_sha,
        "kernelBehaviorVersion": KERNEL_BEHAVIOR_VERSION,
        "captureImplementationRevision": head,
        "cleanTreeDigest": _rm.tree_digest(),
        "commands": {
            "capture": "./build.sh :adapter:capture -Pcapture.scenarios=<abs scenarios.json>"
                       " -Pcapture.output=<new-dir> -Pcapture.development",
            "diff": f"./build.sh :adapter:captureDiffPackets -Pcapture.before=<reference> -Pcapture.after=<mutant>"
                    f" -Pcapture.projection={PROJECTION} -Pcapture.output=<new-dir>",
            "naturalGate": "./build.sh :adapter:test --tests 'io.github.mbackschat.a12.dm.adapter.capture.*'"
                           " :adapter:verifyCapturePurity :interpreter:jvmTest",
        },
        "referencePacketReceiptSha256": reference_sha,
        "mutations": results,
        "naturalGate": natural_gate,
    }
    RECEIPT.write_text(json.dumps(receipt, indent=2) + "\n")
    receipt_sha = hashlib.sha256(RECEIPT.read_bytes()).hexdigest()
    print(f"receipt: {RECEIPT} — {len(results)} scenario mutations, natural gate {natural_gate}", flush=True)
    print(f"receipt sha256 (out-of-band): {receipt_sha}", flush=True)
    return 0 if code == 0 else 1


if __name__ == "__main__":
    sys.exit(main())

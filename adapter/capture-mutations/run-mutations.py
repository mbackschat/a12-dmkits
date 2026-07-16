#!/usr/bin/env python3
"""The capture boundary's MUTATION-SENSITIVITY harness (A12-DMKITS-CAPTURE-PROPOSAL §"Required tests").

For every boundary-applicable mutation: record the classified source-tree digest, apply the mutation,
run the PREDICTED narrow guard (a targeted JUnit class/method, the purity lint, or the REAL command in
--development mode), assert it FIRES, restore the exact source bytes (git checkout), assert the tree
digest returned to its pre-mutation value, and append a machine-checkable receipt entry. Finishes with
the natural full capture gate green and writes `mutation-receipt.json` beside this script.

Run from the repo root:  python3 adapter/capture-mutations/run-mutations.py
Requires a CLEAN committed tree (the mutations must be the only dirt); mutant packets go to an ignored
scratch dir and are qualification-ineligible by construction (--development).
"""

import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
RECEIPT = Path(__file__).resolve().parent / "mutation-receipt.json"
SCRATCH = Path(os.environ.get("CAPTURE_MUTATION_SCRATCH", REPO / "build" / "capture-mutations"))
LOG_DIR = REPO / "build" / "capture-mutations"

CAPTURE = "adapter/src/capture/java/io/github/mbackschat/a12/dm/adapter/capture"
TESTS = "io.github.mbackschat.a12.dm.adapter.capture"


def sh(args, cwd=REPO, check=True):
    proc = subprocess.run(args, cwd=cwd, capture_output=True, text=True)
    if check and proc.returncode != 0:
        raise RuntimeError(f"{' '.join(args)} failed:\n{proc.stdout}\n{proc.stderr}")
    return proc


def tree_digest():
    head = sh(["git", "rev-parse", "HEAD"]).stdout.strip()
    status = sh(["git", "status", "--porcelain"]).stdout
    diff = sh(["git", "diff", "HEAD"]).stdout
    return hashlib.sha256((head + "\n" + status + "\n" + diff).encode()).hexdigest()


def gradle(args, log_name):
    LOG_DIR.mkdir(parents=True, exist_ok=True)
    log = LOG_DIR / log_name
    with open(log, "w") as out:
        proc = subprocess.run(["./build.sh", "--console=plain", *args], cwd=REPO,
                              stdout=out, stderr=subprocess.STDOUT, text=True)
    return proc.returncode, log.read_text()


def apply_replaces(replaces):
    touched = []
    for rel, old, new in replaces:
        path = REPO / rel
        text = path.read_text()
        if text.count(old) != 1:
            raise RuntimeError(f"needle not unique/found in {rel} (count={text.count(old)}):\n{old}")
        path.write_text(text.replace(old, new))
        touched.append(rel)
    return touched


def restore(files):
    sh(["git", "checkout", "--", *files])


# ---- the source mutations (id, description, expected guard, replaces) ---------------------------------

def m(mid, description, guard, replaces, command_step=None):
    return {"id": mid, "description": description, "expectedGuard": guard, "replaces": replaces,
            "commandStep": command_step}


SOURCE_MUTATIONS = [
    m("M01-illegal-model-executed",
      "the legality gate stops gating: illegal models reach runtime execution",
      f"{TESTS}.LegalityGateTest",
      [(f"{CAPTURE}/packet/PacketWriter.java",
        "if (!legality.get(scenarioCase.modelRef()).legal()) {",
        "if (false) {")]),
    m("M02-route-divergence-ignored",
      "route confirmation stops reasoning about a cross-route divergence (hidden divergence)",
      f"{TESTS}.VerifierDivergenceTest.aCrossRouteSplitStopsConfirmationButCharacterizationRetainsIt",
      [(f"{CAPTURE}/policy/PolicyEngine.java",
        '            if (!"equal".equals(route.result())) {',
        "            if (false) {")]),
    m("M03-cause-dropped",
      "the errored instance's structured cause is dropped downstream",
      f"{TESTS}.ErroredComputationObservationTest.theErroredEntryRetainsTheAttemptedValueAndItsStructuredCause",
      [(f"{CAPTURE}/runner/kernel/KernelRouteSupport.java",
        "        return c.getErrorMessage()",
        "        return java.util.Optional.<IMessage>empty()")]),
    m("M04-absent-empty-collapsed",
      "absent and present-empty applied states are collapsed",
      f"{TESTS}.AppliedStateDistinctionTest.theKernelRoutesKeepAbsentAndPresentEmptyDistinct",
      [(f"{CAPTURE}/runner/kernel/KernelRouteSupport.java",
        "        FieldInstanceV2 field = current.directField(segments[segments.length - 1]);\n"
        "        if (field == null) {\n"
        "            return new AppliedState.Absent();\n"
        "        }",
        "        FieldInstanceV2 field = current.directField(segments[segments.length - 1]);\n"
        "        if (field == null) {\n"
        "            return new AppliedState.PresentEmpty();\n"
        "        }")]),
    m("M05-stale-application-route",
      "the applied state reads the INPUT document instead of applying the result to a fresh copy",
      f"{TESTS}.KernelRouteObservationTest.theAppliedStateProbesTheFreshCopyWithTypedValue",
      [(f"{CAPTURE}/runner/kernel/KernelRouteSupport.java",
        "        DocumentV2 applied = result.applyTo(input);",
        "        DocumentV2 applied = input;")]),
    m("M06-characterization-blocks-on-disagreement",
      "characterization starts BLOCKING on a cross-route disagreement instead of retaining it",
      f"{TESTS}.VerifierDivergenceTest.aCrossRouteSplitStopsConfirmationButCharacterizationRetainsIt",
      [(f"{CAPTURE}/policy/PolicyEngine.java",
        '        return new Outcome(reasons.isEmpty() ? "satisfied" : "rejected", reasons,\n'
        "                kernelRouteComparisons(packet));",
        "        List<Projections.Comparison> comparisons = kernelRouteComparisons(packet);\n"
        "        for (Projections.Comparison c : comparisons) {\n"
        '            if (!"equal".equals(c.result())) {\n'
        '                reasons.add("cross-route divergence");\n'
        "            }\n"
        "        }\n"
        '        return new Outcome(reasons.isEmpty() ? "satisfied" : "rejected", reasons, comparisons);')]),
    m("M07-unavailable-treated-as-equal",
      "an unavailable required interpreter channel is treated as semantic equality",
      f"{TESTS}.VerifierDivergenceTest.anUnavailableRequiredInterpreterChannelFailsConformanceClosed",
      [(f"{CAPTURE}/policy/Projections.java",
        '            if (!"available".equals(availability(interpreter, channel.wire()))) {',
        "            if (false) {")]),
    m("M08-route-collapse",
      "the two kernel runner labels select the SAME execution route",
      f"{TESTS}.RouteDistinctnessTest",
      [(f"{CAPTURE}/runner/kernel/JavaStaticRunner.java",
        "    private static IDocumentRtService staticRtService(IDocumentModel expanded, IDocumentModelResolver resolver) {\n"
        "        Properties idToPackage = new Properties();",
        "    private static IDocumentRtService staticRtService(IDocumentModel expanded, IDocumentModelResolver resolver) {\n"
        "        if (true) {\n"
        "            return new DocumentRtServiceFactory(resolver)\n"
        "                    .createDocumentRtService(KernelRouteSupport.freshDynamicConfig());\n"
        "        }\n"
        "        Properties idToPackage = new Properties();")]),
    m("M09-failure-as-successful-empty",
      "a runtime failure is serialized as a successful EMPTY observation",
      f"{TESTS}.RunnersStatusTest.aThrowMapsToTechnicalFailureWithSanitizedDetail",
      [(f"{CAPTURE}/runner/Runners.java",
        "                return ComputeObservation.failed(execution.caseId(), runner.id(), RunStatus.TECHNICAL_FAILURE,\n"
        '                        "the runner threw during execution; details withheld from the packet (see stderr)");',
        "                return ComputeObservation.success(execution.caseId(), runner.id())\n"
        "                        .consumedDocument(new io.github.mbackschat.a12.dm.adapter.capture.dto.ConsumedDocument(0, \"0\".repeat(64)))\n"
        "                        .withoutErrors(ComputeObservation.Channel.available(\"all-computed-clean\", java.util.List.of()))\n"
        "                        .changedSubset(ComputeObservation.Channel.available(\"delta-vs-input\", java.util.List.of()))\n"
        "                        .withErrors(ComputeObservation.Channel.available(\"errored-instances\", java.util.List.of()))\n"
        "                        .cleared(ComputeObservation.Channel.available(\"input-filled-only\", java.util.List.of()))\n"
        "                        .formalErrorsInOperands(ComputeObservation.Channel.available(\"operand-formal-errors\", java.util.List.of()))\n"
        "                        .appliedState(ComputeObservation.Channel.available(\"requested-probes\", java.util.List.of()))\n"
        "                        .build();")]),
    m("M10-decoder-parity-broken",
      "standalone validation stops deciding (accepts everything) while capture still validates",
      f"{TESTS}.DecoderParityTest",
      [(f"{CAPTURE}/cli/CaptureTool.java",
        '                case VALIDATE_SCENARIOS -> validateScenarios(env, required(options, "--scenarios"));',
        "                case VALIDATE_SCENARIOS -> 0;")]),
    m("M11-order-dependent-state",
      "an execution-order-dependent value leaks into the observations (reorder/filtered runs disagree)",
      f"{TESTS}.ReorderEquivalenceTest",
      [(f"{CAPTURE}/runner/kernel/KernelRouteSupport.java",
        "    private KernelRouteSupport() {\n    }",
        "    private static int ORDER_LEAK;\n\n    private KernelRouteSupport() {\n    }"),
       (f"{CAPTURE}/runner/kernel/KernelRouteSupport.java",
        "        return ComputeObservation.success(execution.caseId(), runner)",
        '        return ComputeObservation.success(execution.caseId() + "#" + (ORDER_LEAK++), runner)')]),
    m("M12-clean-gate-removed",
      "default-mode capture stops requiring a clean committed checkout",
      f"{TESTS}.CaptureCommandTest.defaultModeRefusesADirtyTreeNamingDevelopmentMode",
      [(f"{CAPTURE}/packet/PacketWriter.java",
        "        if (!options.development() && !options.sourceState().clean()) {",
        "        if (false) {")]),
    m("M13-nondeterministic-packet",
      "a volatile value enters the packet: a second identical run produces different bytes",
      f"{TESTS}.CaptureDeterminismTest",
      [(f"{CAPTURE}/packet/PacketWriter.java",
        '                        .put("tool", TOOL_NAME)',
        '                        .put("tool", TOOL_NAME + "-" + System.nanoTime())')]),
    m("M14-corpus-write-allowed",
      "output may enter the committed corpus tree without promotion",
      f"{TESTS}.CaptureCommandTest.refusesTheCommittedCorpusTree",
      [(f"{CAPTURE}/packet/OutputDirs.java",
        "        if (out.startsWith(corpus)) {",
        "        if (false) {")]),
    m("M15-metadata-leak",
      "a machine-specific absolute path enters generated metadata",
      f"{TESTS}.CaptureCommandTest.captureWritesTheDirectoryShapedPacketAndPrintsTheReceiptDigest",
      [(f"{CAPTURE}/packet/PacketWriter.java",
        '                        .put("sourceRevision", options.sourceState().revision())',
        '                        .put("sourceRevision", System.getProperty("user.home") + "/"'
        " + options.sourceState().revision())")],
      command_step="capture-expect-refusal"),
    m("M16-legality-inspects-different-bytes",
      "the legality checker consumes DIFFERENT bytes than the runtime executes",
      f"{TESTS}.LegalityGateTest.anIllegalModelIsCharacterizedNeverExecuted",
      [(f"{CAPTURE}/legality/LegalityGate.java",
        "        String dmJson = new String(model.bytes(), StandardCharsets.UTF_8);",
        "        String dmJson = new String(model.bytes(), StandardCharsets.UTF_8)"
        '.replace("[MissingField]", "[BaseRate]");')]),
    m("M17-verifier-mutates-packet",
      "the verifier writes into the packet it consumes",
      f"{TESTS}.VerifierTest.verificationNeverWritesIntoThePacket",
      [(f"{CAPTURE}/policy/Verifier.java",
        "        PolicyEngine.Outcome outcome = PolicyEngine.apply(policy, packet);",
        "        PolicyEngine.Outcome outcome = PolicyEngine.apply(policy, packet);\n"
        "        try {\n"
        '            Files.writeString(packetDir.resolve("VERIFIER-WAS-HERE.txt"), "x");\n'
        "        } catch (IOException e) {\n"
        "            throw new UncheckedIOException(e);\n"
        "        }")]),
    m("M18-schema-mutated-under-existing-id",
      "a projection/schema granularity changes IN PLACE under the existing capability id",
      f"{TESTS}.CapabilitiesTest.capabilitiesOutputMatchesTheCommittedGolden",
      [(f"{CAPTURE}/dto/RunnerFidelity.java",
        '    public static final String ALL_COMPUTED_CLEAN = "all-computed-clean";',
        '    public static final String ALL_COMPUTED_CLEAN = "all-clean";')]),
    m("M20-development-packet-eligible",
      "development/dirty packets become eligible for portable qualification",
      f"{TESTS}.VerifierTest.aDevelopmentPacketIsRejectedByItsDeclaredPolicyBeforeComparison",
      [(f"{CAPTURE}/policy/PolicyEngine.java",
        '        if (packet.developmentMode() || !"clean".equals(packet.workingTree())) {',
        "        if (false) {")]),
    # ---- the Set-Z2 audit-correction mutations (findings 1, 3, 5) --------------------------------------
    m("M21-cli-policy-binding-removed",
      "verification stops binding to the request-declared policy (any CLI policy accepted)",
      f"{TESTS}.VerifierPolicyBindingTest.aMismatchingCliPolicyIsRefusedWithoutAReport",
      [(f"{CAPTURE}/policy/Verifier.java",
        "        if (policy != declared) {",
        "        if (false) {")]),
    m("M22-mandatory-projection-gate-removed",
      "a policy's mandatory projection no longer needs predeclaration (selected after observations)",
      f"{TESTS}.VerifierPolicyBindingTest.aMandatoryProjectionMissingFromTheRequestRefusesBeforeExecution",
      [(f"{CAPTURE}/schema/ScenarioDecoder.java",
        "            if (!requirement.comparisonProjections().contains(mandatory)) {",
        "            if (false) {")]),
    m("M23-channel-fidelity-gate-removed",
      "a required channel unavailable on a qualifying runner no longer refuses before execution",
      f"{TESTS}.VerifierPolicyBindingTest.aRequiredChannelAQualifyingRunnerCannotExposeRefusesBeforeExecution",
      [(f"{CAPTURE}/schema/ScenarioDecoder.java",
        "                if (fidelity.channel(channel).availability() != Availability.AVAILABLE) {",
        "                if (false) {")]),
    m("M24-placement-dropped-in-adaptation",
      "the kernel routes DROP a placement during placement-to-document adaptation (both routes alike)",
      f"{TESTS}.ConsumedDocumentBindingTest",
      [(f"{CAPTURE}/runner/kernel/KernelRouteSupport.java",
        "                case EMPTY -> {\n"
        "                    document.addEntityInstance(df.createFieldInstance(p.path(), reps));\n"
        "                    consumed.empty(p.path(), p.reps());\n"
        "                }",
        "                case EMPTY -> { }")]),
    m("M25-post-run-identity-check-removed",
      "capture stops re-verifying the closure identity before finalization (mid-run change unnoticed)",
      f"{TESTS}.ClosureIdentityTest.anIdentityChangeBetweenExecutionAndFinalizationRefusesWithNoPacket",
      [(f"{CAPTURE}/packet/PacketWriter.java",
        "            if (!after.equals(before)) {",
        "            if (false) {")]),
]

# M19: the DTO-boundary crossing — its guard is the Gradle purity lint, not a JUnit class.
PURITY_MUTATION = m("M19-kernel-type-crosses-dto-boundary",
                    "a kernel type crosses into the neutral DTO boundary",
                    ":adapter:verifyCapturePurity",
                    [(f"{CAPTURE}/dto/Pointers.java",
                      "public final class Pointers {",
                      "public final class Pointers {\n\n    com.mgmtp.a12.kernel.md.rt.api.IMessage leak;")])


def run_source_mutation(entry, results):
    mid = entry["id"]
    print(f"== {mid}", flush=True)
    pre = tree_digest()
    files = apply_replaces(entry["replaces"])
    try:
        if entry["expectedGuard"].startswith(":"):
            code, output = gradle([entry["expectedGuard"]], f"{mid}.log")
            fired = code != 0
            actual = entry["expectedGuard"] + (" FAILED" if fired else " passed")
        else:
            code, output = gradle([":adapter:test", "--tests", entry["expectedGuard"]], f"{mid}.log")
            fired = code != 0 and "FAILED" in output
            actual = "; ".join(sorted({line.strip() for line in output.splitlines()
                                       if " FAILED" in line and ">" in line})) or "no failing test reported"
        command_result = None
        if entry.get("commandStep") == "capture-expect-refusal":
            command_result = run_real_capture_expect_refusal(mid)
    finally:
        restore(files)
    post = tree_digest()
    results.append({
        "id": mid,
        "description": entry["description"],
        "mutatedFiles": files,
        "expectedGuard": entry["expectedGuard"],
        "guardFired": fired,
        "actualGuard": actual,
        "realCommandStep": command_result,
        "preTreeDigest": pre,
        "postTreeDigest": post,
        "restoredExactBytes": pre == post,
    })
    if not fired or pre != post:
        raise RuntimeError(f"{mid}: guardFired={fired} restored={pre == post} — mutation suite FAILED")


def run_real_capture_expect_refusal(mid):
    """The proposal's command-level check: the REAL capture command in --development refuses the mutant."""
    scenario = write_scratch_scenario(mid)
    out_dir = SCRATCH / mid / "packet"
    code, output = gradle([":adapter:capture", f"-Pcapture.scenarios={scenario}",
                           f"-Pcapture.output={out_dir}", "-Pcapture.development"], f"{mid}-command.log")
    refused = code != 0 and "metadata leak" in output
    return {"command": "capture --development", "refused": refused,
            "diagnostic": "metadata leak" if refused else "NOT refused"}


def write_scratch_scenario(name):
    """Copy the committed authoring example (the ONE canonical valid scenario root) into scratch."""
    root = SCRATCH / name / "root"
    if root.exists():
        shutil.rmtree(root)
    shutil.copytree(REPO / "adapter/capture-examples/scenario-example", root)
    return root / "scenarios.json"


# ---- artifact-level mutations (the real command over mutated ARTIFACTS; no source change) --------------
#
# Two tiers: the INTEGRITY tier (A01–A04) leaves the receipt stale, so the byte layer refuses; the
# RELATIONAL tier (A07–A15, audit Finding 2) RENEWS the receipt (and re-binds envelope digests where an
# artifact changed), so only the closed relational validator can catch the internal contradiction.

RECEIPT_ROLES = [
    ("PACKET.json", "packet-envelope"),
    ("request/scenarios.json", "copied-request"),
    ("capabilities/capabilities.json", "capability-declaration"),
    ("inputs/", "copied-input"),
    ("legality/", "legality"),
    ("observations/", "observation"),
]


def receipt_role(rel):
    for prefix, role in RECEIPT_ROLES:
        if rel == prefix or (prefix.endswith("/") and rel.startswith(prefix)):
            return role
    raise RuntimeError(f"unclassifiable artifact {rel}")


def renew_receipt(packet):
    """Rewrite RECEIPT.json from the live directory (the consistent-tamper step); returns its digest."""
    artifacts = []
    for path in sorted(p.relative_to(packet).as_posix() for p in packet.rglob("*") if p.is_file()):
        if path == "RECEIPT.json":
            continue
        data = (packet / path).read_bytes()
        artifacts.append({"path": path, "role": receipt_role(path), "bytes": len(data),
                          "sha256": hashlib.sha256(data).hexdigest()})
    receipt = {"schema": "capture-receipt-v1", "artifacts": artifacts}
    (packet / "RECEIPT.json").write_text(json.dumps(receipt, indent=2) + "\n")
    return hashlib.sha256((packet / "RECEIPT.json").read_bytes()).hexdigest()


def run_artifact_mutations(results):
    scenario = write_scratch_scenario("artifact")
    packet = SCRATCH / "artifact" / "packet"
    code, output = gradle([":adapter:capture", f"-Pcapture.scenarios={scenario}",
                           f"-Pcapture.output={packet}", "-Pcapture.development"], "artifact-capture.log")
    if code != 0:
        raise RuntimeError("artifact-suite base capture failed:\n" + output)
    receipt_sha = hashlib.sha256((packet / "RECEIPT.json").read_bytes()).hexdigest()
    pristine = SCRATCH / "artifact" / "pristine"
    shutil.copytree(packet, pristine)

    def restore_packet():
        shutil.rmtree(packet)
        shutil.copytree(pristine, packet)

    def verify_expect(mid, diagnostic_needle, mutate, unmutate=restore_packet):
        effective_sha = mutate() or receipt_sha
        try:
            code, output = gradle([":adapter:captureVerify", f"-Pcapture.packet={packet}",
                                   f"-Pcapture.packetReceiptSha256={effective_sha}",
                                   "-Pcapture.policy=kernel-route-confirmed-v1",
                                   f"-Pcapture.output={SCRATCH / mid / 'report'}"], f"{mid}.log")
            refused = code != 0 and diagnostic_needle in output
        finally:
            unmutate()
        results.append({"id": mid, "description": f"artifact mutation — expect verify refusal ({diagnostic_needle})",
                        "mutatedFiles": ["<packet artifacts, restored>"],
                        "expectedGuard": f"verify refusal: {diagnostic_needle}",
                        "guardFired": refused, "actualGuard": "refused" if refused else "NOT refused",
                        "realCommandStep": {"command": "verify", "refused": refused},
                        "preTreeDigest": None, "postTreeDigest": None, "restoredExactBytes": True})
        if not refused:
            raise RuntimeError(f"{mid}: verify did not refuse — mutation suite FAILED")

    def envelope_sub(pattern, repl, count=1):
        env = packet / "PACKET.json"
        text, n = re.subn(pattern, repl, env.read_text(), count=count)
        if n != count:
            raise RuntimeError(f"envelope mutation matched {n} times, wanted {count}: {pattern}")
        env.write_text(text)
        return renew_receipt(packet)

    def artifact_rebound_sub(rel, pattern, repl):
        f = packet / rel
        old_sha = hashlib.sha256(f.read_bytes()).hexdigest()
        text, n = re.subn(pattern, repl, f.read_text(), count=1)
        if n != 1:
            raise RuntimeError(f"artifact mutation did not match: {rel}: {pattern}")
        f.write_text(text)
        new_sha = hashlib.sha256(f.read_bytes()).hexdigest()
        env = packet / "PACKET.json"
        env.write_text(env.read_text().replace(old_sha, new_sha))
        return renew_receipt(packet)

    # -- the integrity tier (stale receipt) --
    print("== A01-stray-artifact", flush=True)
    verify_expect("A01-stray-artifact", "refuses unclassifiable artifact",
                  lambda: (packet / "notes.txt").write_text("stray") and None)

    print("== A02-byte-drift", flush=True)
    target = packet / "observations/premium-computes/kernel-java-static.json"
    verify_expect("A02-byte-drift", "drifted",
                  lambda: target.write_text(target.read_text().replace('"20.5"', '"20.9"')) and None)

    print("== A03-symlink", flush=True)
    verify_expect("A03-symlink", "refuses symlink",
                  lambda: (packet / "observations/premium-computes/link.json").symlink_to(target) and None)

    print("== A04-removed-artifact", flush=True)
    verify_expect("A04-removed-artifact", "drifted",
                  lambda: (packet / "legality/models/quote-premium.json").unlink() and None)

    # -- the relational tier (renewed receipt; audit Finding 2 + the F3/F4 verify halves) --
    print("== A07-request-digest-claim", flush=True)
    verify_expect("A07-request-digest-claim", "requestSha256",
                  lambda: envelope_sub(r'"requestSha256": "[0-9a-f]{64}"',
                                       '"requestSha256": "' + "1" * 64 + '"'))

    print("== A08-scenario-identity-claim", flush=True)
    verify_expect("A08-scenario-identity-claim", "disagrees with the copied request",
                  lambda: envelope_sub(r'"id": "capture-selftest-quote-v1"',
                                       '"id": "someone-elses-scenario-v1"'))

    print("== A09-legality-verdict-claim", flush=True)
    verify_expect("A09-legality-verdict-claim", "contradiction",
                  lambda: envelope_sub(r'"verdict": "legal"', '"verdict": "illegal"'))

    print("== A10-observation-case-claim", flush=True)
    verify_expect("A10-observation-case-claim", "caseId",
                  lambda: artifact_rebound_sub("observations/premium-computes/kernel-groovy-dynamic.json",
                                               r'"caseId": "premium-computes"', '"caseId": "another-case"'))

    print("== A11-unknown-nested-member", flush=True)
    verify_expect("A11-unknown-nested-member", "unknown member",
                  lambda: envelope_sub(r'"requestArtifact": "request/scenarios.json",',
                                       '"requestArtifact": "request/scenarios.json",\\n    "note": "smuggled",'))

    print("== A12-removed-association", flush=True)
    verify_expect("A12-removed-association", "exactly once",
                  lambda: envelope_sub(r',\s*\{\s*"runner": "kernel-java-static",[^}]*\}', ""))

    print("== A13-orphan-artifact", flush=True)
    def orphan():
        shutil.copyfile(packet / "observations/premium-computes/kernel-groovy-dynamic.json",
                        packet / "observations/premium-computes/extra.json")
        return renew_receipt(packet)
    verify_expect("A13-orphan-artifact", "orphan", orphan)

    print("== A14-capability-copy-tamper", flush=True)
    verify_expect("A14-capability-copy-tamper", "registry",
                  lambda: artifact_rebound_sub("capabilities/capabilities.json",
                                               r'"all-computed-clean"', '"all-clean"'))

    print("== A15-consumed-document-tamper", flush=True)
    verify_expect("A15-consumed-document-tamper", "consumed",
                  lambda: artifact_rebound_sub("observations/premium-computes/kernel-groovy-dynamic.json",
                                               r'"canonicalSha256": "[0-9a-f]{64}"',
                                               '"canonicalSha256": "' + "3" * 64 + '"'))

    for mid, needle, mutate_text in [
        ("A05-expected-member", "never carries expected runner results",
         lambda t: t.replace('"probes": [ "/Quote[1]/Premium" ]',
                             '"probes": [ "/Quote[1]/Premium" ], "expected": ["x"]')),
        ("A06-unknown-schema-version", "unknown versions are rejected",
         lambda t: t.replace("capture-scenario-set-v1", "capture-scenario-set-v9")),
    ]:
        print(f"== {mid}", flush=True)
        scenario_file = write_scratch_scenario(mid)
        text = scenario_file.read_text()
        mutated = mutate_text(text)
        if mutated == text:
            raise RuntimeError(f"{mid}: scenario mutation did not apply")
        scenario_file.write_text(mutated)
        code, output = gradle([":adapter:captureValidateScenarios",
                               f"-Pcapture.scenarios={scenario_file}"], f"{mid}.log")
        refused = code != 0 and needle in output
        results.append({"id": mid, "description": f"request mutation — expect validate-scenarios refusal ({needle})",
                        "mutatedFiles": ["<scratch scenario>"],
                        "expectedGuard": f"validate-scenarios refusal: {needle}",
                        "guardFired": refused, "actualGuard": "refused" if refused else "NOT refused",
                        "realCommandStep": {"command": "validate-scenarios", "refused": refused},
                        "preTreeDigest": None, "postTreeDigest": None, "restoredExactBytes": True})
        if not refused:
            raise RuntimeError(f"{mid}: validate-scenarios did not refuse — mutation suite FAILED")


def main():
    status = sh(["git", "status", "--porcelain"]).stdout
    if status.strip():
        print("the working tree must be CLEAN before the mutation suite (the mutations are the only dirt):\n"
              + status, file=sys.stderr)
        return 1
    if SCRATCH.exists():
        shutil.rmtree(SCRATCH)
    results = []
    head = sh(["git", "rev-parse", "HEAD"]).stdout.strip()

    for entry in SOURCE_MUTATIONS:
        run_source_mutation(entry, results)
    run_source_mutation(PURITY_MUTATION, results)
    run_artifact_mutations(results)

    print("== natural full gate", flush=True)
    code, _ = gradle([":adapter:test", "--tests", f"{TESTS}.*", ":adapter:verifyCapturePurity"],
                     "natural-gate.log")
    natural_gate = "BUILD SUCCESSFUL" if code == 0 else "BUILD FAILED"

    receipt = {
        "schema": "capture-mutation-receipt-v1",
        "headRevision": head,
        "cleanTreeDigest": tree_digest(),
        "mutations": results,
        "naturalGate": natural_gate,
    }
    RECEIPT.write_text(json.dumps(receipt, indent=2) + "\n")
    print(f"receipt: {RECEIPT} — {len(results)} mutations, natural gate {natural_gate}", flush=True)
    return 0 if code == 0 else 1


if __name__ == "__main__":
    sys.exit(main())

# Capture-boundary mutation suite

The machine-checkable mutation-sensitivity harness for the portable evidence-capture boundary ([`docs/CONFORMANCE-CORPUS-SPEC.md`](../../docs/CONFORMANCE-CORPUS-SPEC.md) §15; the adopted [`A12-DMKITS-CAPTURE-PROPOSAL.md`](../../docs/A12-DMKITS-CAPTURE-PROPOSAL.md) §"Required tests and mutation sensitivity").

**What it does.** For every boundary-applicable mutation from the proposal's list, [`run-mutations.py`](run-mutations.py): records the classified source-tree digest → applies the mutation → runs the PREDICTED narrow guard (a targeted JUnit class/method, the `verifyCapturePurity` lint, or the REAL command in `--development` mode) → asserts it FIRES → restores the exact source bytes → asserts the tree digest returned to its pre-mutation value → appends a receipt entry. Artifact-level mutations (stray file, symlink, byte drift, removed artifact, expected-member/unknown-version requests) run through the real `capture`/`verify`/`validate-scenarios` commands over a disposable `--development` packet. The run finishes with the natural full capture gate green.

**The receipt.** [`mutation-receipt.json`](mutation-receipt.json) (`capture-mutation-receipt-v1`): per mutation — id, description, mutated files, expected guard, actual failing guard, real-command step where applicable, pre/post tree digests, exact-restoration flag — plus the HEAD revision and the natural-gate result. Mutant packets are disposable and qualification-ineligible by construction (`--development`).

**Run** (from the repo root, CLEAN tree — the mutations must be the only dirt; takes ~15–25 min):

```
python3 adapter/capture-mutations/run-mutations.py
```

**Deferred mutations** (scenario-run-specific — they need the externally authored `string-direct-cascade-v1` pack, the next set): the accepted-equal all-clean/changed-subset split, the source-absent stale-cascade (`"OLD-X"`), the rejected-attempted-value-reaching-the-dependent, the *semantic* half of downstream cause reconstruction (the transport half is M03), and the final qualified recapture from clean committed HEAD (the handback ritual; its clean-gate mechanics are M12). The compact legacy corpus signature is guarded by the existing `corpusReplayTest` gate (this program never touched that surface). The full mapping lives in the spec's §15f.

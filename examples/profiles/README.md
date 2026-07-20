# Performance-calibration profiles

These three committed artifacts are the approved current-format `profile′` outputs from own-domain synthesized models:

- [`compact.json`](compact.json) — a smaller model with concentrated rule work and high repeatability capacity.
- [`breadth-shaped.json`](breadth-shaped.json) — a wide model with 2,827 fields, 1,267 groups, 2,181 rules, and 307 computations.
- [`depth-shaped.json`](depth-shaped.json) — a computation-heavy model with 851 computations, a maximum computation cascade depth of 16, and four repeatable levels.

They are calibration inputs for profile-driven model synthesis and large-document report/interpreter performance tests. Their private real-model target profiles and source models are not included. The files contain no legacy `profileVersion` field and require no compatibility reader; [`MODEL-PROFILE-PROPOSAL.md`](../../docs/MODEL-PROFILE-PROPOSAL.md) defines the current contract while G52 is being implemented.

# a12-dmkits — licensing & IP

The settled licensing decision and the reasoning behind it. The proposal's [§19](PROJECT-PROPOSAL.md) states the decision in one line and points here for the full analysis.

## Decision (2026-06-04)

- **rulekit source — MIT.** Every file in this repository is rulekit's own original work and ships **no kernel binary**, so the source carries no copyleft entanglement. MIT keeps the whole A12-consuming repo family consistent (`a12-kernel-demo` and `a12-kernel-bootstrap` are both MIT © mbackschat).
- **The bundled CLI distributable — EUPL-1.2.** The standalone CLI (N10) embeds the kernel for a zero-install experience; the moment kernel bytecode is in a thing we hand out, that *artifact* must be offered under EUPL-1.2. rulekit's source stays MIT regardless.

The governing rule, one line: **MIT for our source that resolves the kernel at build time; EUPL-1.2 for any artifact in which we bundle the kernel.** This same rule covers `a12-dmkits`, `a12-kernel-demo`, and the future CLI.

## Why this is sound — the facts

- **The A12 kernel stack is dual-licensed `EUPL-1.2 OR LicenseRef-commercial`** (every contributing module: `a12-kernel`, `a12-base`, `a12-utils`, `a12-logging`, `a12-localization`, `a12-devtools`; `a12-kernel-antlr4` is an ANTLR-derived BSD-3 fork). The bootstrap kit is MIT.
- **Redistribution is explicitly granted** by the kernel LICENSE: *"You may redistribute and/or modify this software under the terms of the European Union Public Licence, version 1.2."* So bundling needs **no commercial license** — the OSS option covers it. The obligation is EUPL-1.2 compliance on what we ship.
- **No public binary host.** The kernel is built from public GitHub sources (`github.com/mgm-tp`) and published only to local Maven. There is nothing public to fetch, so the only zero-install path for the BA is to *bundle* the kernel — which is why the bundle is the place EUPL applies.

## Why MIT-source + EUPL-bundle do not conflict

License direction is a **one-way valve: permissive → copyleft always combines; copyleft → permissive is what's blocked.** We only ever do the former.

- MIT grants nearly everything and explicitly grants the right to **sublicense**, imposing only "keep the notice." So MIT code is *allowed* to sit inside a distribution governed by stricter terms.
- EUPL requires a *distributed combined/derivative work* to be offered under EUPL; it does **not** forbid including permissive code — it only forbids relicensing EUPL code *out* to something weaker.
- In the bundled CLI: the **artifact** is offered under **EUPL-1.2** (it contains EUPL bytecode), while the **rulekit files inside stay MIT-origin** (notices preserved). Both licenses' conditions hold at once — that *is* "no conflict."

Two clarifications that usually cause confusion:

1. **EUPL's compatibility-list excluding MIT is irrelevant here.** That Appendix only matters when combining EUPL with *another copyleft* and relicensing the combined work under it (e.g. GPLv3). Including *permissive* code is always allowed.
2. **Source vs artifact are separate, and only the artifact touches EUPL.** The GitHub source repo is pure MIT (no kernel). Building MIT code against the EUPL kernel where the *user* supplies their own kernel (via the bootstrap) distributes no EUPL — fully fine. EUPL applies only to a release that physically embeds the kernel.

This also sidesteps the one genuinely grey area — the EUPL "derivative work" / linking question — because we never invoke EUPL except at the bundle, where it plainly applies.

## EUPL-1.2 compliance checklist (for the bundled CLI, when built)

1. Include the EUPL-1.2 text (`licenses/EUPL-1.2.txt`) plus the kernel's `LICENSE` + `NOTICE`.
2. Retain `THIRD_PARTY_NOTICES` + the `licenses/` folder (ANTLR-BSD, Jackson, etc.).
3. Provide kernel **source availability** — a pointer to the public `github.com/mgm-tp` repos satisfies EUPL Art. 5.
4. Keep the warranty disclaimer; label the distributed CLI EUPL-1.2.
5. **No "mgm A12" name/logo** in product branding (the "Not an official A12 artifact" disclaimer already helps); the `rulekit` name is fine.

## Scope notes

- **`a12-kernel-mm`** (EUPL-1.2) — reference/test-only metamodels, **never bundled** in the CLI runtime, so out of scope for redistribution. Committed fixtures remain our own originals (project `CLAUDE.md`).
- **A12 Tools workspaces** (proprietary) — read-only pattern oracle, never committed, never shipped.
- **Standard caveat:** this is engineering analysis, not legal advice. The permissive-into-copyleft combination is well-settled; the commercial option (`a12-license@mgm-tp.com`) remains available if a future need wants different terms.

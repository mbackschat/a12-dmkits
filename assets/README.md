# Assets

## What these are, and why they are risky

Full-resolution raster infographics, embedded as scaled previews in the project READMEs and in the public release mirrors.

An image is the worst carrier this repository has for a fact: nothing can read it, so a figure printed inside one is a claim no test will ever check. That is why the pixels are not the source of truth and never edited directly.

**These PNGs are derived output.** Their source is [`docs/INFOGRAPHICS.md`](../docs/INFOGRAPHICS.md), which owns the content maps, the exact generation prompts, the asset registry, the factual sources, the placement map, the visual acceptance gates and the refinement log. Edit that document and regenerate; do not retouch a PNG in isolation, because nothing would then record what it claims or why.

**Every embedded figure is guarded.** [`scripts/check-infographic-freshness.sh`](../scripts/check-infographic-freshness.sh) and `InfographicAssetGuardTest` read the registry in that document — neither carries a second asset list — and they refuse an unregistered PNG, a registered asset with no accepted-output preview, a broken README placement, and a release-source copy that is not byte-identical to the canonical file.

**Factual claims printed inside an image are bound to their owning source.** The performance figures track the public performance report, and the Java/Kotlin kLOC values track the generated Source Statistics table in the root [`README.md`](../README.md). When an owning source is refreshed, the guard fails on the *prompt* — that failure is the signal to regenerate the image, not a reason to edit the figure back.

The PNG is deliberately not treated as machine-readable data: reliable raster-text verification would need brittle OCR, so CI proves that the owning reports and the generation prompts agree and leaves the pixels to a human inspection recorded in the refinement log.

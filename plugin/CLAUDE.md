# dmtool public release mirror — agent instructions

This repository is generated release output for `dmtool`. It is not the development repo, and it is not edited by hand.

The public tree is assembled from the source repo by `scripts/assemble-release-repo.sh` and pushed by `scripts/publish-release.sh`. The mirror root contains the Claude Code plugin; `codex/` contains the Codex plugin; GitHub Release assets contain the native binaries and `SHA256SUMS`.

Do not commit directly to this repository or patch `.github/workflows/build-native.yml` here. Change the source repo, then run the release process so the mirror is regenerated.

Published releases are immutable by project policy, even when GitHub technically allows release assets to be edited. After a release is public, adding a platform binary, replacing a binary, changing `SHA256SUMS`, or changing the public workflow for that release requires a new patch release. Draft releases may be refreshed before publication.

Never record local machine paths, usernames, personal tokens, local checkout names, or other machine-specific/secret data in files committed to this repository.

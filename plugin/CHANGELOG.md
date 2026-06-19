# Changelog

All notable changes to the **publicly released `dmtool` artifacts** — the native CLI binary and the agent plugins (Claude Code, OpenAI Codex). This file is maintained in the source repo and shipped to the public [`a12-dmtool-releases`](https://github.com/mbackschat/a12-dmtool-releases) mirror; it tracks only what an end user can download, not internal development.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versioning is [SemVer](https://semver.org/) plus **A12 Kernel compatibility metadata** (the kernel each release targets is recorded per entry, never folded into the version string).

## [Unreleased]

### Changed

- **`field modify` can now change a field's metadata in place, not just re-type it.** It is a **partial** edit: a `kind`/per-kind config re-types the field (as before), and any metadata key — requiredness, label, helper text, descriptions, annotations, the global/transient flags — changes that metadata, with no delete-and-recreate. A metadata-only edit (no `kind`) is valid, and the result's `changed.aspects` reports exactly what was touched.

### Fixed

- **A typo'd or out-of-scope key in a JSON spec is now rejected, not silently ignored.** `field add`/`field modify`, `rule add`, `computation add`, and the `apply`/`batch` ops validate their spec against the published schema — **top-level and inside nested config blocks** — so an unrecognized key (a misspelled `requried`, a `string.maxLenght`) is refused with a corrective `RK_UNKNOWN_ARG` (and a did-you-mean) instead of being parsed-and-dropped into a false `applied`.
- **`field modify` no longer silently drops the metadata you give it.** Passing a `label`/`helperText`/`required` to a re-type used to report `applied` while changing nothing (a false success); those aspects are now applied, and a spec that would change nothing is refused instead of reported as applied. The same fix covers the `apply`/`batch` field-modify op.
- **An upgraded plugin now actually picks up the new binary.** The downloader cached `dmtool` at a version-less path and only fetched it when absent, so once any binary was cached, bumping the plugin never replaced it — an upgrade kept running the old binary (and the v0.3.2 download-pin fix couldn't reach anyone who already had one cached). The cache is now **keyed by version**: a newer plugin re-downloads the matching binary on its first session, prunes the old one, and keeps the deterministic `…/bin/dmtool` path as a symlink to the current version. (Already running a stale binary from before this fix? Upgrading to this version self-heals on the next session.)

## [0.3.2] — kernel 30.8.1 (A12 Tools 2025.06-ext5)

### Fixed

- **The agent plugins now download the binary that matches the installed plugin version.** The `SessionStart` hook that fetches `dmtool` on first use was pinned to the very first release tag, so every install pulled that initial binary regardless of the plugin version — and a native-image crash on the current-date operators (`Today`, `YearFromDate`, `AddYears`), fixed in the binary long ago, kept reaching users. The download pin now tracks the release version, so a fresh install gets the matching, fixed binary.

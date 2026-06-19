# Changelog

All notable changes to the **publicly released `dmtool` artifacts** — the native CLI binary and the agent plugins (Claude Code, OpenAI Codex). This file is maintained in the source repo and shipped to the public [`a12-dmtool-releases`](https://github.com/mbackschat/a12-dmtool-releases) mirror; it tracks only what an end user can download, not internal development.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versioning is [SemVer](https://semver.org/) plus **A12 Kernel compatibility metadata** (the kernel each release targets is recorded per entry, never folded into the version string).

## [Unreleased]

_Empty._

## [0.3.2] — kernel 30.8.1 (A12 Tools 2025.06-ext5)

### Fixed

- **The agent plugins now download the binary that matches the installed plugin version.** The `SessionStart` hook that fetches `dmtool` on first use was pinned to the very first release tag, so every install pulled that initial binary regardless of the plugin version — and a native-image crash on the current-date operators (`Today`, `YearFromDate`, `AddYears`), fixed in the binary long ago, kept reaching users. The download pin now tracks the release version, so a fresh install gets the matching, fixed binary.

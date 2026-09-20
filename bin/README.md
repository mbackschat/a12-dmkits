# `bin/` — the stable `dmtool` call site

One file: [`dmtool`](dmtool), a POSIX shim. Docs, the [`examples/`](../examples) walkthroughs and every script call `dmtool <target> <op> …`, and this resolves that to whichever CLI implementation is present — **so a call site never names a packaging artifact.**

That is the same stable-interface / swappable-implementation split the packaging decision makes: the documented command text is identical whether a JVM launcher or a native binary answers it. A demo that hard-coded `build/install/cli/bin/dmtool` would break the moment the native binary became the thing to test — and worse, would keep passing against a stale JVM build.

**Resolution order, first hit wins:**

1. `$DMTOOL_CLI` — an explicit path, which overrides everything
2. the native image, **only if `RK_NATIVE=1`**
3. the `installDist` launcher — the default
4. nothing → exit 2 with a build hint

**Native is opt-in rather than preferred, and the reason matters.** It starts in ~37 ms against the launcher's ~600 ms, so "fastest first" looks obviously right — and would let a stale native artifact silently shadow freshly built code. The launcher, rebuilt by `:cli:installDist`, is always current; opt into native when you have rebuilt it and want the startup win.

The shim also validates the Java that the generated Gradle start script would otherwise trust blindly, and fails with a recovery hint instead of dying inside that script — a stale versioned Homebrew Cellar path in `JAVA_HOME` is routine after `brew upgrade openjdk`, and diagnosing it from the start script's error is not.

```sh
./build.sh :cli:installDist      # build the dev launcher first
./bin/dmtool manifest            # then the shim finds it
RK_NATIVE=1 ./bin/dmtool manifest  # …or the native binary, if you rebuilt it
```

Packaging phases and the reasoning: [`docs/CLI-SPEC.md`](../docs/CLI-SPEC.md) §11 · [`docs/ARCHITECTURE.md`](../docs/ARCHITECTURE.md) · native image: [`docs/NATIVE-IMAGE-GRAALVM.md`](../docs/NATIVE-IMAGE-GRAALVM.md).

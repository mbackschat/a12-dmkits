# A12 Interpreter

A **Kotlin Multiplatform** engine for A12 Document Models: **one implementation, three targets — JVM, Node.js and browser** — with no A12 Kernel at runtime.

It prepares model workspaces, resolves includes and imported type definitions, reads Documents, runs full or partial validation, and computes derived values. Your application keeps control of file, network, storage, and credential access.

## Documentation

**[→ Full documentation, examples, performance and downloads](https://github.com/mbackschat/a12-interpreter-releases)**

This package README is deliberately minimal so it cannot drift from the documentation. Everything — the capability list, TypeScript and Kotlin examples, the workspace model, performance and footprint figures, API references and the live browser showcase — lives in the public repository above.

## Install

Version `0.14.0` is distributed from the public `a12-interpreter-releases` repository.

For TypeScript or JavaScript, install the release tarball with `pnpm add https://github.com/mbackschat/a12-interpreter-releases/releases/download/v0.14.0/a12-interpreter-0.14.0.tgz`.

For Kotlin or Java, add the Maven repository `https://mbackschat.github.io/a12-interpreter-releases/maven` and dependency `io.github.mbackschat.a12.dm:dm-interpreter:0.14.0`. Public third-party dependencies continue to resolve from Maven Central or npm.

## License

MIT. The package contains no A12 Kernel bytecode and has no Kernel runtime dependency. It is an independent project, not an official A12 or mgm artifact.

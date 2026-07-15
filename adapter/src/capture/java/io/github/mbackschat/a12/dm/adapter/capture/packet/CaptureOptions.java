package io.github.mbackschat.a12.dm.adapter.capture.packet;

import java.nio.file.Path;

/**
 * One capture run's options — builder-constructed (the standing rule: several same-typed {@link Path}
 * parameters are silently swap-prone as positional arguments).
 */
public record CaptureOptions(
        Path scenarioFile,
        Path outputDir,
        boolean development,
        SourceState sourceState,
        Path repoRoot) {

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Path scenarioFile;
        private Path outputDir;
        private boolean development;
        private SourceState sourceState;
        private Path repoRoot;

        public Builder scenarioFile(Path scenarioFile) {
            this.scenarioFile = scenarioFile;
            return this;
        }

        public Builder outputDir(Path outputDir) {
            this.outputDir = outputDir;
            return this;
        }

        public Builder development(boolean development) {
            this.development = development;
            return this;
        }

        public Builder sourceState(SourceState sourceState) {
            this.sourceState = sourceState;
            return this;
        }

        public Builder repoRoot(Path repoRoot) {
            this.repoRoot = repoRoot;
            return this;
        }

        public CaptureOptions build() {
            if (scenarioFile == null || outputDir == null || sourceState == null || repoRoot == null) {
                throw new IllegalStateException("scenarioFile, outputDir, sourceState, and repoRoot are required");
            }
            return new CaptureOptions(scenarioFile, outputDir, development, sourceState, repoRoot);
        }
    }
}

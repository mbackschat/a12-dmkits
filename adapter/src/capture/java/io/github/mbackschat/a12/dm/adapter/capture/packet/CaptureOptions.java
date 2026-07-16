package io.github.mbackschat.a12.dm.adapter.capture.packet;

import java.nio.file.Path;

/**
 * One capture run's options — builder-constructed (the standing rule: several same-typed {@link Path}
 * parameters are silently swap-prone as positional arguments). The identity PROBE (not a snapshot) rides
 * along because capture resolves the closure identity twice — before execution and again before
 * finalization (audit Finding 5).
 */
public record CaptureOptions(
        Path scenarioFile,
        Path outputDir,
        boolean development,
        ClosureIdentity.Probe identityProbe,
        Path repoRoot) {

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Path scenarioFile;
        private Path outputDir;
        private boolean development;
        private ClosureIdentity.Probe identityProbe;
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

        public Builder identityProbe(ClosureIdentity.Probe identityProbe) {
            this.identityProbe = identityProbe;
            return this;
        }

        public Builder repoRoot(Path repoRoot) {
            this.repoRoot = repoRoot;
            return this;
        }

        public CaptureOptions build() {
            if (scenarioFile == null || outputDir == null || identityProbe == null || repoRoot == null) {
                throw new IllegalStateException("scenarioFile, outputDir, identityProbe, and repoRoot are required");
            }
            return new CaptureOptions(scenarioFile, outputDir, development, identityProbe, repoRoot);
        }
    }
}

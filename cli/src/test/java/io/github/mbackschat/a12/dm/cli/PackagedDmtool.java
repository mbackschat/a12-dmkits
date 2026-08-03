package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Exact-process test seam for the installDist launcher or the native binary selected by the Gradle gate. */
final class PackagedDmtool {

    record Result(int code, String out, String err) {}

    private final Path executable;
    private final Path work;
    private int invocation;

    PackagedDmtool(Path work) {
        this.work = work;
        this.executable = Path.of(System.getProperty("dmtool.executable", "")).toAbsolutePath();
        assertTrue(Files.isExecutable(executable),
                () -> "publicContractTest needs an executable -Ddmtool.executable, got " + executable);
    }

    Result run(List<String> args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(executable.toString());
        command.addAll(args);
        Path out = work.resolve("process-" + invocation + ".out");
        Path err = work.resolve("process-" + invocation + ".err");
        invocation++;
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(work.toFile())
                .redirectOutput(out.toFile())
                .redirectError(err.toFile());
        builder.environment().remove("DMTOOL_CLI");
        builder.environment().remove("RK_NATIVE");
        Process process = builder.start();
        boolean completed = process.waitFor(Duration.ofSeconds(60).toMillis(), TimeUnit.MILLISECONDS);
        if (!completed) {
            process.destroyForcibly();
            throw new AssertionError("packaged dmtool timed out: " + command);
        }
        return new Result(process.exitValue(), Files.readString(out), Files.readString(err));
    }

    Result run(String... args) throws Exception {
        return run(List.of(args));
    }
}

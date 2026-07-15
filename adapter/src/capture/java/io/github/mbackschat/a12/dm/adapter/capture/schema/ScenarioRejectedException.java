package io.github.mbackschat.a12.dm.adapter.capture.schema;

import java.util.List;

/**
 * A scenario set the closed decoder / structural validator refuses — with every problem named and located
 * (the refusal-with-rich-corrective-diagnostics discipline). Refusal happens BEFORE any runner executes and
 * mutates nothing.
 */
public class ScenarioRejectedException extends RuntimeException {

    private final List<String> problems;

    public ScenarioRejectedException(List<String> problems) {
        super("scenario set rejected:\n  " + String.join("\n  ", problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }
}

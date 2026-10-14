package io.github.mbackschat.a12.dm.cli;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.cli.interp.CustomJsWorker;
import io.github.mbackschat.a12.dm.cli.interp.InterpreterEngine;
import io.github.mbackschat.a12.dm.cli.interp.PredefinedTypesFile;
import io.github.mbackschat.a12.dm.interpreter.model.CustomCondition;
import io.github.mbackschat.a12.dm.interpreter.model.CustomFieldType;
import io.github.mbackschat.a12.dm.interpreter.model.PredefinedType;
import io.github.mbackschat.a12.dm.interpreter.model.PredefinedTypeRegistry;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Shared custom registries and support policy for {@code model eval}, {@code rule eval} and {@code model compute}.
 * Project JavaScript runs through a persistent Node worker; library consumers can register callbacks in-process.
 * Missing or unusable handlers remain visible in the result, and strict mode refuses that degraded observation.
 */
@Command // a mixin must be a "command"
public final class CustomSupportOptions {

    @Option(names = "--predefined-types", paramLabel = "<file>",
            description = "a declarative custom-field-type registry (JSON: { \"<TypeName>\": { minLength?, "
                    + "maxLength?, pattern? } }), merged over the built-ins; lets a format-constraint custom "
                    + "type validate instead of being reported unsupported. Interpreter-only.")
    String predefinedTypesPath;

    @Option(names = "--strict-custom",
            description = "fail (exit 2) instead of degrading when the engine can't evaluate a custom "
                    + "construct because its handler is missing or unusable; retain the observed data and "
                    + "unsupported details. Interpreter-only.")
    boolean strictCustom;

    @Option(names = "--custom-conditions-js", paramLabel = "<dir>",
            description = "a directory of project JS custom-condition impls (<Name>.js, exporting "
                    + "check(data, relevantEntities, formallyIncorrectFields, errorField)); run via a persistent "
                    + "Node worker so a model's imperative CustomConditions evaluate. Needs `node`; if absent, "
                    + "the condition stays unsupported. Interpreter-only.")
    String customConditionsJsDir;

    @Option(names = "--custom-field-types-js", paramLabel = "<dir>",
            description = "a directory of project JS custom-field-type impls (<Name>.js, exporting "
                    + "validate(value, context)); context is {locale,minLength,maxLength,isDisplayValue}; return "
                    + "boolean or {valid,errorKey?,errorMessage?}. Run via the same Node worker so an imperative custom field type "
                    + "(a checksum) validates instead of being reported unsupported. Needs `node`; if absent, "
                    + "the value stays unsupported. Interpreter-only.")
    String customFieldTypesJsDir;

    /**
     * The effective predefined-type registry: the built-in defaults ({@code EMail}, …) when no file is given,
     * else {@code defaults + project types} parsed from {@link #predefinedTypesPath}.
     *
     * @throws IllegalArgumentException on a malformed / unreadable registry file (mapped to a {@code fail()})
     */
    public Map<String, PredefinedType> predefinedTypes() {
        if (predefinedTypesPath == null) {
            return PredefinedTypeRegistry.INSTANCE.getDEFAULT();
        }
        try {
            return PredefinedTypesFile.read(predefinedTypesPath);
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot read --predefined-types: " + e.getMessage());
        }
    }

    /**
     * The project's JS custom conditions ({@code --custom-conditions-js <dir>}), worker-backed and keyed by
     * name; empty when the flag is absent (a model's {@code CustomCondition} then stays unsupported, as before).
     *
     * @throws IllegalArgumentException if the directory can't be listed (mapped to a {@code fail()})
     */
    public Map<String, CustomCondition> customConditions() {
        if (customConditionsJsDir == null) {
            return Map.of();
        }
        try {
            return CustomJsWorker.customConditions(Path.of(customConditionsJsDir));
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot read --custom-conditions-js dir: " + e.getMessage());
        }
    }

    /**
     * The project's JS custom field types ({@code --custom-field-types-js <dir>}), worker-backed and keyed by
     * name; empty when the flag is absent (a custom-typed value then stays unsupported unless covered by
     * {@code --predefined-types}).
     *
     * @throws IllegalArgumentException if the directory can't be listed (mapped to a {@code fail()})
     */
    public Map<String, CustomFieldType> customFieldTypes() {
        if (customFieldTypesJsDir == null) {
            return Map.of();
        }
        try {
            return CustomJsWorker.customFieldTypes(Path.of(customFieldTypesJsDir));
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot read --custom-field-types-js dir: " + e.getMessage());
        }
    }

    /** The registries above, with the evaluation instant {@code Today}/{@code Now} read. */
    public InterpreterEngine.RuntimeInputs runtimeInputs(Instant clock) {
        return new InterpreterEngine.RuntimeInputs(predefinedTypes(), customConditions(), customFieldTypes(), clock);
    }

    /**
     * The refused envelope (exit 2) when {@code --strict-custom} is set AND {@code data} carries a non-empty
     * {@code unsupported} array — the engine couldn't evaluate those constructs and the caller asked for
     * complete custom support. Returns {@code null} when not strict, or when everything was evaluated (proceed with
     * the normal read). The refused envelope keeps the {@code unsupported} list visible in {@code data}.
     */
    public ResultEnvelope strictRefusal(ResultIdentity identity, ObjectNode data, RuntimeEvidence evidence) {
        if (!strictCustom) {
            return null;
        }
        JsonNode unsupported = data.get("unsupported");
        if (unsupported == null || unsupported.isEmpty()) {
            return null;
        }
        return ResultEnvelope.runtimeRefused(identity,
                "--strict-custom: " + unsupported.size() + " construct(s) could not be evaluated (a custom field "
                        + "type with no --predefined-types entry, or a CustomCondition with no impl); supply the "
                        + "missing handler or drop --strict-custom",
                data, List.of(), evidence);
    }
}

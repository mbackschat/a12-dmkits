package io.github.mbackschat.a12.dm.cli;

import java.io.IOException;
import java.util.Map;

import io.github.mbackschat.a12.dm.cli.interp.PredefinedTypesFile;
import io.github.mbackschat.a12.dm.interpreter.model.PredefinedType;
import io.github.mbackschat.a12.dm.interpreter.model.PredefinedTypeRegistry;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * The shared options for supporting models that use <b>custom constructs</b> (custom field types, custom
 * conditions) on the runtime-validation verbs ({@code model eval}, {@code rule test}). A native {@code dmtool}
 * cannot host arbitrary project validator/condition code, so the interpreter degrades leniently — but these
 * two knobs make that honest and useful:
 * <ul>
 *   <li>{@code --predefined-types <file>} (G30) supplies a declarative custom-field-type registry
 *       (length/pattern), so a format-constraint custom type is actually validated instead of skipped.</li>
 * </ul>
 *
 * <p>Both are <b>interpreter-only</b> (the kernel uses its own field-type factory / throws on an unregistered
 * type), so combining either with {@code --kernel} is a usage error — see {@link #kernelConflict(boolean)}.
 */
@Command // a mixin must be a "command"
public final class CustomSupportOptions {

    @Option(names = "--predefined-types", paramLabel = "<file>",
            description = "a declarative custom-field-type registry (JSON: { \"<TypeName>\": { minLength?, "
                    + "maxLength?, pattern? } }), merged over the built-ins; lets a format-constraint custom "
                    + "type validate instead of being reported unsupported. Interpreter-only.")
    String predefinedTypesPath;

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
     * The corrective message if an interpreter-only option here was combined with {@code --kernel}, else
     * {@code null}. The kernel runs its own field-type factory, so these options don't apply to it.
     */
    public String kernelConflict(boolean useKernel) {
        if (useKernel && predefinedTypesPath != null) {
            return "--predefined-types applies to the interpreter; the A12 kernel uses its own field-type "
                    + "factory — drop --kernel or --predefined-types";
        }
        return null;
    }
}

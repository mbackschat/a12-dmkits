package io.github.mbackschat.a12.dm;

import java.util.LinkedHashSet;
import java.util.Set;

import org.opentest4j.TestAbortedException;

/**
 * A12's own error vocabularies, read off the kernel's enums rather than from a list anyone maintains.
 *
 * <p>The kernel answers a model with codes from two families. The {@code MVK_}-prefixed metamodel ones are
 * self-describing, which is why every guard in this estate could recognise them from shape alone. The
 * error-text ones are not — {@code INVALID_FIELD}, {@code NO_BASE_YEAR}, {@code WRONG_NUMBER_OF_DOLLARS} are
 * ordinary screaming-case words — so a scan looking for them needs the enum itself, and without one the
 * checker's whole unprefixed estate sat outside {@link CheckerCodeBasisGuardTest} while it grew to 26 codes
 * (2026-09-15).
 *
 * <p><b>Aborts rather than returning an empty set</b> when the kernel is not on the test classpath. An empty
 * vocabulary would make every consumer pass by finding nothing, which is the fail-open shape a completeness
 * guard exists to rule out.
 */
final class KernelErrorVocabulary {

    /** The enums the ledger already reads for its prefixed half ({@code MvkLedgerTest}), unfiltered here. */
    private static final String[] ENUM_CLASSES = {
            "com.mgmtp.a12.kernel.core.parser.internal.error.ParserErrorTypesEnum",
            "com.mgmtp.a12.kernel.core.calc.internal.util.CalculationErrorType",
            "com.mgmtp.a12.kernel.core.service.internal.errortext.FehlertextErrorType",
            "com.mgmtp.a12.kernel.core.parser.internal.common.error.CommonParserErrorTypesEnum",
    };

    private KernelErrorVocabulary() {
    }

    /** Every constant of those enums whose name does NOT carry the metamodel prefix. */
    static Set<String> errorTextCodes() {
        Set<String> codes = new LinkedHashSet<>();
        for (String className : ENUM_CLASSES) {
            Class<?> enumClass;
            try {
                enumClass = Class.forName(className);
            } catch (ClassNotFoundException e) {
                throw new TestAbortedException("kernel enum not on test classpath: " + className);
            }
            for (Object constant : enumClass.getEnumConstants()) {
                String name = ((Enum<?>) constant).name();
                if (!name.startsWith("MVK_")) {
                    codes.add(name);
                }
            }
        }
        return codes;
    }
}

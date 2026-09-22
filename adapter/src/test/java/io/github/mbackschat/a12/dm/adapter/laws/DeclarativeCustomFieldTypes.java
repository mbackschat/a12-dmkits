package io.github.mbackschat.a12.dm.adapter.laws;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.mgmtp.a12.kernel.core.customfieldtype.ICustomFieldTypeCheckError;
import com.mgmtp.a12.kernel.core.customfieldtype.ICustomFieldTypeValidationParam;
import com.mgmtp.a12.kernel.core.customfieldtype.ICustomFieldTypeFactory;
import com.mgmtp.a12.kernel.core.customfieldtype.ICustomFieldValidator;

/**
 * A custom-field-type registry declared in a probe request, bound to the kernel's {@code ICustomFieldTypeFactory}
 * so a CUSTOM cell is actually validated instead of being unobservable.
 *
 * <p><b>Why a probe needs this at all.</b> A model declaring a {@code CUSTOM} field authors and checks cleanly,
 * and validates for as long as that cell is <em>empty</em>; fill it and both codegen strategies raise out of the
 * whole call, naming the declared type. The kernel resolves a type from the per-call
 * {@code DocumentProcessingConfig.customFieldTypeFactory} and falls back to the static
 * {@code CustomFieldTypeService}, which is populated only by an explicit host {@code register} call — no
 * {@code ServiceLoader}, and the kernel's own implementation module contributes no factory of its own. So both
 * routes are empty unless a request supplies one, which closed an entire semantic family for a12-kernel-lean
 * (their 2026-09-21 note §5: three parked items, one of them marked route-closed rather than pending).
 *
 * <p><b>The shape is dmtool's own {@code --predefined-types} registry</b>, {@code {"<TypeName>": {minLength?,
 * maxLength?, pattern?}}} — the declarative half of the same idea, already published and already understood by
 * a reader of the CLI. Inventing a second spelling for one concept is the defect this project's first interop
 * rule names, and the imperative half ({@code --custom-field-types-js}) is deliberately not mirrored: a probe
 * artifact is pinned by SHA-256, so every input must be reproducible from the request bytes alone.
 *
 * <p><b>Two length sources, both checked, because the kernel supplies one of them.</b> A registry entry's
 * lengths belong to the TYPE; {@code ICustomFieldTypeValidationParam} additionally carries the FIELD's declared
 * {@code minLength}/{@code maxLength}, which A12 states are absent exactly when the call came from the
 * {@code Valid}/{@code Invalid} predicate rather than from formal validation. Checking both is what a real host
 * implementation does, and the absent-parameter case is itself observable — which is the distinction the
 * predicates' runtime half needs. Each failure names the constraint that produced it in its {@code errorKey},
 * so a consumer can tell the gates apart rather than inferring from message prose.
 */
final class DeclarativeCustomFieldTypes {

    /** One declared type: any subset of the three constraints, each absent meaning "unconstrained". */
    record Declared(Integer minLength, Integer maxLength, Pattern pattern) {

        static Declared of(String typeName, Integer minLength, Integer maxLength, String pattern) {
            if (minLength != null && maxLength != null && minLength > maxLength) {
                throw new IllegalArgumentException("custom field type \"" + typeName
                        + "\": minLength " + minLength + " exceeds maxLength " + maxLength);
            }
            try {
                return new Declared(minLength, maxLength, pattern == null ? null : Pattern.compile(pattern));
            } catch (PatternSyntaxException bad) {
                throw new IllegalArgumentException("custom field type \"" + typeName
                        + "\": pattern is not a valid regular expression: " + bad.getDescription());
            }
        }
    }

    private DeclarativeCustomFieldTypes() {
    }

    /**
     * The factory a request's registry binds to, or {@code null} for an empty registry — which is the
     * pre-existing behaviour exactly, so a request that declares no type produces the artifact it always did.
     */
    static ICustomFieldTypeFactory factoryOf(Map<String, Declared> registry) {
        if (registry.isEmpty()) {
            return null;
        }
        Map<String, Declared> declared = Map.copyOf(new LinkedHashMap<>(registry));
        // An anonymous class rather than a lambda: A12's factory and validator interfaces each carry more than
        // one abstract method, so neither is functional.
        return new ICustomFieldTypeFactory() {

            @Override
            public Optional<ICustomFieldValidator> createCustomFieldValidator(String name) {
                return Optional.ofNullable(declared.get(name)).map(DeclarativeCustomFieldTypes::validator);
            }
        };
    }

    private static ICustomFieldValidator validator(Declared type) {
        return new ICustomFieldValidator() {

            @Override
            public Optional<ICustomFieldTypeCheckError> validate(
                    String value, ICustomFieldTypeValidationParam param, boolean isDisplayValue) {
                return check(type, value, param);
            }
        };
    }

    private static Optional<ICustomFieldTypeCheckError> check(
            Declared type, String value, ICustomFieldTypeValidationParam param) {
        if (type.pattern() != null && !type.pattern().matcher(value).matches()) {
            return Optional.of(error("customFieldType.pattern",
                    "value does not match the declared pattern " + type.pattern().pattern()));
        }
        Optional<ICustomFieldTypeCheckError> typeLength =
                length(value, type.minLength(), type.maxLength(), "customFieldType");
        if (typeLength.isPresent()) {
            return typeLength;
        }
        // The kernel's own parameters, absent exactly when the call is a Valid/Invalid predicate rather than a
        // formal field validation — so this branch not firing is itself the observation for that distinction.
        return length(value, param.getMinLength().orElse(null), param.getMaxLength().orElse(null), "field");
    }

    private static Optional<ICustomFieldTypeCheckError> length(
            String value, Integer min, Integer max, String source) {
        if (min != null && value.length() < min) {
            return Optional.of(error(source + ".minLength",
                    "value is shorter than the " + source + "'s declared minimum of " + min));
        }
        if (max != null && value.length() > max) {
            return Optional.of(error(source + ".maxLength",
                    "value is longer than the " + source + "'s declared maximum of " + max));
        }
        return Optional.empty();
    }

    private static ICustomFieldTypeCheckError error(String key, String message) {
        return new ICustomFieldTypeCheckError() {

            @Override
            public String getErrorMessage() {
                return message;
            }

            @Override
            public String getErrorKey() {
                return key;
            }
        };
    }
}

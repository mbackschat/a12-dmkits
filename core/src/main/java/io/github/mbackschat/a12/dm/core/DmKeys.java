package io.github.mbackschat.a12.dm.core;

/**
 * The DM-JSON <b>structural</b> vocabulary — the object keys that spell the model/element skeleton, named
 * once (M9). Before this, keys like {@code "type"}/{@code "elements"}/{@code "content"} appeared as bare
 * string literals ~190× across the kits' tree surgery ({@code ModelWriter}'s primitives, {@code ModelDocument},
 * {@code DocumentModelDraft}), each a
 * silent typo risk with no single source. These constants are that source; the element {@code type}
 * discriminator's closed value set is {@link ElementType} (an enum, not loose strings).
 *
 * <p>Scope: the model/element <em>skeleton</em> keys and the model-level config keys. The per-kind
 * <em>fieldType-block</em> internals ({@code minLength}, {@code pattern}, {@code minValue}, {@code trait},
 * …) are a separate, self-contained cluster (they live almost entirely in {@code DocumentModelDraft}'s
 * type-config read/write) and are deliberately left out of this skeleton vocabulary.
 */
public final class DmKeys {

    private DmKeys() {
    }

    // --- the element skeleton: {"type": <ElementType>, "id": …, "name": …, "<Type>": {"elements": […]}} ---
    /** The element-kind discriminator key; its value set is {@link ElementType}. */
    public static final String TYPE = "type";
    public static final String ID = "id";
    public static final String NAME = "name";
    public static final String ELEMENTS = "elements";

    // --- the model skeleton ---
    public static final String CONTENT = "content";
    public static final String HEADER = "header";
    public static final String MODEL_ROOT = "modelRoot";
    public static final String ROOT_GROUPS = "rootGroups";
    public static final String MODEL_REFERENCES = "modelReferences";
    public static final String TYPE_DEFINITIONS = "typeDefinitions";
    public static final String MODEL_INFO = "modelInfo";
    public static final String MODEL_CONFIG = "modelConfig";
    public static final String MODEL_TYPE = "modelType";
    public static final String DOCUMENT = "document";
    public static final String INCLUDE = "include";

    // --- common element attributes ---
    public static final String FIELD_TYPE = "fieldType";
    public static final String REQUIREDNESS_CONFIG = "requirednessConfig";
    public static final String ANNOTATIONS = "annotations";
    public static final String LOCALE = "locale";
    public static final String LOCALES = "locales";
    public static final String TEXT = "text";
    public static final String REFERENCE = "reference";
    /** The DM 28 mount key, gone since DM 29.0 (A12K-4102). Read only to refuse it: A12 drops it silently (KF651). */
    public static final String MODEL_ALIAS = "modelAlias";
    /** A mount group's include directive, {@code {reference, includeLevel?, excludeRules?, excludeComputations?}}. */
    public static final String INCLUDE_CONFIG = "includeConfig";
    public static final String INCLUDE_LEVEL = "includeLevel";
    public static final String EXCLUDE_RULES = "excludeRules";
    public static final String EXCLUDE_COMPUTATIONS = "excludeComputations";
    public static final String PURPOSE = "purpose";
    public static final String REPEATABILITY = "repeatability";
    public static final String USAGE_TYPE = "usageType";
    public static final String TYPE_DEFINITION_ID = "typeDefinitionId";
    public static final String SORT_FIELDS = "sortFields";
    public static final String INDEX_FIELD_NAME = "indexFieldName";
    public static final String INTERNAL_DESCRIPTION = "internalDescription";
    public static final String EXTERNAL_DESCRIPTION = "externalDescription";
    public static final String COMMENT = "comment";
    public static final String ERROR_MESSAGE = "errorMessage";
    public static final String LABEL = "label";
    public static final String LABELS = "labels";
    public static final String VALUE = "value";
    public static final String VALUES = "values";
    public static final String CODE = "code";
    public static final String ORDER = "order";
    public static final String DESCRIPTION = "description";

    // --- rule body ---
    public static final String ERROR_ENTITY_REL_PATH = "errorEntityRelPath";
    public static final String ERROR_CONDITION = "errorCondition";
    public static final String ERROR_CODE = "errorCode";
    public static final String SEVERITY = "severity";

    // --- model-level config (modelConfig / modelInfo settings) ---
    public static final String CONDITION_LANGUAGE = "conditionLanguage";
    public static final String SUPPORTED_CHARACTERS = "supportedCharacters";
    public static final String DECIMAL_SEPARATOR = "decimalSeparator";
    public static final String TIME_ZONE = "timeZone";
    public static final String BASE_YEAR = "baseYear";
    public static final String FIELD_REF_BY_SHORT_NAME_ALLOWED = "fieldRefByShortNameAllowed";
}

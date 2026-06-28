package io.github.mbackschat.a12.dm.schemakit;

/**
 * The JSON-Schema dialect of the input/output (SCHEMAKIT-SPEC §4g). OpenAPI's "Schema Object" is a
 * <em>dialect</em> of JSON Schema, and the dialects differ enough to change how the importer parses and the
 * exporter emits:
 *
 * <ul>
 *   <li>{@link #JSON_SCHEMA} — Draft 2020-12: {@code type:[X,"null"]} for nullability, <em>numeric</em>
 *       {@code exclusiveMinimum}/{@code exclusiveMaximum}, {@code $defs} for reusable definitions.</li>
 *   <li>{@link #OPENAPI_31} — OpenAPI 3.1 / 3.2, which <em>is</em> Draft 2020-12 plus three
 *       version-independent OpenAPI-isms: refs live under {@code components/schemas}, an explicit
 *       {@code discriminator} object, and the OpenAPI {@code format} set.</li>
 *   <li>{@link #OPENAPI_30} — OpenAPI 3.0, a Draft-05 variant: {@code nullable:true} (no {@code type} arrays),
 *       <em>boolean</em> {@code exclusiveMinimum}/{@code exclusiveMaximum} paired with {@code minimum}/
 *       {@code maximum}, refs under {@code components/schemas}.</li>
 *   <li>{@link #AUTO} — detect from the document (the {@code openapi:} version field, else {@code $schema}),
 *       defaulting to {@link #JSON_SCHEMA} when neither is present.</li>
 * </ul>
 *
 * <p>{@code OPENAPI_31} covers OpenAPI 3.2 as well — 3.2 introduced zero JSON-Schema dialect changes over 3.1.
 */
public enum Dialect {
    AUTO,
    JSON_SCHEMA,
    OPENAPI_30,
    OPENAPI_31;

    /** Any OpenAPI dialect — {@code components/schemas} refs, the explicit {@code discriminator}, OpenAPI formats. */
    public boolean isOpenApi() {
        return this == OPENAPI_30 || this == OPENAPI_31;
    }

    /** OpenAPI 3.0 specifically — {@code nullable:true} and the <em>boolean</em> exclusive-bound encoding. */
    public boolean isOpenApi30() {
        return this == OPENAPI_30;
    }
}

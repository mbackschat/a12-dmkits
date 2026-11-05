package io.github.mbackschat.a12.dm;

import static java.util.Map.entry;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Role-based guard for JSON construction in production code.
 *
 * <p>A default JSON parser is correct for a dmtool-owned envelope, profile, or catalog and wrong at an A12
 * DM-JSON / Document-JSON boundary. Three manual sweeps missed that distinction by searching one module or one
 * call path at a time (API-FINDINGS 103 / INTERPRETER-FINDINGS IF228). This guard makes the complete production
 * source enumeration executable: every Jackson mapper construction and every use of kotlinx.serialization's
 * default {@code Json} instance must have one reviewed, non-blank role basis below. A new construction fails
 * closed; deleting or replacing one makes its stale allowlist row fail too.
 *
 * <p>The key is stable across line moves: {@code relative-file :: construction-kind # same-kind ordinal}.
 * A second construction of the same kind in an already-reviewed file therefore receives a new key and cannot
 * hide behind the existing row.
 */
class A12JsonConstructionGuardTest {

    private static final Checkout CHECKOUT = Checkout.locate();

    private static final List<String> SOURCE_ROOTS = List.of(
            "core/src/main",
            "modelkit/src/main",
            "rulekit/src/main",
            "edit/src/main",
            "schemakit/src/main",
            "cli/src/main",
            "adapter/src/main",
            "interpreter-bridge/src/main",
            "interpreter/src/commonMain",
            "interpreter/src/jvmMain",
            "interpreter/src/jsMain");

    private static final Pattern JACKSON_OBJECT_MAPPER = Pattern.compile(
            "\\bnew\\s+(?:[A-Za-z_$][\\w$]*\\.)*(?:ObjectMapper|JsonMapper|YAMLMapper)\\s*\\(");
    private static final Pattern JACKSON_JSON_MAPPER_BUILDER = Pattern.compile(
            "\\bJsonMapper\\s*\\.\\s*builder\\s*\\(");
    private static final Pattern KOTLINX_DEFAULT_JSON = Pattern.compile(
            "\\bJson\\s*(?:\\{|\\.\\s*(?:parseToJsonElement|encodeToString|decodeFromString|"
                    + "decodeFromJsonElement|encodeToJsonElement|decodeFromStream|encodeToStream)\\b)");

    private static final String A12_MODEL_CODEC =
            "A12 DM-JSON boundary; its mapper is explicitly configured for comments and lexical number fidelity.";
    private static final String A12_DOCUMENT_CODEC =
            "A12 Document JSON boundary; it uses the shared A12 codec or an explicitly fidelity-preserving writer.";
    private static final String A12_INTERPRETER_CODEC =
            "shared interpreter codec for A12 DM-JSON and Document JSON, configured with A12 comment behavior.";
    private static final String AUTH_CONFIG =
            "dmkit-owned roles/users YAML configuration, not A12 model or document content.";
    private static final String RULEKIT_OWNED_JSON =
            "rulekit-owned catalog, explanation, diff, or result projection, not A12 model or document content.";
    private static final String NODE_FACTORY_ONLY =
            "creates Jackson tree containers only; it neither parses nor serializes transport text.";
    private static final String EXTERNAL_SCHEMA_CODEC =
            "external JSON Schema/OpenAPI boundary; its JSON and YAML parsers explicitly preserve decimal scale.";
    private static final String CLI_OWNED_JSON =
            "dmtool-owned command spec, diagnostic, result envelope, or projection, not A12 content.";
    /**
     * The MCP layer gets its own role rather than riding {@link #CLI_OWNED_JSON}, because it is the one
     * dmtool-owned frame that <em>carries</em> A12 content: a request brings a model's DM-JSON and a result
     * hands the edited bytes back. What keeps a default mapper correct there is that the content is never
     * parsed at this layer — it crosses as opaque text and the CLI does the parsing it already does
     * correctly. State the invariant here, so a future change that starts parsing model content in the MCP
     * layer has to come back and confront this row.
     */
    private static final String MCP_FRAME_JSON =
            "MCP JSON-RPC frame this server owns; A12 content crosses it as opaque text, never parsed here.";
    private static final String INTERPRETER_OWNED_JSON =
            "dmtool interpreter worker/configuration/selection shape, not A12 model or document content.";
    private static final String PROFILE_JSON =
            "dmtool-owned synthesis profile or profile result projection, not A12 model or document content.";
    private static final String REVIEW_JSON =
            "adapter review-owned diff/delta projection, not A12 model or document content.";
    private static final String CONFORMANCE_CASE_JSON =
            "dmkit-owned conformance-case envelope; embedded A12 model/document members use A12_JSON separately.";
    private static final String JS_LIMITS_JSON =
            "JavaScript bridge parses only dmkit-owned preparation/runtime limit objects, not A12 content.";

    /**
     * Exact construction-site allowlist, shaped like {@code SchemaCommand.CONTRACT_EXEMPT}: one site, one stated
     * reason. Rows are populated only after reading the call site and classifying the shape it parses or emits.
     */
    private static final Map<String, String> JSON_CONSTRUCTION_ALLOWLIST = Map.ofEntries(
            entry("core/src/main/java/io/github/mbackschat/a12/dm/core/ModelReader.java"
                    + "::jackson-json-mapper-builder#1", A12_MODEL_CODEC),
            entry("core/src/main/java/io/github/mbackschat/a12/dm/core/ReferenceResolvers.java"
                    + "::jackson-json-mapper-builder#1", A12_MODEL_CODEC),
            entry("modelkit/src/main/java/io/github/mbackschat/a12/dm/modelkit/DocumentModelDraft.java"
                    + "::jackson-json-mapper-builder#1", A12_MODEL_CODEC),
            entry("modelkit/src/main/java/io/github/mbackschat/a12/dm/modelkit/ModelDocument.java"
                    + "::jackson-json-mapper-builder#1", A12_MODEL_CODEC),
            entry("modelkit/src/main/java/io/github/mbackschat/a12/dm/modelkit/RolesFile.java"
                    + "::jackson-object-mapper#1", AUTH_CONFIG),
            entry("modelkit/src/main/java/io/github/mbackschat/a12/dm/modelkit/UsersFile.java"
                    + "::jackson-object-mapper#1", AUTH_CONFIG),

            entry("rulekit/src/main/java/io/github/mbackschat/a12/dm/rulekit/catalog/OperatorCatalog.java"
                    + "::jackson-object-mapper#1", RULEKIT_OWNED_JSON),
            entry("rulekit/src/main/java/io/github/mbackschat/a12/dm/rulekit/context/ComputationExplanation.java"
                    + "::jackson-object-mapper#1", RULEKIT_OWNED_JSON),
            entry("rulekit/src/main/java/io/github/mbackschat/a12/dm/rulekit/context/ModelCard.java"
                    + "::jackson-object-mapper#1", RULEKIT_OWNED_JSON),
            entry("rulekit/src/main/java/io/github/mbackschat/a12/dm/rulekit/context/RuleExplanation.java"
                    + "::jackson-object-mapper#1", RULEKIT_OWNED_JSON),
            entry("rulekit/src/main/java/io/github/mbackschat/a12/dm/rulekit/dsl/RenderedDiff.java"
                    + "::jackson-object-mapper#1", RULEKIT_OWNED_JSON),
            entry("rulekit/src/main/java/io/github/mbackschat/a12/dm/rulekit/validate/ModelVersion.java"
                    + "::jackson-json-mapper-builder#1", A12_MODEL_CODEC),
            entry("rulekit/src/main/java/io/github/mbackschat/a12/dm/rulekit/validate/RuleValidator.java"
                    + "::jackson-json-mapper-builder#1", A12_MODEL_CODEC),
            entry("rulekit/src/main/java/io/github/mbackschat/a12/dm/rulekit/validate/ValidationResult.java"
                    + "::jackson-object-mapper#1", RULEKIT_OWNED_JSON),
            entry("rulekit/src/main/java/io/github/mbackschat/a12/dm/rulekit/write/DmJson.java"
                    + "::jackson-json-mapper-builder#1", A12_MODEL_CODEC),
            entry("rulekit/src/main/java/io/github/mbackschat/a12/dm/rulekit/write/ModelWriter.java"
                    + "::jackson-json-mapper-builder#1", A12_MODEL_CODEC),

            entry("schemakit/src/main/java/io/github/mbackschat/a12/dm/schemakit/Carriage.java"
                    + "::jackson-object-mapper#1", NODE_FACTORY_ONLY),
            entry("schemakit/src/main/java/io/github/mbackschat/a12/dm/schemakit/JsonSchemaExporter.java"
                    + "::jackson-json-mapper-builder#1", A12_MODEL_CODEC),
            entry("schemakit/src/main/java/io/github/mbackschat/a12/dm/schemakit/JsonSchemaImporter.java"
                    + "::jackson-object-mapper#1", EXTERNAL_SCHEMA_CODEC),
            entry("schemakit/src/main/java/io/github/mbackschat/a12/dm/schemakit/JsonSchemaImporter.java"
                    + "::jackson-json-mapper-builder#1", EXTERNAL_SCHEMA_CODEC),

            entry("cli/src/main/java/io/github/mbackschat/a12/dm/cli/ApplyCommand.java"
                    + "::jackson-object-mapper#1", CLI_OWNED_JSON),
            entry("cli/src/main/java/io/github/mbackschat/a12/dm/cli/BaseCommand.java"
                    + "::jackson-object-mapper#1", CLI_OWNED_JSON),
            entry("cli/src/main/java/io/github/mbackschat/a12/dm/cli/BatchCommand.java"
                    + "::jackson-object-mapper#1", CLI_OWNED_JSON),
            entry("cli/src/main/java/io/github/mbackschat/a12/dm/cli/BatchObservationCapture.java"
                    + "::jackson-object-mapper#1", NODE_FACTORY_ONLY),
            entry("cli/src/main/java/io/github/mbackschat/a12/dm/cli/CommandContracts.java"
                    + "::jackson-object-mapper#1", CLI_OWNED_JSON),
            entry("cli/src/main/java/io/github/mbackschat/a12/dm/cli/DiagnosticsCommand.java"
                    + "::jackson-object-mapper#1", CLI_OWNED_JSON),
            entry("cli/src/main/java/io/github/mbackschat/a12/dm/cli/ExportCommand.java"
                    + "::jackson-json-mapper-builder#1", A12_MODEL_CODEC),
            entry("cli/src/main/java/io/github/mbackschat/a12/dm/cli/FormatVerbs.java"
                    + "::jackson-object-mapper#1", CLI_OWNED_JSON),
            entry("cli/src/main/java/io/github/mbackschat/a12/dm/cli/ManifestCommand.java"
                    + "::jackson-object-mapper#1", CLI_OWNED_JSON),
            entry("cli/src/main/java/io/github/mbackschat/a12/dm/cli/McpDispatcher.java"
                    + "::jackson-object-mapper#1", MCP_FRAME_JSON),
            entry("cli/src/main/java/io/github/mbackschat/a12/dm/cli/McpFrames.java"
                    + "::jackson-object-mapper#1", MCP_FRAME_JSON),
            entry("cli/src/main/java/io/github/mbackschat/a12/dm/cli/McpHttpBinding.java"
                    + "::jackson-object-mapper#1", MCP_FRAME_JSON),
            entry("cli/src/main/java/io/github/mbackschat/a12/dm/cli/McpInvoker.java"
                    + "::jackson-object-mapper#1", MCP_FRAME_JSON),
            entry("cli/src/main/java/io/github/mbackschat/a12/dm/cli/McpToolRegistry.java"
                    + "::jackson-object-mapper#1", MCP_FRAME_JSON),
            entry("cli/src/main/java/io/github/mbackschat/a12/dm/cli/McpStdioBinding.java"
                    + "::jackson-object-mapper#1", MCP_FRAME_JSON),
            entry("cli/src/main/java/io/github/mbackschat/a12/dm/cli/MetaCommand.java"
                    + "::jackson-object-mapper#1", CLI_OWNED_JSON),
            entry("cli/src/main/java/io/github/mbackschat/a12/dm/cli/OperatorsCommand.java"
                    + "::jackson-object-mapper#1", CLI_OWNED_JSON),
            entry("cli/src/main/java/io/github/mbackschat/a12/dm/cli/PatternsCommand.java"
                    + "::jackson-object-mapper#1", CLI_OWNED_JSON),
            entry("cli/src/main/java/io/github/mbackschat/a12/dm/cli/ReadPayloads.java"
                    + "::jackson-object-mapper#1", CLI_OWNED_JSON),
            entry("cli/src/main/java/io/github/mbackschat/a12/dm/cli/ReferenceRewritingRefactorCommand.java"
                    + "::jackson-object-mapper#1", CLI_OWNED_JSON),
            entry("cli/src/main/java/io/github/mbackschat/a12/dm/cli/ResultEnvelope.java"
                    + "::jackson-object-mapper#1", CLI_OWNED_JSON),
            entry("cli/src/main/java/io/github/mbackschat/a12/dm/cli/SafetyChecks.java"
                    + "::jackson-object-mapper#1", CLI_OWNED_JSON),
            entry("cli/src/main/java/io/github/mbackschat/a12/dm/cli/SchemaCommand.java"
                    + "::jackson-object-mapper#1", CLI_OWNED_JSON),
            entry("cli/src/main/java/io/github/mbackschat/a12/dm/cli/SpecKeys.java"
                    + "::jackson-object-mapper#1", CLI_OWNED_JSON),
            entry("cli/src/main/java/io/github/mbackschat/a12/dm/cli/TextRenderer.java"
                    + "::jackson-object-mapper#1", CLI_OWNED_JSON),
            entry("cli/src/main/java/io/github/mbackschat/a12/dm/cli/WhereUsedCommand.java"
                    + "::jackson-object-mapper#1", CLI_OWNED_JSON),

            entry("cli/src/main/java/io/github/mbackschat/a12/dm/cli/computation/ComputationAddCommand.java"
                    + "::jackson-object-mapper#1", CLI_OWNED_JSON),
            entry("cli/src/main/java/io/github/mbackschat/a12/dm/cli/computation/ComputationExplainCommand.java"
                    + "::jackson-object-mapper#1", CLI_OWNED_JSON),
            entry("cli/src/main/java/io/github/mbackschat/a12/dm/cli/computation/ComputationModifyCommand.java"
                    + "::jackson-object-mapper#1", CLI_OWNED_JSON),
            entry("cli/src/main/java/io/github/mbackschat/a12/dm/cli/computation/ComputationRemoveCommand.java"
                    + "::jackson-object-mapper#1", CLI_OWNED_JSON),
            entry("cli/src/main/java/io/github/mbackschat/a12/dm/cli/config/ConfigModifyCommand.java"
                    + "::jackson-object-mapper#1", CLI_OWNED_JSON),

            entry("cli/src/main/java/io/github/mbackschat/a12/dm/cli/field/FieldAddCommand.java"
                    + "::jackson-object-mapper#1", CLI_OWNED_JSON),
            entry("cli/src/main/java/io/github/mbackschat/a12/dm/cli/field/FieldModifyCommand.java"
                    + "::jackson-object-mapper#1", CLI_OWNED_JSON),
            entry("cli/src/main/java/io/github/mbackschat/a12/dm/cli/field/FieldRemoveCommand.java"
                    + "::jackson-object-mapper#1", CLI_OWNED_JSON),

            entry("cli/src/main/java/io/github/mbackschat/a12/dm/cli/group/GroupAddCommand.java"
                    + "::jackson-object-mapper#1", CLI_OWNED_JSON),
            entry("cli/src/main/java/io/github/mbackschat/a12/dm/cli/group/GroupAttachmentCommand.java"
                    + "::jackson-object-mapper#1", CLI_OWNED_JSON),
            entry("cli/src/main/java/io/github/mbackschat/a12/dm/cli/group/GroupExtractCommand.java"
                    + "::jackson-object-mapper#1", CLI_OWNED_JSON),
            entry("cli/src/main/java/io/github/mbackschat/a12/dm/cli/group/GroupModifyCommand.java"
                    + "::jackson-object-mapper#1", CLI_OWNED_JSON),
            entry("cli/src/main/java/io/github/mbackschat/a12/dm/cli/group/GroupMultiSelectCommand.java"
                    + "::jackson-object-mapper#1", CLI_OWNED_JSON),
            entry("cli/src/main/java/io/github/mbackschat/a12/dm/cli/group/GroupRemoveCommand.java"
                    + "::jackson-object-mapper#1", CLI_OWNED_JSON),

            entry("cli/src/main/java/io/github/mbackschat/a12/dm/cli/criterion/CriterionAddCommand.java"
                    + "::jackson-object-mapper#1", CLI_OWNED_JSON),
            entry("cli/src/main/java/io/github/mbackschat/a12/dm/cli/criterion/CriterionRemoveCommand.java"
                    + "::jackson-object-mapper#1", CLI_OWNED_JSON),

            entry("cli/src/main/java/io/github/mbackschat/a12/dm/cli/include/IncludeAddCommand.java"
                    + "::jackson-object-mapper#1", CLI_OWNED_JSON),
            entry("cli/src/main/java/io/github/mbackschat/a12/dm/cli/include/IncludeInlineCommand.java"
                    + "::jackson-object-mapper#1", CLI_OWNED_JSON),
            entry("cli/src/main/java/io/github/mbackschat/a12/dm/cli/include/IncludeReadCommand.java"
                    + "::jackson-object-mapper#1", CLI_OWNED_JSON),
            entry("cli/src/main/java/io/github/mbackschat/a12/dm/cli/include/IncludeRemoveCommand.java"
                    + "::jackson-object-mapper#1", CLI_OWNED_JSON),
            entry("cli/src/main/java/io/github/mbackschat/a12/dm/cli/include/IncludeSetReferenceCommand.java"
                    + "::jackson-object-mapper#1", CLI_OWNED_JSON),

            entry("cli/src/main/java/io/github/mbackschat/a12/dm/cli/interp/CustomJsWorker.java"
                    + "::jackson-object-mapper#1", INTERPRETER_OWNED_JSON),
            entry("cli/src/main/java/io/github/mbackschat/a12/dm/cli/interp/InterpreterEngine.java"
                    + "::jackson-object-mapper#1", CLI_OWNED_JSON),
            entry("cli/src/main/java/io/github/mbackschat/a12/dm/cli/interp/InterpreterModelLoader.java"
                    + "::jackson-json-mapper-builder#1", A12_MODEL_CODEC),
            entry("cli/src/main/java/io/github/mbackschat/a12/dm/cli/interp/PredefinedTypesFile.java"
                    + "::jackson-object-mapper#1", INTERPRETER_OWNED_JSON),
            entry("cli/src/main/java/io/github/mbackschat/a12/dm/cli/interp/RelevantSelection.java"
                    + "::jackson-object-mapper#1", INTERPRETER_OWNED_JSON),

            entry("cli/src/main/java/io/github/mbackschat/a12/dm/cli/model/ModelDescribeCommand.java"
                    + "::jackson-object-mapper#1", CLI_OWNED_JSON),
            entry("cli/src/main/java/io/github/mbackschat/a12/dm/cli/model/ModelDiffCommand.java"
                    + "::jackson-object-mapper#1", CLI_OWNED_JSON),
            entry("cli/src/main/java/io/github/mbackschat/a12/dm/cli/model/ModelExportJsonschemaCommand.java"
                    + "::jackson-object-mapper#1", CLI_OWNED_JSON),
            entry("cli/src/main/java/io/github/mbackschat/a12/dm/cli/model/ModelImportJsonschemaCommand.java"
                    + "::jackson-object-mapper#1", CLI_OWNED_JSON),
            entry("cli/src/main/java/io/github/mbackschat/a12/dm/cli/model/ModelInfoCommand.java"
                    + "::jackson-object-mapper#1", CLI_OWNED_JSON),
            entry("cli/src/main/java/io/github/mbackschat/a12/dm/cli/model/ModelNewCommand.java"
                    + "::jackson-object-mapper#1", CLI_OWNED_JSON),
            entry("cli/src/main/java/io/github/mbackschat/a12/dm/cli/model/ModelNormalizeCommand.java"
                    + "::jackson-object-mapper#1", CLI_OWNED_JSON),
            entry("cli/src/main/java/io/github/mbackschat/a12/dm/cli/model/ModelReadCommand.java"
                    + "::jackson-object-mapper#1", CLI_OWNED_JSON),
            entry("cli/src/main/java/io/github/mbackschat/a12/dm/cli/model/ModelRenameCommand.java"
                    + "::jackson-object-mapper#1", CLI_OWNED_JSON),
            entry("cli/src/main/java/io/github/mbackschat/a12/dm/cli/model/ModelReportCommand.java"
                    + "::jackson-object-mapper#1", CLI_OWNED_JSON),
            entry("cli/src/main/java/io/github/mbackschat/a12/dm/cli/model/ModelSeedCommand.java"
                    + "::jackson-object-mapper#1", A12_DOCUMENT_CODEC),
            entry("cli/src/main/java/io/github/mbackschat/a12/dm/cli/model/ModelTreeData.java"
                    + "::jackson-json-mapper-builder#1", A12_MODEL_CODEC),
            entry("cli/src/main/java/io/github/mbackschat/a12/dm/cli/model/ModelUsageCommand.java"
                    + "::jackson-object-mapper#1", CLI_OWNED_JSON),

            entry("cli/src/main/java/io/github/mbackschat/a12/dm/cli/profile/ProfileCompareCommand.java"
                    + "::jackson-object-mapper#1", PROFILE_JSON),
            entry("cli/src/main/java/io/github/mbackschat/a12/dm/cli/profile/ProfileExtractCommand.java"
                    + "::jackson-object-mapper#1", PROFILE_JSON),
            entry("cli/src/main/java/io/github/mbackschat/a12/dm/cli/profile/ProfileSynthesizeCommand.java"
                    + "::jackson-object-mapper#1", PROFILE_JSON),

            entry("cli/src/main/java/io/github/mbackschat/a12/dm/cli/rule/RuleAddCommand.java"
                    + "::jackson-object-mapper#1", CLI_OWNED_JSON),
            entry("cli/src/main/java/io/github/mbackschat/a12/dm/cli/rule/RuleCheckCommand.java"
                    + "::jackson-object-mapper#1", CLI_OWNED_JSON),
            entry("cli/src/main/java/io/github/mbackschat/a12/dm/cli/rule/RuleDepsCommand.java"
                    + "::jackson-object-mapper#1", CLI_OWNED_JSON),
            entry("cli/src/main/java/io/github/mbackschat/a12/dm/cli/rule/RuleExplainData.java"
                    + "::jackson-object-mapper#1", CLI_OWNED_JSON),
            entry("cli/src/main/java/io/github/mbackschat/a12/dm/cli/rule/RuleModifyCommand.java"
                    + "::jackson-object-mapper#1", CLI_OWNED_JSON),
            entry("cli/src/main/java/io/github/mbackschat/a12/dm/cli/rule/RuleRemoveCommand.java"
                    + "::jackson-object-mapper#1", CLI_OWNED_JSON),
            entry("cli/src/main/java/io/github/mbackschat/a12/dm/cli/rule/RuleRenameCommand.java"
                    + "::jackson-object-mapper#1", CLI_OWNED_JSON),

            entry("cli/src/main/java/io/github/mbackschat/a12/dm/cli/typedef/TypedefAddCommand.java"
                    + "::jackson-object-mapper#1", CLI_OWNED_JSON),
            entry("cli/src/main/java/io/github/mbackschat/a12/dm/cli/typedef/TypedefExtractCommand.java"
                    + "::jackson-object-mapper#1", CLI_OWNED_JSON),
            entry("cli/src/main/java/io/github/mbackschat/a12/dm/cli/typedef/TypedefImportCommand.java"
                    + "::jackson-object-mapper#1", CLI_OWNED_JSON),
            entry("cli/src/main/java/io/github/mbackschat/a12/dm/cli/typedef/TypedefInlineCommand.java"
                    + "::jackson-object-mapper#1", CLI_OWNED_JSON),
            entry("cli/src/main/java/io/github/mbackschat/a12/dm/cli/typedef/TypedefModifyCommand.java"
                    + "::jackson-object-mapper#1", CLI_OWNED_JSON),
            entry("cli/src/main/java/io/github/mbackschat/a12/dm/cli/typedef/TypedefReadCommand.java"
                    + "::jackson-object-mapper#1", CLI_OWNED_JSON),
            entry("cli/src/main/java/io/github/mbackschat/a12/dm/cli/typedef/TypedefRemoveCommand.java"
                    + "::jackson-object-mapper#1", CLI_OWNED_JSON),
            entry("cli/src/main/java/io/github/mbackschat/a12/dm/cli/typedef/TypedefRenameCommand.java"
                    + "::jackson-object-mapper#1", CLI_OWNED_JSON),
            entry("cli/src/main/java/io/github/mbackschat/a12/dm/cli/typedef/TypedefUnimportCommand.java"
                    + "::jackson-object-mapper#1", CLI_OWNED_JSON),

            entry("cli/src/main/java/io/github/mbackschat/a12/dm/cli/workspace/WorkspaceCheckCommand.java"
                    + "::jackson-object-mapper#1", CLI_OWNED_JSON),
            entry("cli/src/main/java/io/github/mbackschat/a12/dm/cli/workspace/WorkspaceGraphCommand.java"
                    + "::jackson-object-mapper#1", CLI_OWNED_JSON),
            entry("cli/src/main/java/io/github/mbackschat/a12/dm/cli/workspace/WorkspaceListCommand.java"
                    + "::jackson-object-mapper#1", CLI_OWNED_JSON),
            entry("cli/src/main/java/io/github/mbackschat/a12/dm/cli/workspace/WorkspaceRolesCommand.java"
                    + "::jackson-object-mapper#1", CLI_OWNED_JSON),

            entry("adapter/src/main/java/io/github/mbackschat/a12/dm/adapter/profile/ModelProfileComparator.java"
                    + "::jackson-object-mapper#1", PROFILE_JSON),
            entry("adapter/src/main/java/io/github/mbackschat/a12/dm/adapter/profile/ModelProfileJson.java"
                    + "::jackson-json-mapper-builder#1", PROFILE_JSON),
            entry("adapter/src/main/java/io/github/mbackschat/a12/dm/adapter/review/ConfigurationDiffProjector.java"
                    + "::jackson-object-mapper#1", REVIEW_JSON),
            entry("adapter/src/main/java/io/github/mbackschat/a12/dm/adapter/review/DeltaValueParser.java"
                    + "::jackson-object-mapper#1", REVIEW_JSON),

            entry("interpreter-bridge/src/main/kotlin/io/github/mbackschat/a12/dm/bridge/"
                    + "InterpreterConditionReadBack.kt::jackson-json-mapper-builder#1", A12_MODEL_CODEC),

            entry("interpreter/src/commonMain/kotlin/io/github/mbackschat/a12/dm/interpreter/A12Json.kt"
                    + "::kotlinx-default-json#1", A12_INTERPRETER_CODEC),
            entry("interpreter/src/commonMain/kotlin/io/github/mbackschat/a12/dm/interpreter/conformance/CaseJson.kt"
                    + "::kotlinx-default-json#1", CONFORMANCE_CASE_JSON),
            entry("interpreter/src/commonMain/kotlin/io/github/mbackschat/a12/dm/interpreter/conformance/CaseJson.kt"
                    + "::kotlinx-default-json#2", CONFORMANCE_CASE_JSON),
            entry("interpreter/src/jsMain/kotlin/io/github/mbackschat/a12/dm/interpreter/TypeScriptBridge.kt"
                    + "::kotlinx-default-json#1", JS_LIMITS_JSON),
            entry("interpreter/src/jsMain/kotlin/io/github/mbackschat/a12/dm/interpreter/TypeScriptBridge.kt"
                    + "::kotlinx-default-json#2", JS_LIMITS_JSON));

    @Test
    void everyJsonConstructionHasAReviewedRoleBasis() throws IOException {
        Map<String, Path> actual = scan();

        List<String> missing = actual.keySet().stream()
                .filter(site -> !JSON_CONSTRUCTION_ALLOWLIST.containsKey(site))
                .toList();
        List<String> stale = JSON_CONSTRUCTION_ALLOWLIST.keySet().stream()
                .filter(site -> !actual.containsKey(site))
                .toList();
        List<String> reasonless = JSON_CONSTRUCTION_ALLOWLIST.entrySet().stream()
                .filter(entry -> entry.getValue() == null || entry.getValue().isBlank())
                .map(Map.Entry::getKey)
                .toList();

        assertEquals(List.of(), missing,
                () -> "JSON construction site(s) lack a reviewed role basis. If the site parses or re-emits "
                        + "A12-owned content, configure it with A12's exact parser/writer behaviour; otherwise add "
                        + "an exact allowlist row explaining the independent shape:\n  " + String.join("\n  ", missing));
        assertEquals(List.of(), stale,
                () -> "stale JSON construction allowlist row(s) — remove or reclassify them:\n  "
                        + String.join("\n  ", stale));
        assertEquals(List.of(), reasonless,
                () -> "JSON construction allowlist row(s) need a stated reason:\n  "
                        + String.join("\n  ", reasonless));
    }

    private static Map<String, Path> scan() throws IOException {
        Map<String, Path> sites = new LinkedHashMap<>();
        for (String sourceRoot : SOURCE_ROOTS) {
            Path directory = CHECKOUT.resolve(sourceRoot);
            try (Stream<Path> files = Files.walk(directory)) {
                for (Path file : files.filter(Files::isRegularFile)
                        .filter(A12JsonConstructionGuardTest::isJavaOrKotlin)
                        .sorted()
                        .toList()) {
                    String source = maskNonCode(Files.readString(file));
                    addSites(sites, file, source, "jackson-object-mapper", JACKSON_OBJECT_MAPPER);
                    addSites(sites, file, source, "jackson-json-mapper-builder", JACKSON_JSON_MAPPER_BUILDER);
                    addSites(sites, file, source, "kotlinx-default-json", KOTLINX_DEFAULT_JSON);
                }
            }
        }
        return sites;
    }

    private static void addSites(Map<String, Path> sites, Path file, String source,
            String kind, Pattern pattern) {
        Matcher matcher = pattern.matcher(source);
        int ordinal = 0;
        while (matcher.find()) {
            ordinal++;
            String relative = CHECKOUT.relative(file);
            sites.put(relative + "::" + kind + "#" + ordinal, file);
        }
    }

    private static boolean isJavaOrKotlin(Path file) {
        String name = file.getFileName().toString();
        return name.endsWith(".java") || name.endsWith(".kt");
    }

    /**
     * Mask comments and literals so an example string that happens to say {@code new ObjectMapper()} is not a
     * construction site. Kotlin and Java share the regular syntax; Kotlin's triple-quoted strings are handled
     * explicitly. Newlines stay in place so a scanner failure remains easy to reproduce with a source grep.
     */
    private static String maskNonCode(String source) {
        StringBuilder out = new StringBuilder(source.length());
        boolean lineComment = false;
        boolean blockComment = false;
        boolean string = false;
        boolean tripleQuotedString = false;
        boolean character = false;
        boolean escaped = false;
        for (int index = 0; index < source.length(); index++) {
            char current = source.charAt(index);
            char next = index + 1 < source.length() ? source.charAt(index + 1) : '\0';
            char afterNext = index + 2 < source.length() ? source.charAt(index + 2) : '\0';

            if (lineComment) {
                if (current == '\n') {
                    lineComment = false;
                    out.append(current);
                } else {
                    out.append(' ');
                }
                continue;
            }
            if (blockComment) {
                if (current == '*' && next == '/') {
                    blockComment = false;
                    out.append("  ");
                    index++;
                } else {
                    out.append(current == '\n' ? '\n' : ' ');
                }
                continue;
            }
            if (tripleQuotedString) {
                if (current == '"' && next == '"' && afterNext == '"') {
                    tripleQuotedString = false;
                    out.append("   ");
                    index += 2;
                } else {
                    out.append(current == '\n' ? '\n' : ' ');
                }
                continue;
            }
            if (string) {
                out.append(current == '\n' ? '\n' : ' ');
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    string = false;
                }
                continue;
            }
            if (character) {
                out.append(current == '\n' ? '\n' : ' ');
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '\'') {
                    character = false;
                }
                continue;
            }
            if (current == '/' && next == '/') {
                lineComment = true;
                out.append("  ");
                index++;
                continue;
            }
            if (current == '/' && next == '*') {
                blockComment = true;
                out.append("  ");
                index++;
                continue;
            }
            if (current == '"' && next == '"' && afterNext == '"') {
                tripleQuotedString = true;
                out.append("   ");
                index += 2;
                continue;
            }
            if (current == '"') {
                string = true;
                out.append(' ');
                continue;
            }
            if (current == '\'') {
                character = true;
                out.append(' ');
                continue;
            }
            out.append(current);
        }
        return out.toString();
    }

}

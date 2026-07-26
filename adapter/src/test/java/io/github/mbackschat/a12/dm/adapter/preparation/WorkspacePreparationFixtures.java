package io.github.mbackschat.a12.dm.adapter.preparation;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.mbackschat.a12.dm.core.ModelResolver;
import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.modelkit.MountOptions;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;

/**
 * The single typed author for the committed raw-workspace fixtures. The interpreter consumes the generated
 * serializations, while the adapter rebuilds them through modelkit/EditSession/rulekit for freshness checks.
 */
public final class WorkspacePreparationFixtures {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SELF_CONTAINED_ID = "permit-basic";
    private static final String LOCAL_TYPEDEF_ID = "permit-local-typedef";
    private static final String TYPES_ID = "permit-types";
    private static final String INCLUDE_TYPES_ID = "permit-section-types";
    private static final String INCLUDE_TYPES_ENTRY_ID = "permit-include-typedef";
    private static final String CHECKS_ID = "inspection-checks";
    private static final String SECTION_ID = "permit-section";
    private static final String SINGLE_ID = "permit-single";
    private static final String MODEL_ROOT_ID = "permit-model-root";
    private static final String EXCLUDED_ID = "permit-excluded";
    private static final String RULES_EXCLUDED_ID = "permit-rules-excluded";
    private static final String COMPUTATIONS_EXCLUDED_ID = "permit-computations-excluded";
    private static final String MULTI_ROOT_SECTIONS_ID = "permit-multi-root-sections";
    private static final String MULTI_ROOT_ENTRY_ID = "permit-multi-root";
    private static final String REPEATED_MOUNT_ID = "permit-repeated-mount";
    private static final String NESTED_RULES_EXCLUDED_ID = "permit-nested-rules-excluded";
    private static final String ATTACHMENT_TEMPLATE_ID = "permit-attachment-template";
    private static final String ATTACHMENT_HOST_ID = "permit-attachment-host";
    private static final String ATTACHMENT_SINGLE_HOST_ID = "permit-attachment-single-host";
    private static final String ROOT_MOUNT_PROVIDER_ID = "inspection-root-mount";
    private static final String ROOT_MOUNT_HOST_ID = "permit-root-mount-host";

    private WorkspacePreparationFixtures() {
    }

    public static List<FixtureCase> cases() {
        var types = permitTypes();
        var checks = inspectionChecks();
        var sectionSources = sources(types, checks);
        var section = permitSection(sectionSources);
        var shared = sources(types, checks, section);
        var selfContained = selfContained();
        var localTypedef = localTypedef();
        var includeTypes = includeTypes();
        var includeTypesEntry = includeTypesEntry();
        var single = permitEntry(SINGLE_ID, MountOptions.single(),
                "/Permit/Review/Inspection", shared);
        var modelRoot = permitEntry(MODEL_ROOT_ID, MountOptions.single().asModelRoot(),
                "/Permit/Review/Section/Inspection", shared);
        var excluded = excludedEntry();
        var rulesExcluded = rulesExcludedEntry();
        var computationsExcluded = computationsExcludedEntry();
        var multiRootSections = multiRootSections();
        var multiRootEntry = multiRootEntry();
        var repeatedMount = repeatedMountEntry();
        var nestedRulesExcluded = permitEntry(
                NESTED_RULES_EXCLUDED_ID,
                MountOptions.single().excludingRules(),
                "/Permit/Review/Inspection",
                shared);
        var attachmentTemplate = attachmentTemplate();
        var attachmentHost = attachmentHost();
        var attachmentSingleHost = attachmentSingleHost();
        var rootMountProvider = rootMountProvider();
        var rootMountHost = rootMountHost();

        return List.of(
                FixtureCase.builder()
                        .id("self-contained")
                        .entryModelId(SELF_CONTAINED_ID)
                        .sources(sources(selfContained))
                        .expectedFields(Set.of(
                                "/Permit/ApplicationNo",
                                "/Permit/RequestedArea",
                                "/Permit/ReviewedArea",
                                "/Permit/Note"))
                        .expectedRules(Set.of("/Permit/AreaNeedsNote"))
                        .expectedComputations(Map.of("/Permit/CopyRequestedArea", "/Permit/ReviewedArea"))
                        .build(),
                FixtureCase.builder()
                        .id("local-typedef")
                        .entryModelId(LOCAL_TYPEDEF_ID)
                        .sources(sources(localTypedef))
                        .expectedFields(Set.of(
                                "/Permit/ApplicationCode",
                                "/Permit/RequestedArea",
                                "/Permit/Decision"))
                        .build(),
                FixtureCase.builder()
                        .id("include-contributed-typedef")
                        .entryModelId(INCLUDE_TYPES_ENTRY_ID)
                        .sources(sources(includeTypes, includeTypesEntry))
                        .expectedFields(Set.of(
                                "/Permit/ApplicationCode",
                                "/Permit/Details/Description"))
                        .build(),
                FixtureCase.builder()
                        .id("imported-typedef-nested-single")
                        .entryModelId(SINGLE_ID)
                        .sources(sources(types, checks, section, single))
                        .expectedFields(Set.of(
                                "/Permit/ApplicationCode",
                                "/Permit/InspectionDisposition",
                                "/Permit/Review/Name",
                                "/Permit/Review/Inspection/Disposition",
                                "/Permit/Review/Inspection/Score",
                                "/Permit/Review/Inspection/Summary",
                                "/Permit/Review/Inspection/Result"))
                        .expectedRules(Set.of(
                                "/Permit/MountedInspectionFlag",
                                "/Permit/Review/NestedInspectionFlag",
                                "/Permit/Review/Inspection/NegativeScore"))
                        .expectedComputations(Map.of(
                                "/Permit/Review/Inspection/CopyScore",
                                "/Permit/Review/Inspection/Result"))
                        .build(),
                FixtureCase.builder()
                        .id("imported-typedef-nested-model-root")
                        .entryModelId(MODEL_ROOT_ID)
                        .sources(sources(types, checks, section, modelRoot))
                        .expectedFields(Set.of(
                                "/Permit/ApplicationCode",
                                "/Permit/InspectionDisposition",
                                "/Permit/Review/Section/Name",
                                "/Permit/Review/Section/Inspection/Disposition",
                                "/Permit/Review/Section/Inspection/Score",
                                "/Permit/Review/Section/Inspection/Summary",
                                "/Permit/Review/Section/Inspection/Result"))
                        .expectedRules(Set.of(
                                "/Permit/MountedInspectionFlag",
                                "/Permit/Review/Section/NestedInspectionFlag",
                                "/Permit/Review/Section/Inspection/NegativeScore"))
                        .expectedComputations(Map.of(
                                "/Permit/Review/Section/Inspection/CopyScore",
                                "/Permit/Review/Section/Inspection/Result"))
                        .build(),
                FixtureCase.builder()
                        .id("alias-with-exclusions")
                        .entryModelId(EXCLUDED_ID)
                        .sources(sources(checks, excluded))
                        .expectedFields(Set.of(
                                "/Permit/Inspection/Disposition",
                                "/Permit/Inspection/Score",
                                "/Permit/Inspection/Summary",
                                "/Permit/Inspection/Result"))
                        .build(),
                FixtureCase.builder()
                        .id("rules-excluded-computation-retained")
                        .entryModelId(RULES_EXCLUDED_ID)
                        .sources(sources(checks, rulesExcluded))
                        .expectedFields(Set.of(
                                "/Permit/Inspection/Disposition",
                                "/Permit/Inspection/Score",
                                "/Permit/Inspection/Summary",
                                "/Permit/Inspection/Result"))
                        .expectedComputations(Map.of(
                                "/Permit/Inspection/CopyScore",
                                "/Permit/Inspection/Result"))
                        .build(),
                FixtureCase.builder()
                        .id("computations-excluded-rule-retained")
                        .entryModelId(COMPUTATIONS_EXCLUDED_ID)
                        .sources(sources(checks, computationsExcluded))
                        .expectedFields(Set.of(
                                "/Permit/Inspection/Disposition",
                                "/Permit/Inspection/Score",
                                "/Permit/Inspection/Summary",
                                "/Permit/Inspection/Result"))
                        .expectedRules(Set.of("/Permit/Inspection/NegativeScore"))
                        .build(),
                FixtureCase.builder()
                        .id("model-root-multiple-groups")
                        .entryModelId(MULTI_ROOT_ENTRY_ID)
                        .sources(sources(multiRootSections, multiRootEntry))
                        .expectedFields(Set.of(
                                "/Permit/Sections/Applicant/Name",
                                "/Permit/Sections/Decision/Status"))
                        .build(),
                FixtureCase.builder()
                        .id("same-alias-mounted-twice")
                        .entryModelId(REPEATED_MOUNT_ID)
                        .sources(sources(checks, repeatedMount))
                        .expectedFields(Set.of(
                                "/Permit/Primary/Disposition",
                                "/Permit/Primary/Score",
                                "/Permit/Primary/Summary",
                                "/Permit/Primary/Result",
                                "/Permit/Secondary/Disposition",
                                "/Permit/Secondary/Score",
                                "/Permit/Secondary/Summary",
                                "/Permit/Secondary/Result"))
                        .expectedRules(Set.of(
                                "/Permit/Primary/NegativeScore",
                                "/Permit/Secondary/NegativeScore"))
                        .expectedComputations(Map.of(
                                "/Permit/Primary/CopyScore", "/Permit/Primary/Result",
                                "/Permit/Secondary/CopyScore", "/Permit/Secondary/Result"))
                        .build(),
                FixtureCase.builder()
                        .id("nested-rules-excluded")
                        .entryModelId(NESTED_RULES_EXCLUDED_ID)
                        .sources(sources(types, checks, section, nestedRulesExcluded))
                        .expectedFields(Set.of(
                                "/Permit/ApplicationCode",
                                "/Permit/InspectionDisposition",
                                "/Permit/Review/Name",
                                "/Permit/Review/Inspection/Disposition",
                                "/Permit/Review/Inspection/Score",
                                "/Permit/Review/Inspection/Summary",
                                "/Permit/Review/Inspection/Result"))
                        .expectedRules(Set.of("/Permit/MountedInspectionFlag"))
                        .expectedComputations(Map.of(
                                "/Permit/Review/Inspection/CopyScore",
                                "/Permit/Review/Inspection/Result"))
                        .build(),
                FixtureCase.builder()
                        .id("attachment-rules-survive-outer-exclusion")
                        .entryModelId(ATTACHMENT_HOST_ID)
                        .sources(sources(attachmentTemplate, attachmentHost))
                        .expectedFields(Set.of(
                                "/Permit/Documents/Evidence/original_filename",
                                "/Permit/Documents/Evidence/internal_filename",
                                "/Permit/Documents/Evidence/content",
                                "/Permit/Documents/Evidence/attachment_id",
                                "/Permit/Documents/Evidence/size",
                                "/Permit/Documents/Evidence/mime_type",
                                "/Permit/Documents/Evidence/category",
                                "/Permit/Documents/Evidence/description"))
                        .expectedRules(Set.of(
                                "/Permit/Documents/Evidence/AttachmentExactlyOneSource",
                                "/Permit/Documents/Evidence/AttachmentSizeRequired",
                                "/Permit/Documents/Evidence/AttachmentInternalFilenameRequired",
                                "/Permit/Documents/Evidence/AttachmentMimeTypeRequired"))
                        .build(),
                FixtureCase.builder()
                        .id("single-attachment-rules-survive-outer-exclusion")
                        .entryModelId(ATTACHMENT_SINGLE_HOST_ID)
                        .sources(sources(attachmentTemplate, attachmentSingleHost))
                        .expectedFields(Set.of(
                                "/Permit/Documents/original_filename",
                                "/Permit/Documents/internal_filename",
                                "/Permit/Documents/content",
                                "/Permit/Documents/attachment_id",
                                "/Permit/Documents/size",
                                "/Permit/Documents/mime_type",
                                "/Permit/Documents/category",
                                "/Permit/Documents/description"))
                        .expectedRules(Set.of(
                                "/Permit/Documents/AttachmentExactlyOneSource",
                                "/Permit/Documents/AttachmentSizeRequired",
                                "/Permit/Documents/AttachmentInternalFilenameRequired",
                                "/Permit/Documents/AttachmentMimeTypeRequired"))
                        .build(),
                FixtureCase.builder()
                        .id("single-provider-root-include")
                        .entryModelId(ROOT_MOUNT_HOST_ID)
                        .sources(sources(checks, rootMountProvider, rootMountHost))
                        .expectedFields(Set.of(
                                "/Permit/Review/Disposition",
                                "/Permit/Review/Score",
                                "/Permit/Review/Summary",
                                "/Permit/Review/Result"))
                        .expectedRules(Set.of("/Permit/Review/NegativeScore"))
                        .expectedComputations(Map.of(
                                "/Permit/Review/CopyScore",
                                "/Permit/Review/Result"))
                        .build());
    }

    public static FixtureCase caseById(String id) {
        return cases().stream()
                .filter(candidate -> candidate.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown preparation fixture case: " + id));
    }

    /** Every committed fixture path → typed-authored DM-JSON, in stable manifest order. */
    public static Map<String, String> committedFiles() {
        var byPath = new LinkedHashMap<String, String>();
        for (var fixture : cases()) {
            for (var source : fixture.sources().entrySet()) {
                var shared = source.getKey().equals(fixture.entryModelId()) ? "entries" : "shared";
                if (fixture.id().equals("self-contained")) {
                    shared = "self-contained";
                }
                byPath.putIfAbsent(shared + "/" + source.getKey() + ".dm.json", source.getValue());
            }
        }
        return Collections.unmodifiableMap(byPath);
    }

    private static String selfContained() {
        var structure = DocumentModelDraft.named(SELF_CONTAINED_ID).locale("en_US")
                .rootGroup("Permit", permit -> permit
                        .field("ApplicationNo", FieldKind.STRING)
                        .numberField("RequestedArea", number -> number.fractionDigits(0))
                        .numberField("ReviewedArea", number -> number.fractionDigits(0))
                        .field("Note", FieldKind.STRING))
                .build()
                .toJson();
        var session = EditSession.read(structure);
        var permit = session.group("/Permit");
        var applicationNo = permit.str("ApplicationNo");
        var requestedArea = permit.num("RequestedArea");
        var reviewedArea = permit.num("ReviewedArea");
        var note = permit.str("Note");

        permit.addRule(rule -> rule.name("AreaNeedsNote")
                .errorField(note)
                .when(requestedArea.filled().and(requestedArea.gt(100)).and(note.notFilled()))
                .code("AREA_NEEDS_NOTE")
                .severity(Severity.ERROR)
                .message(en("A large requested area needs a note."))
                .build());
        permit.addComputation(reviewedArea, computation -> computation.name("CopyRequestedArea")
                .when(applicationNo.filled())
                .calc(requestedArea.value())
                .message(en("The reviewed area follows the request."))
                .build());
        return session.validate().toJson();
    }

    private static String permitTypes() {
        return DocumentModelDraft.typeDefModel(TYPES_ID).locale("en_US")
                .typeDef("PermitCode", type -> type.string(text -> text.maxLength(12)))
                .build()
                .toJson();
    }

    private static String localTypedef() {
        return DocumentModelDraft.named(LOCAL_TYPEDEF_ID).locale("en_US")
                .typeDef("LocalPermitCode", type -> type.string(text -> text.maxLength(8)))
                .typeDef("LocalArea",
                        type -> type.number(number -> number.fractionDigits(2).min("0.00").max("999.50")))
                .typeDef("LocalDecision",
                        type -> type.enumeration(enumeration -> enumeration.value("APPROVE").value("REJECT")))
                .rootGroup("Permit", permit -> permit
                        .typedefField("ApplicationCode", "LocalPermitCode")
                        .typedefField("RequestedArea", "LocalArea")
                        .typedefField("Decision", "LocalDecision"))
                .build()
                .toJson();
    }

    private static String includeTypes() {
        return DocumentModelDraft.named(INCLUDE_TYPES_ID).locale("en_US")
                .typeDef("IncludedPermitCode", type -> type.string(text -> text.maxLength(10)))
                .rootGroup("Section", section -> section.field("Description", FieldKind.STRING))
                .build()
                .toJson();
    }

    private static String includeTypesEntry() {
        return DocumentModelDraft.named(INCLUDE_TYPES_ENTRY_ID).locale("en_US")
                .include("section-types", INCLUDE_TYPES_ID)
                .rootGroup("Permit", permit -> permit
                        .typedefField("ApplicationCode", "IncludedPermitCode")
                        .mount("Details", "section-types"))
                .build()
                .toJson();
    }

    private static String inspectionChecks() {
        var structure = DocumentModelDraft.named(CHECKS_ID).locale("en_US")
                .typeDef("InspectionDisposition", type -> type.string(text -> text.maxLength(6)))
                .rootGroup("Inspection", inspection -> inspection
                        .numberField("Score", number -> number.fractionDigits(0))
                        .field("Summary", FieldKind.STRING)
                        .typedefField("Disposition", "InspectionDisposition")
                        .numberField("Result", number -> number.fractionDigits(0)))
                .build()
                .toJson();
        var session = EditSession.read(structure);
        var inspection = session.group("/Inspection");
        var score = inspection.num("Score");
        var result = inspection.num("Result");

        inspection.addRule(rule -> rule.name("NegativeScore")
                .errorField(score)
                .when(score.filled().and(score.lt(0)))
                .code("NEGATIVE_SCORE")
                .severity(Severity.ERROR)
                .message(en("An inspection score cannot be negative."))
                .build());
        inspection.addComputation(result, computation -> computation.name("CopyScore")
                .when(score.filled())
                .calc(score.value())
                .message(en("The result follows the inspection score."))
                .build());
        return session.validate().toJson();
    }

    private static String rootMountProvider() {
        var structure = DocumentModelDraft.named(ROOT_MOUNT_PROVIDER_ID).locale("en_US")
                .include("checksAlias", CHECKS_ID)
                .rootGroup("Placeholder", group -> {})
                .build()
                .toJson();
        var session = EditSession.read(structure);
        session.removeGroup("/Placeholder");
        session.mount("", "InspectionProxy", "checksAlias");
        return session.toJson();
    }

    private static String rootMountHost() {
        return DocumentModelDraft.named(ROOT_MOUNT_HOST_ID).locale("en_US")
                .include("providerAlias", ROOT_MOUNT_PROVIDER_ID)
                .rootGroup("Permit", permit -> permit.mount("Review", "providerAlias"))
                .build()
                .toJson();
    }

    private static String permitSection(Map<String, String> providers) {
        var raw = DocumentModelDraft.named(SECTION_ID).locale("en_US")
                .include("checksAlias", CHECKS_ID)
                .rootGroup("Section", section -> section
                        .field("Name", FieldKind.STRING)
                        .mount("Inspection", "checksAlias"))
                .build()
                .toJson();
        var session = EditSession.read(raw, resolver(providers));
        var section = session.group("/Section");
        var name = section.str("Name");
        var nestedScore = session.group("/Section/Inspection").num("Score");
        section.addRule(rule -> rule.name("NestedInspectionFlag")
                .errorField(name)
                .when(name.filled().and(nestedScore.filled()).and(nestedScore.lt(0)))
                .code("NESTED_INSPECTION_FLAG")
                .severity(Severity.ERROR)
                .message(en("The nested inspection needs attention."))
                .build());
        return session.validate().toJson();
    }

    private static String permitEntry(
            String id,
            MountOptions options,
            String mountedInspectionPath,
            Map<String, String> providers) {
        var raw = DocumentModelDraft.named(id).locale("en_US")
                .importTypeDefs(TYPES_ID)
                .include("sectionAlias", SECTION_ID)
                .rootGroup("Permit", permit -> permit
                        .typedefField("ApplicationCode", "PermitCode")
                        .typedefField("InspectionDisposition", "InspectionDisposition")
                        .mount("Review", "sectionAlias", options))
                .build()
                .toJson();
        var session = EditSession.read(raw, resolver(providers));
        var permit = session.group("/Permit");
        var applicationCode = permit.str("ApplicationCode");
        var score = session.group(mountedInspectionPath).num("Score");

        permit.addRule(rule -> rule.name("MountedInspectionFlag")
                .errorField(applicationCode)
                .when(applicationCode.filled().and(score.filled()).and(score.lt(0)))
                .code("MOUNTED_INSPECTION_FLAG")
                .severity(Severity.ERROR)
                .message(en("The mounted inspection needs attention."))
                .build());
        return session.validate().toJson();
    }

    private static String excludedEntry() {
        return DocumentModelDraft.named(EXCLUDED_ID).locale("en_US")
                .include("checksAlias", CHECKS_ID)
                .rootGroup("Permit", permit -> permit.mount(
                        "Inspection",
                        "checksAlias",
                        MountOptions.single().excludingRules().excludingComputations()))
                .build()
                .toJson();
    }

    private static String rulesExcludedEntry() {
        return DocumentModelDraft.named(RULES_EXCLUDED_ID).locale("en_US")
                .include("checksAlias", CHECKS_ID)
                .rootGroup("Permit", permit -> permit.mount(
                        "Inspection",
                        "checksAlias",
                        MountOptions.single().excludingRules()))
                .build()
                .toJson();
    }

    private static String computationsExcludedEntry() {
        return DocumentModelDraft.named(COMPUTATIONS_EXCLUDED_ID).locale("en_US")
                .include("checksAlias", CHECKS_ID)
                .rootGroup("Permit", permit -> permit.mount(
                        "Inspection",
                        "checksAlias",
                        MountOptions.single().excludingComputations()))
                .build()
                .toJson();
    }

    private static String multiRootSections() {
        return DocumentModelDraft.named(MULTI_ROOT_SECTIONS_ID).locale("en_US")
                .rootGroup("Applicant", applicant -> applicant
                        .repeatable(3)
                        .field("Name", FieldKind.STRING))
                .rootGroup("Decision", decision -> decision.field("Status", FieldKind.STRING))
                .build()
                .toJson();
    }

    private static String multiRootEntry() {
        return DocumentModelDraft.named(MULTI_ROOT_ENTRY_ID).locale("en_US")
                .include("sections", MULTI_ROOT_SECTIONS_ID)
                .rootGroup("Permit", permit -> permit.mount(
                        "Sections",
                        "sections",
                        MountOptions.single().asModelRoot()))
                .build()
                .toJson();
    }

    private static String repeatedMountEntry() {
        return DocumentModelDraft.named(REPEATED_MOUNT_ID).locale("en_US")
                .include("checksAlias", CHECKS_ID)
                .rootGroup("Permit", permit -> permit
                        .mount("Primary", "checksAlias")
                        .mount("Secondary", "checksAlias"))
                .build()
                .toJson();
    }

    private static String attachmentTemplate() {
        return DocumentModelDraft.named(ATTACHMENT_TEMPLATE_ID).locale("en_US")
                .attachmentGroup("Evidence", attachment -> { })
                .build()
                .toJson();
    }

    private static String attachmentHost() {
        return DocumentModelDraft.named(ATTACHMENT_HOST_ID).locale("en_US")
                .include("evidence", ATTACHMENT_TEMPLATE_ID)
                .rootGroup("Permit", permit -> permit.mount(
                        "Documents",
                        "evidence",
                        MountOptions.single().asModelRoot().excludingRules().excludingComputations()))
                .build()
                .toJson();
    }

    private static String attachmentSingleHost() {
        return DocumentModelDraft.named(ATTACHMENT_SINGLE_HOST_ID).locale("en_US")
                .include("evidence", ATTACHMENT_TEMPLATE_ID)
                .rootGroup("Permit", permit -> permit.mount(
                        "Documents",
                        "evidence",
                        MountOptions.single().excludingRules().excludingComputations()))
                .build()
                .toJson();
    }

    private static ModelResolver resolver(Map<String, String> sources) {
        return reference -> {
            var source = sources.get(reference);
            if (source == null) {
                throw new IllegalStateException("unknown fixture model id: " + reference);
            }
            return source;
        };
    }

    private static Map<String, String> sources(String... models) {
        var byId = new LinkedHashMap<String, String>();
        for (var model : models) {
            var id = headerId(model);
            byId.put(id, model);
        }
        return Collections.unmodifiableMap(byId);
    }

    private static String headerId(String json) {
        try {
            var id = JSON.readTree(json).path("header").path("id").asText("");
            if (id.isEmpty()) {
                throw new IllegalArgumentException("fixture has no header id");
            }
            return id;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("fixture is not valid JSON", exception);
        }
    }

    public record FixtureCase(
            String id,
            String entryModelId,
            Map<String, String> sources,
            Set<String> expectedFields,
            Set<String> expectedRules,
            Map<String, String> expectedComputations) {

        public FixtureCase {
            id = Objects.requireNonNull(id, "id");
            entryModelId = Objects.requireNonNull(entryModelId, "entryModelId");
            Objects.requireNonNull(sources, "sources");
            Objects.requireNonNull(expectedFields, "expectedFields");
            Objects.requireNonNull(expectedRules, "expectedRules");
            Objects.requireNonNull(expectedComputations, "expectedComputations");
            sources = Collections.unmodifiableMap(new LinkedHashMap<>(sources));
            expectedFields = Set.copyOf(expectedFields);
            expectedRules = Set.copyOf(expectedRules);
            expectedComputations = Map.copyOf(expectedComputations);
            if (!sources.containsKey(entryModelId)) {
                throw new IllegalArgumentException("entry source is absent: " + entryModelId);
            }
        }

        public String entryJson() {
            return sources.get(entryModelId);
        }

        public ModelResolver resolver() {
            return WorkspacePreparationFixtures.resolver(sources);
        }

        public static Builder builder() {
            return new Builder();
        }

        public static final class Builder {

            private String id;
            private String entryModelId;
            private Map<String, String> sources = Map.of();
            private Set<String> expectedFields = Set.of();
            private Set<String> expectedRules = Set.of();
            private Map<String, String> expectedComputations = Map.of();

            private Builder() {
            }

            public Builder id(String value) {
                if (value != null && !value.isBlank()) {
                    id = value;
                }
                return this;
            }

            public Builder entryModelId(String value) {
                if (value != null && !value.isBlank()) {
                    entryModelId = value;
                }
                return this;
            }

            public Builder sources(Map<String, String> value) {
                if (value != null && !value.isEmpty()) {
                    sources = value;
                }
                return this;
            }

            public Builder expectedFields(Set<String> value) {
                if (value != null && !value.isEmpty()) {
                    expectedFields = value;
                }
                return this;
            }

            public Builder expectedRules(Set<String> value) {
                if (value != null && !value.isEmpty()) {
                    expectedRules = value;
                }
                return this;
            }

            public Builder expectedComputations(Map<String, String> value) {
                if (value != null && !value.isEmpty()) {
                    expectedComputations = value;
                }
                return this;
            }

            public FixtureCase build() {
                return new FixtureCase(
                        id,
                        entryModelId,
                        sources,
                        expectedFields,
                        expectedRules,
                        expectedComputations);
            }
        }
    }
}

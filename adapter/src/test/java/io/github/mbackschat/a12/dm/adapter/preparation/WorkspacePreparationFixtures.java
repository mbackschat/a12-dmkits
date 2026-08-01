package io.github.mbackschat.a12.dm.adapter.preparation;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.core.ModelResolver;
import io.github.mbackschat.a12.dm.core.ValueKind;
import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.modelkit.MountOptions;
import io.github.mbackschat.a12.dm.rulekit.context.ModelContext;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation;
import io.github.mbackschat.a12.dm.rulekit.write.ModelWriter;

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
    private static final String REFERENCE_PROVIDER_ID = "benefits-reference-family";
    private static final String REFERENCE_SINGLE_ID = "policy-reference-single";
    private static final String REFERENCE_MODEL_ROOT_ID = "policy-reference-model-root";
    private static final String GERMAN_REFERENCE_PROVIDER_ID = "benefits-reference-family-de";
    private static final String GERMAN_REFERENCE_SINGLE_ID = "policy-reference-single-de";
    private static final String MIXED_REFERENCE_MIDDLE_ID = "policy-reference-middle-mixed";
    private static final String MIXED_REFERENCE_NESTED_ID = "envelope-reference-nested-mixed";
    private static final String NEUTRAL_REFERENCE_PROVIDER_ID = "benefits-reference-neutral-de";
    private static final String NEUTRAL_REFERENCE_HOST_ID = "policy-reference-neutral-en";
    private static final String REVERSE_NEUTRAL_REFERENCE_PROVIDER_ID = "benefits-reference-neutral-en";
    private static final String REVERSE_NEUTRAL_REFERENCE_HOST_ID = "policy-reference-neutral-de";
    private static final String COLLECTION_ENTRY_ID = "permit-collection";
    private static final String COLLECTION_LEFT_TYPES_ID = "permit-collection-left-types";
    private static final String COLLECTION_RIGHT_TYPES_ID = "permit-collection-right-types";
    private static final String COLLECTION_SHARED_TYPES_ID = "permit-collection-shared-types";
    private static final String COLLECTION_SECTION_ID = "permit-collection-section";
    private static final String COLLECTION_CHECKLIST_ID = "permit-collection-checklist";
    private static final String COLLECTION_UNUSED_ID = "permit-collection-unused";

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
        var referenceProvider = referenceFamilyProvider(REFERENCE_PROVIDER_ID, ConditionLanguage.EN);
        var referenceSingle = referenceFamilyHost(
                REFERENCE_SINGLE_ID,
                REFERENCE_PROVIDER_ID,
                ConditionLanguage.EN,
                MountOptions.single(),
                true);
        var referenceModelRoot = referenceFamilyHost(
                REFERENCE_MODEL_ROOT_ID,
                REFERENCE_PROVIDER_ID,
                ConditionLanguage.EN,
                MountOptions.single().asModelRoot(),
                false);
        var germanReferenceProvider = referenceFamilyProvider(
                GERMAN_REFERENCE_PROVIDER_ID,
                ConditionLanguage.DE);
        var germanReferenceSingle = referenceFamilyHost(
                GERMAN_REFERENCE_SINGLE_ID,
                GERMAN_REFERENCE_PROVIDER_ID,
                ConditionLanguage.DE,
                MountOptions.single(),
                false);
        var mixedReferenceMiddle = referenceFamilyHost(
                MIXED_REFERENCE_MIDDLE_ID,
                GERMAN_REFERENCE_PROVIDER_ID,
                ConditionLanguage.EN,
                MountOptions.single(),
                false);
        var mixedReferenceNested = nestedReferenceHost();
        var neutralReferenceProvider = neutralReferenceProvider(
                NEUTRAL_REFERENCE_PROVIDER_ID,
                ConditionLanguage.DE);
        var neutralReferenceHost = neutralReferenceHost(
                NEUTRAL_REFERENCE_HOST_ID,
                NEUTRAL_REFERENCE_PROVIDER_ID,
                ConditionLanguage.EN);
        var reverseNeutralReferenceProvider = neutralReferenceProvider(
                REVERSE_NEUTRAL_REFERENCE_PROVIDER_ID,
                ConditionLanguage.EN);
        var reverseNeutralReferenceHost = neutralReferenceHost(
                REVERSE_NEUTRAL_REFERENCE_HOST_ID,
                REVERSE_NEUTRAL_REFERENCE_PROVIDER_ID,
                ConditionLanguage.DE);
        var collectionSharedTypes = collectionSharedTypes();
        var collectionLeftTypes = collectionLeftTypes();
        var collectionRightTypes = collectionRightTypes();
        var collectionChecklist = collectionChecklist();
        var collectionSection = collectionSection();
        var collectionUnused = collectionUnused();
        var collectionEntry = collectionEntry();

        return List.of(
                FixtureCase.builder()
                        .id("self-contained")
                        .entryModelId(SELF_CONTAINED_ID)
                        .sources(sources(selfContained))
                        .expectedFields(Set.of(
                                "/Permit/ApplicationNo",
                                "/Permit/ApplicantName",
                                "/Permit/PermitType",
                                "/Permit/SubmittedOn",
                                "/Permit/RequestedArea",
                                "/Permit/UnitFee",
                                "/Permit/ReviewedArea",
                                "/Permit/EstimatedFee",
                                "/Permit/Note",
                                "/Permit/Rate",
                                "/Permit/PatternProbe",
                                "/Permit/GraphemeProbe",
                                "/Permit/ReportedAt",
                                "/Permit/SettledAt",
                                "/Permit/SiteSections/SectionName",
                                "/Permit/SiteSections/Area",
                                "/Permit/SiteSections/SurveyCharge"))
                        .expectedRules(Set.of(
                                "/Permit/AreaNeedsNote",
                                "/Permit/DecimalPrecisionControl",
                                "/Permit/DecimalPrecisionSplit",
                                "/Permit/PatternDialect",
                                "/Permit/GraphemeDialect",
                                "/Permit/BerlinSpringHour"))
                        .expectedComputations(Map.of(
                                "/Permit/CopyRequestedArea", "/Permit/ReviewedArea",
                                "/Permit/CalculateEstimatedFee", "/Permit/EstimatedFee",
                                "/Permit/SiteSections/ChargeSectionSurvey",
                                "/Permit/SiteSections/SurveyCharge"))
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
                        .build(),
                FixtureCase.builder()
                        .id("reference-family-single")
                        .entryModelId(REFERENCE_SINGLE_ID)
                        .sources(sources(referenceProvider, referenceSingle))
                        .expectedFields(referenceFields("/Policy/Cover", "/Policy/Renewal"))
                        .expectedRules(referenceRules("/Policy/Cover", "/Policy/Renewal"))
                        .expectedComputations(referenceComputations("/Policy/Cover", "/Policy/Renewal"))
                        .build(),
                FixtureCase.builder()
                        .id("reference-family-model-root")
                        .entryModelId(REFERENCE_MODEL_ROOT_ID)
                        .sources(sources(referenceProvider, referenceModelRoot))
                        .expectedFields(referenceFields("/Policy/Cover/Benefits"))
                        .expectedRules(referenceRules("/Policy/Cover/Benefits"))
                        .expectedComputations(referenceComputations("/Policy/Cover/Benefits"))
                        .build(),
                FixtureCase.builder()
                        .id("reference-family-german-single")
                        .entryModelId(GERMAN_REFERENCE_SINGLE_ID)
                        .sources(sources(germanReferenceProvider, germanReferenceSingle))
                        .expectedFields(referenceFields("/Policy/Cover"))
                        .expectedRules(referenceRules("/Policy/Cover"))
                        .expectedComputations(referenceComputations("/Policy/Cover"))
                        .build(),
                FixtureCase.builder()
                        .id("reference-family-mixed-language-nested")
                        .entryModelId(MIXED_REFERENCE_NESTED_ID)
                        .sources(sources(
                                germanReferenceProvider,
                                mixedReferenceMiddle,
                                mixedReferenceNested))
                        .disposition(FixtureDisposition.MIXED_CONDITION_LANGUAGE)
                        .build(),
                FixtureCase.builder()
                        .id("reference-family-mixed-language-neutral")
                        .entryModelId(NEUTRAL_REFERENCE_HOST_ID)
                        .sources(sources(neutralReferenceProvider, neutralReferenceHost))
                        .expectedFields(Set.of(
                                "/Policy/PolicyNo",
                                "/Policy/Cover/Note"))
                        .expectedRules(Set.of("/Policy/Cover/NoteEqualsX"))
                        .build(),
                FixtureCase.builder()
                        .id("reference-family-mixed-language-neutral-reverse")
                        .entryModelId(REVERSE_NEUTRAL_REFERENCE_HOST_ID)
                        .sources(sources(reverseNeutralReferenceProvider, reverseNeutralReferenceHost))
                        .expectedFields(Set.of(
                                "/Policy/PolicyNo",
                                "/Policy/Cover/Note"))
                        .expectedRules(Set.of("/Policy/Cover/NoteEqualsX"))
                        .build(),
                FixtureCase.builder()
                        .id("collection-diamond-unmounted")
                        .entryModelId(COLLECTION_ENTRY_ID)
                        .sources(sources(
                                collectionSharedTypes,
                                collectionLeftTypes,
                                collectionRightTypes,
                                collectionChecklist,
                                collectionSection,
                                collectionUnused,
                                collectionEntry))
                        .expectedFields(Set.of(
                                "/Permit/CollectionCode",
                                "/Permit/Section/SectionCode",
                                "/Permit/Section/Checklist/Comment"))
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
                        .stringField("ApplicationNo", text -> text
                                .maxLength(20)
                                .required()
                                .label("en_US", "Application number"))
                        .stringField("ApplicantName", text -> text
                                .maxLength(80)
                                .label("en_US", "Applicant"))
                        .enumField("PermitType", type -> type
                                .value("RESIDENTIAL", label -> label.label("en_US", "Residential"))
                                .value("COMMERCIAL", label -> label.label("en_US", "Commercial"))
                                .value("PUBLIC", label -> label.label("en_US", "Public"))
                                .label("en_US", "Permit type"))
                        .dateField("SubmittedOn", date -> date
                                .format("yyyy-MM-dd")
                                .label("en_US", "Submitted on"))
                        .numberField("RequestedArea", number -> number
                                .fractionDigits(0)
                                .min("1")
                                .max("1000")
                                .required()
                                .label("en_US", "Requested area (m²)"))
                        .numberField("UnitFee", number -> number
                                .fractionDigits(2)
                                .min("0")
                                .label("en_US", "Fee per m²"))
                        .numberField("ReviewedArea", number -> number.fractionDigits(0))
                        .numberField("EstimatedFee", number -> number
                                .fractionDigits(2)
                                .label("en_US", "Estimated fee"))
                        .field("Note", FieldKind.STRING)
                        .numberField("Rate", number -> number.fractionDigits(0, 14))
                        .field("PatternProbe", FieldKind.STRING)
                        .field("GraphemeProbe", FieldKind.STRING)
                        .dateTimeField("ReportedAt", dateTime -> dateTime.format("yyyy-MM-dd'T'HH:mm:ss"))
                        .dateTimeField("SettledAt", dateTime -> dateTime.format("yyyy-MM-dd'T'HH:mm:ss"))
                        .group("SiteSections", sections -> sections
                                .repeatable(20)
                                .stringField("SectionName", text -> text
                                        .maxLength(60)
                                        .required()
                                        .label("en_US", "Section name"))
                                .numberField("Area", number -> number
                                        .fractionDigits(0)
                                        .min("1")
                                        .max("1000")
                                        .required()
                                        .label("en_US", "Section area (m²)"))
                                // The showcase's one REPEATED computed target: several results whose
                                // pointer.path is the same string, told apart only by their coordinates.
                                .numberField("SurveyCharge", number -> number
                                        .fractionDigits(0)
                                        .label("en_US", "Section survey charge"))))
                .build()
                .toJson();
        var session = EditSession.read(structure);
        session.config().timeZone("Europe/Berlin");
        var permit = session.group("/Permit");
        var applicationNo = permit.str("ApplicationNo");
        var requestedArea = permit.num("RequestedArea");
        var unitFee = permit.num("UnitFee");
        var reviewedArea = permit.num("ReviewedArea");
        var estimatedFee = permit.num("EstimatedFee");
        var note = permit.str("Note");
        var rate = permit.num("Rate");
        var patternProbe = permit.str("PatternProbe");
        var graphemeProbe = permit.str("GraphemeProbe");
        var reportedAt = permit.dateTime("ReportedAt");
        var settledAt = permit.dateTime("SettledAt");
        var sections = session.group("/Permit/SiteSections");
        var sectionArea = sections.num("Area");
        var surveyCharge = sections.num("SurveyCharge");

        Rule areaNeedsNote = permit.rule(rule -> rule.name("AreaNeedsNote")
                .errorField(note)
                .when(requestedArea.filled().and(requestedArea.gt(100)).and(note.notFilled()))
                .code("AREA_NEEDS_NOTE")
                .severity(Severity.ERROR)
                .message(en("A large requested area needs a note."))
                .build());
        session.addRule(ignored -> areaNeedsNote);
        Computation copyRequestedArea = permit.computation(reviewedArea,
                computation -> computation.name("CopyRequestedArea")
                .when(applicationNo.filled())
                .calc(requestedArea.value())
                .message(en("The reviewed area follows the request."))
                .build());
        session.addComputation(ignored -> copyRequestedArea);
        Computation calculateEstimatedFee = permit.computation(estimatedFee,
                computation -> computation.name("CalculateEstimatedFee")
                        .when(requestedArea.filled().and(unitFee.filled()))
                        .calc(requestedArea.asNumber().times(unitFee.asNumber()).expression())
                        .message(en("The estimated permit fee is area multiplied by the unit fee."))
                        .build());
        session.addComputation(ignored -> calculateEstimatedFee);
        Computation chargeSectionSurvey = sections.computation(surveyCharge,
                computation -> computation.name("ChargeSectionSurvey")
                        .when(sectionArea.filled())
                        .calc(sectionArea.asNumber().times(Dsl.number("25")).expression())
                        .message(en("Each site section is surveyed at the statutory rate of 25 per m²."))
                        .build());
        session.addComputation(ignored -> chargeSectionSurvey);
        var reciprocalPower = rate.asNumber().toThePowerOf(Dsl.number("-64"));
        Rule decimalPrecisionControl = permit.rule(rule -> rule.name("DecimalPrecisionControl")
                .errorField(rate)
                .when(rate.filled().and(reciprocalPower.gt("1000000")))
                .code("DECIMAL_CONTROL")
                .severity(Severity.ERROR)
                .message(en("The decimal control discriminator fires."))
                .build());
        session.addRule(ignored -> decimalPrecisionControl);
        Rule decimalPrecisionSplit = permit.rule(rule -> rule.name("DecimalPrecisionSplit")
                .errorField(rate)
                .when(rate.filled().and(reciprocalPower.gt(
                        "2912324058756262786433368781182555.3552912554484015")))
                .code("DECIMAL_SPLIT")
                .severity(Severity.ERROR)
                .message(en("The decimal precision discriminator must stay silent."))
                .build());
        session.addRule(ignored -> decimalPrecisionSplit);
        Rule patternDialect = permit.rule(rule -> rule.name("PatternDialect")
                .errorField(patternProbe)
                .when(patternProbe.matches("\\d+\\h\\d+"))
                .code("PATTERN_DIALECT")
                .severity(Severity.ERROR)
                .message(en("Java horizontal whitespace matched."))
                .build());
        session.addRule(ignored -> patternDialect);
        Rule graphemeDialect = permit.rule(rule -> rule.name("GraphemeDialect")
                .errorField(graphemeProbe)
                .when(graphemeProbe.matches("\\X"))
                .code("GRAPHEME_DIALECT")
                .severity(Severity.ERROR)
                .message(en("The extended grapheme matched."))
                .build());
        session.addRule(ignored -> graphemeDialect);
        Rule berlinSpringHour = permit.rule(rule -> rule.name("BerlinSpringHour")
                .errorField(reportedAt)
                .when(reportedAt.filled()
                        .and(Dsl.differenceInHours(reportedAt, settledAt).eq(1)))
                .code("BERLIN_SPRING_HOUR")
                .severity(Severity.ERROR)
                .message(en("The Berlin spring-forward pair is one real hour apart."))
                .build());
        session.addRule(ignored -> berlinSpringHour);

        ModelContext explanations = new ModelContext(BoundModel.ofJson(session.toJson()));
        describeRule(session, explanations, areaNeedsNote,
                "Flags a permit when its requested area exceeds 100 m² and no review note is present.");
        describeRule(session, explanations, decimalPrecisionControl,
                "Exercises the maintained high-precision arithmetic control used by the browser conformance gate.");
        describeRule(session, explanations, decimalPrecisionSplit,
                "Guards the browser's high-precision arithmetic boundary and normally remains silent.");
        describeRule(session, explanations, patternDialect,
                "Exercises Java-compatible horizontal-whitespace matching when PatternProbe is supplied.");
        describeRule(session, explanations, graphemeDialect,
                "Exercises extended-grapheme matching when GraphemeProbe is supplied.");
        describeRule(session, explanations, berlinSpringHour,
                "Recognizes a one-hour real-time interval across Berlin's spring daylight-saving transition.");
        describeComputation(session, explanations, copyRequestedArea,
                "Copies the requested area into the reviewed-area output.");
        describeComputation(session, explanations, calculateEstimatedFee,
                "Calculates the estimated permit fee as requested area multiplied by the fee per m².");
        describeComputation(session, explanations, chargeSectionSurvey,
                "Charges every site-section row its own survey fee at the statutory per-m² rate.");

        return session.validate().toJson();
    }

    private static void describeRule(EditSession session, ModelContext explanations, Rule rule, String plainText) {
        session.rule(rule.group().path() + "/" + rule.name())
                .setExternalDescription("en_US", plainText + " " + explanations.explain(rule.condition()).gloss());
    }

    private static void describeComputation(EditSession session, ModelContext explanations,
            Computation computation, String plainText) {
        session.computation(computation.groupPath() + "/" + computation.name())
                .setExternalDescription("en_US",
                        plainText + " " + explanations.explainComputation(computation).gloss());
    }

    private static String collectionSharedTypes() {
        return DocumentModelDraft.typeDefModel(COLLECTION_SHARED_TYPES_ID).locale("en_US")
                .typeDef("CollectionCode", type -> type.string(text -> text.maxLength(16)))
                .build()
                .toJson();
    }

    private static String collectionLeftTypes() {
        return DocumentModelDraft.typeDefModel(COLLECTION_LEFT_TYPES_ID).locale("en_US")
                .importTypeDefs(COLLECTION_SHARED_TYPES_ID)
                .typeDef("LeftCollectionMarker", type -> type.string(text -> text.maxLength(1)))
                .build()
                .toJson();
    }

    private static String collectionRightTypes() {
        return DocumentModelDraft.typeDefModel(COLLECTION_RIGHT_TYPES_ID).locale("en_US")
                .importTypeDefs(COLLECTION_SHARED_TYPES_ID)
                .typeDef("RightCollectionMarker", type -> type.string(text -> text.maxLength(1)))
                .build()
                .toJson();
    }

    private static String collectionChecklist() {
        return DocumentModelDraft.named(COLLECTION_CHECKLIST_ID).locale("en_US")
                .rootGroup("Checklist", checklist -> checklist.field("Comment", FieldKind.STRING))
                .build()
                .toJson();
    }

    private static String collectionSection() {
        return DocumentModelDraft.named(COLLECTION_SECTION_ID).locale("en_US")
                .importTypeDefs(COLLECTION_SHARED_TYPES_ID)
                .include("checklist", COLLECTION_CHECKLIST_ID)
                .rootGroup("Section", section -> section
                        .typedefField("SectionCode", "CollectionCode")
                        .mount("Checklist", "checklist"))
                .build()
                .toJson();
    }

    private static String collectionUnused() {
        return DocumentModelDraft.named(COLLECTION_UNUSED_ID).locale("en_US")
                .rootGroup("Unused", unused -> unused.field("Secret", FieldKind.STRING))
                .build()
                .toJson();
    }

    private static String collectionEntry() {
        return DocumentModelDraft.named(COLLECTION_ENTRY_ID).locale("en_US")
                .importTypeDefs(COLLECTION_LEFT_TYPES_ID)
                .importTypeDefs(COLLECTION_RIGHT_TYPES_ID)
                .include("section", COLLECTION_SECTION_ID)
                .include("unused", COLLECTION_UNUSED_ID)
                .rootGroup("Permit", permit -> permit
                        .typedefField("CollectionCode", "CollectionCode")
                        .mount("Section", "section"))
                .build()
                .toJson();
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

    private static String referenceFamilyProvider(
            String modelId,
            ConditionLanguage language) {
        var structure = DocumentModelDraft.named(modelId).locale("en_US")
                .rootGroup("Benefits", root -> root
                        .numberField("Threshold", number -> number.fractionDigits(0))
                        .field("Note", FieldKind.STRING)
                        .group("Lines", lines -> lines
                                .repeatable(5)
                                .field("Key", FieldKind.STRING)
                                .numberField("Amount", number -> number.fractionDigits(0))
                                .indexField("Key"))
                        .group("Level", level -> level
                                .numberField("Value", number -> number.fractionDigits(0))
                                .group("Deep", deep -> deep
                                        .numberField("Marker", number -> number.fractionDigits(0))
                                        .numberField("Total", number -> number.fractionDigits(0)))))
                .build()
                .toJson();
        structure = withConditionLanguage(structure, language);
        var model = io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel.ofJson(structure);
        var benefits = model.group("/Benefits");
        var threshold = model.num("/Benefits/Threshold");
        var note = model.str("/Benefits/Note");
        var amount = model.num("/Benefits/Lines/Amount");
        var levelValue = model.num("/Benefits/Level/Value");
        var marker = model.num("/Benefits/Level/Deep/Marker");
        var total = model.num("/Benefits/Level/Deep/Total");

        var outerCondition = note.filled().and(
                Dsl.sum(model.flatten("/Benefits/Lines*/Amount").having(amount.eq(threshold.outer()))).gt(0));
        var indexedCondition = note.filled().and(amount.forIndex(note.field()).gt(0));
        var outerRule = model.rule(benefits)
                .name("OuterMatch")
                .errorField(note)
                .when(outerCondition)
                .code("OUTER")
                .severity(Severity.ERROR)
                .message(en("A matching benefit line needs review."))
                .build();
        var indexedRule = model.rule(benefits)
                .name("IndexedAmount")
                .errorField(note)
                .when(indexedCondition)
                .code("INDEXED")
                .severity(Severity.ERROR)
                .message(en("A keyed benefit amount needs review."))
                .build();
        var rootCondition = note.filled()
                .or(benefits.filled())
                .or(threshold.filled())
                .or(levelValue.filled());
        var rootRule = model.rule(benefits)
                .name("RootPresent")
                .errorField(note)
                .when(rootCondition)
                .code("ROOT")
                .severity(Severity.ERROR)
                .message(en("The benefit root is present."))
                .build();
        var levelCondition = levelValue.filled().and(levelValue.lt(0));
        var levelRule = model.rule(model.group("/Benefits/Level"))
                .name("LevelNegative")
                .errorField(levelValue)
                .when(levelCondition)
                .code("LEVEL")
                .severity(Severity.ERROR)
                .message(en("The benefit level cannot be negative."))
                .build();
        var deepCondition = marker.filled().and(marker.lt(0));
        var deepRule = model.rule(model.group("/Benefits/Level/Deep"))
                .name("DeepNegative")
                .errorField(marker)
                .when(deepCondition)
                .code("DEEP")
                .severity(Severity.ERROR)
                .message(en("The deep marker cannot be negative."))
                .build();

        model = model.add(outerRule);
        model = model.add(indexedRule);
        model = model.add(rootRule);
        model = model.add(levelRule);
        model = model.add(deepRule);
        var canonicalComputation = model.computation(total)
                .name("CopyThreshold")
                .commonPrecondition(marker.filled().and(threshold.gt(0)))
                .when(marker.lt(0).and(threshold.gt(0)))
                .calc(threshold.value())
                .when(marker.ge(0).and(threshold.gt(0)))
                .calc(threshold.value())
                .message(en("The total follows the provider threshold."))
                .build();
        model = model.add(canonicalComputation);

        // A short path is an intentionally non-canonical storage style. Keep the fixture typed by rendering an
        // equivalent Computation AST whose provider-local threshold ref carries that stored spelling.
        var shortThreshold = new NumberRef(new FieldRef("Threshold", ValueKind.NUMBER), 0);
        var storedComputation = model.computation(total)
                .name("CopyThreshold")
                .commonPrecondition(marker.filled().and(shortThreshold.gt(0)))
                .when(marker.lt(0).and(shortThreshold.gt(0)))
                .calc(shortThreshold.value())
                .when(marker.ge(0).and(shortThreshold.gt(0)))
                .calc(shortThreshold.value())
                .message(en("The total follows the provider threshold."))
                .build();

        var absolute = new DslRenderer(language);
        var styled = ModelWriter.replaceComputation(
                model.toJson(),
                "/Benefits/Level/Deep/CopyThreshold",
                storedPathDraft(storedComputation, absolute),
                storedComputation.messages());
        if (language == ConditionLanguage.DE) {
            // The German lexer admits the same lower/canonical/upper variants as its English peer. Keep each
            // style in the real-Kernel fixture so portable parsing does not accidentally accept only rendering output.
            styled = ModelWriter.replaceCondition(
                    styled,
                    "/Benefits/IndexedAmount",
                    indexedRule.render(language).replace(" Und ", " und "));
            styled = ModelWriter.replaceCondition(
                    styled,
                    "/Benefits/RootPresent",
                    rootRule.render(language)
                            .replaceFirst(" Oder ", " ODER ")
                            .replaceFirst(" Oder ", " oder "));
            styled = ModelWriter.replaceCondition(
                    styled,
                    "/Benefits/Level/LevelNegative",
                    levelRule.render(language).replace(" Und ", " UND "));
        }
        // The raw-slot styling above is intentional concrete-syntax coverage. Re-bind before replacing ids so the
        // real authoring gate proves the case variants and unique short-name references remain legal.
        styled = io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel.ofJson(styled).toJson();
        var root = objectModel(styled);
        rewriteOpaqueElementIds(root);
        return writeModel(root);
    }

    private static String referenceFamilyHost(
            String id,
            String providerId,
            ConditionLanguage language,
            MountOptions options,
            boolean repeatedMount) {
        var source = DocumentModelDraft.named(id).locale("en_US")
                .include("benefits", providerId)
                .rootGroup("Policy", policy -> {
                    policy.field("PolicyNo", FieldKind.STRING)
                            .numberField("Threshold", number -> number.fractionDigits(0))
                            .mount("Cover", "benefits", options);
                    if (repeatedMount) {
                        policy.mount("Renewal", "benefits", options);
                    }
                })
                .build()
                .toJson();
        source = withConditionLanguage(source, language);
        var root = objectModel(source);
        rewriteOpaqueElementIds(root);
        return writeModel(root);
    }

    private static String nestedReferenceHost() {
        return DocumentModelDraft.named(MIXED_REFERENCE_NESTED_ID).locale("en_US")
                .include("policy", MIXED_REFERENCE_MIDDLE_ID)
                .rootGroup("Envelope", envelope -> envelope
                        .field("EntryNo", FieldKind.STRING)
                        .mount("NestedPolicy", "policy"))
                .build()
                .toJson();
    }

    private static String neutralReferenceProvider(
            String modelId,
            ConditionLanguage language) {
        var structure = DocumentModelDraft.named(modelId).locale("en_US")
                .rootGroup("Benefits", benefits -> benefits.field("Note", FieldKind.STRING))
                .build()
                .toJson();
        structure = withConditionLanguage(structure, language);
        var model = io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel.ofJson(structure);
        var benefits = model.group("/Benefits");
        var note = model.str("/Benefits/Note");
        var rule = model.rule(benefits)
                .name("NoteEqualsX")
                .errorField(note)
                .when(note.eq("x"))
                .code("NOTE_EQUALS_X")
                .severity(Severity.ERROR)
                .message(en("The note must not equal x."))
                .build();
        var root = objectModel(model.add(rule).toJson());
        rewriteOpaqueElementIds(root);
        return writeModel(root);
    }

    private static String neutralReferenceHost(
            String modelId,
            String providerId,
            ConditionLanguage language) {
        var source = DocumentModelDraft.named(modelId).locale("en_US")
                .include("benefits", providerId)
                .rootGroup("Policy", policy -> policy
                        .field("PolicyNo", FieldKind.STRING)
                        .mount("Cover", "benefits"))
                .build()
                .toJson();
        source = withConditionLanguage(source, language);
        var root = objectModel(source);
        rewriteOpaqueElementIds(root);
        return writeModel(root);
    }

    private static String withConditionLanguage(String source, ConditionLanguage language) {
        if (language == ConditionLanguage.EN) {
            return source;
        }
        var session = EditSession.read(source);
        session.config().conditionLanguage("de_DE");
        return session.toJson();
    }

    private static Set<String> referenceFields(String... mountedRoots) {
        var fields = new LinkedHashSet<String>();
        fields.add("/Policy/PolicyNo");
        fields.add("/Policy/Threshold");
        for (var root : mountedRoots) {
            addReferenceProviderFields(fields, root);
        }
        return Set.copyOf(fields);
    }

    private static void addReferenceProviderFields(Set<String> fields, String root) {
        fields.add(root + "/Threshold");
        fields.add(root + "/Note");
        fields.add(root + "/Lines/Key");
        fields.add(root + "/Lines/Amount");
        fields.add(root + "/Level/Value");
        fields.add(root + "/Level/Deep/Marker");
        fields.add(root + "/Level/Deep/Total");
    }

    private static Set<String> referenceRules(String... mountedRoots) {
        var rules = new LinkedHashSet<String>();
        for (var root : mountedRoots) {
            rules.add(root + "/OuterMatch");
            rules.add(root + "/IndexedAmount");
            rules.add(root + "/RootPresent");
            rules.add(root + "/Level/LevelNegative");
            rules.add(root + "/Level/Deep/DeepNegative");
        }
        return Set.copyOf(rules);
    }

    private static Map<String, String> referenceComputations(String... mountedRoots) {
        var computations = new LinkedHashMap<String, String>();
        for (var root : mountedRoots) {
            computations.put(root + "/Level/Deep/CopyThreshold", root + "/Level/Deep/Total");
        }
        return Map.copyOf(computations);
    }

    /**
     * A unique short-name reference is deliberately non-canonical stored syntax: the normal computation draft renders
     * relative paths. Build the raw-slot draft from the typed computation with an absolute renderer so only that
     * concrete spelling differs; the authoring gate above proves the result remains model-legal.
     */
    private static DraftComputation storedPathDraft(
            io.github.mbackschat.a12.dm.rulekit.dsl.Computation computation,
            DslRenderer renderer) {
        return new DraftComputation(
                computation.groupPath(),
                computation.name(),
                computation.computedFieldRelPath(),
                computation.commonPrecondition().map(renderer::render).orElse(null),
                computation.alternatives().stream()
                        .map(alternative -> new DraftComputation.Alternative(
                                alternative.precondition().map(renderer::render).orElse(null),
                                renderer.render(alternative.operation()),
                                alternative.toleranceRangeOp().orElse(null)))
                        .toList(),
                computation.errorCodesToSuppress());
    }

    private static void rewriteOpaqueElementIds(ObjectNode root) {
        // Opaque ids are a foreign-storage witness: the typed kits correctly author name-path ids, then this
        // concrete DM-JSON boundary emulates models persisted by a producer whose ids carry no path meaning.
        var next = new int[] { 1 };
        rewriteOpaqueElementIds(root, next);
    }

    private static void rewriteOpaqueElementIds(JsonNode node, int[] next) {
        if (node.isObject()) {
            var elementType = node.path("type").asText();
            if (Set.of("Group", "Field", "Rule", "Computation").contains(elementType)) {
                ((ObjectNode) node).put("id", "opaque-" + next[0]++);
            }
            node.elements().forEachRemaining(child -> rewriteOpaqueElementIds(child, next));
        } else if (node.isArray()) {
            node.elements().forEachRemaining(child -> rewriteOpaqueElementIds(child, next));
        }
    }

    private static ObjectNode objectModel(String json) {
        try {
            return (ObjectNode) JSON.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("fixture is not valid JSON", exception);
        }
    }

    private static String writeModel(ObjectNode root) {
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root) + System.lineSeparator();
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("could not serialize typed-authored fixture", exception);
        }
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
            Map<String, String> expectedComputations,
            FixtureDisposition disposition) {

        public FixtureCase {
            id = Objects.requireNonNull(id, "id");
            entryModelId = Objects.requireNonNull(entryModelId, "entryModelId");
            Objects.requireNonNull(sources, "sources");
            Objects.requireNonNull(expectedFields, "expectedFields");
            Objects.requireNonNull(expectedRules, "expectedRules");
            Objects.requireNonNull(expectedComputations, "expectedComputations");
            disposition = Objects.requireNonNull(disposition, "disposition");
            sources = Collections.unmodifiableMap(new LinkedHashMap<>(sources));
            expectedFields = Set.copyOf(expectedFields);
            expectedRules = Set.copyOf(expectedRules);
            expectedComputations = Map.copyOf(expectedComputations);
            if (!sources.containsKey(entryModelId)) {
                throw new IllegalArgumentException("entry source is absent: " + entryModelId);
            }
            switch (disposition) {
                case VALID -> {
                }
                case MIXED_CONDITION_LANGUAGE -> {
                    if (!expectedFields.isEmpty()
                            || !expectedRules.isEmpty()
                            || !expectedComputations.isEmpty()) {
                        throw new IllegalArgumentException(
                                "a rejected fixture cannot claim prepared output");
                    }
                }
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
            private FixtureDisposition disposition = FixtureDisposition.VALID;

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

            public Builder disposition(FixtureDisposition value) {
                if (value != null) {
                    disposition = value;
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
                        expectedComputations,
                        disposition);
            }
        }
    }

    public enum FixtureDisposition {
        VALID,
        MIXED_CONDITION_LANGUAGE,
    }
}

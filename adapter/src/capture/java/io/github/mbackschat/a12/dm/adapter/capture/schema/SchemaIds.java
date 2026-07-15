package io.github.mbackschat.a12.dm.adapter.capture.schema;

/**
 * The capture boundary's published schema identities (CONFORMANCE-CORPUS-SPEC §15). A published ID is
 * IMMUTABLE: because every schema is closed, an added or reinterpreted member requires a NEW id (a
 * {@code -v2}), never a silent additive change — the capabilities golden fixture
 * ({@code CapabilitiesTest}) fails on an in-place mutation. Requests pin explicit IDs and never mean
 * "latest"; old packets stay verifiable after new versions are added.
 */
public final class SchemaIds {

    private SchemaIds() {
    }

    /** The closed scenario-set request schema (the consumer-authored input). */
    public static final String SCENARIO_SET = "capture-scenario-set-v1";

    /** The packet envelope schema ({@code PACKET.json}). */
    public static final String EVIDENCE_PACKET = "evidence-packet-v1";

    /** The tagged computation operation-payload schema (one per-case-per-runner observation artifact). */
    public static final String COMPUTE_OBSERVATION = "compute-observation-v1";

    /** The closed sorted artifact-inventory schema ({@code RECEIPT.json}, packet and report dirs alike). */
    public static final String RECEIPT = "capture-receipt-v1";

    /** The capability-discovery output schema. */
    public static final String CAPABILITIES = "a12-dmkits-capture-capabilities-v1";

    /** The capability output's own independent version (part of the compatibility identity). */
    public static final String CAPABILITIES_VERSION = "1";

    /** The per-model legality artifact schema (the §6 gate's provenance record). */
    public static final String LEGALITY = "capture-legality-v1";

    /** The qualification report schema ({@code REPORT.json} in a verify output dir). */
    public static final String QUALIFICATION_REPORT = "qualification-report-v1";

    /** The pinned policy+projection definition schema ({@code PROFILE.json}, digest-bound by the report). */
    public static final String QUALIFICATION_PROFILE = "qualification-profile-v1";

    /** The structural packet-diff report schema. */
    public static final String PACKET_DIFF_REPORT = "packet-diff-report-v1";

    /** The structural report-diff report schema. */
    public static final String REPORT_DIFF_REPORT = "report-diff-report-v1";

    /** The v1 execution-context/world profile: per-case local wall-clock instant (no zone crossing —
     *  timezone stays runner-local allowlisted runtime identity), locale pinned in the request. */
    public static final String WORLD_PROFILE_LOCAL_WALLCLOCK = "local-wallclock-v1";

    /** The v1 compute input-support identity: repeatable computation targets/scopes are REJECTED. */
    public static final String INPUT_SUPPORT_COMPUTE = "non-repeatable-compute-targets-v1";
}

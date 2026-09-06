package io.github.mbackschat.a12.dm.core.spi;

import java.util.List;

import io.github.mbackschat.a12.dm.core.ModelResolver;
import io.github.mbackschat.a12.dm.core.Notification;

/**
 * The R3 consistency-gate seam (KERNEL-DECOUPLING-PROPOSAL §2a): the narrow port behind which the real
 * kernel's {@code checkConsistency} lives, so the kits carry <b>zero</b> kernel imports. The one backend
 * ({@code :adapter}) implements it and registers via {@link java.util.ServiceLoader}; the kits resolve it
 * through {@link io.github.mbackschat.a12.dm.core.Backends}.
 *
 * <p>Speaks only our types — the audit proved {@code message}+{@code severity} is the entire consumed
 * surface, so the returned {@link Notification}s carry exactly that. This is <b>not re-implementable</b>
 * kernel-free (the MVK gate is the kernel's alone); when no backend is present the consistency-gate paths
 * degrade to structural-only rather than fabricating a verdict.
 */
public interface ConsistencyOracle {

    /**
     * Deserialize {@code dmJson}, expand it against {@code resolver} (resolving includes / type-def
     * imports), and run the real kernel's {@code checkConsistency}. Returns <em>every</em> reported
     * notification (INFO/WARNING/ERROR), in the kernel's order — the caller filters by severity (the load
     * gate keeps only ERROR to throw; the rule-check path enriches all of them). May throw if the kernel
     * cannot even parse/expand the model (a deserialize/expand-tier rejection).
     *
     * <p><b>Implementations MUST report a build failure inside the consistency check itself as
     * {@link io.github.mbackschat.a12.dm.core.ConsistencyCheckFailedException}.</b> Some backends refuse a
     * model they cannot construct by throwing rather than by reporting a structured problem — the real kernel
     * does this for a blank temporal {@code format} and for a duplicate child name (KERNEL-FINDINGS KF259).
     * That event is the backend's verdict on the caller's bytes, and the two callers of this port dispose of it
     * differently on purpose: {@link io.github.mbackschat.a12.dm.core.ModelLoader} folds it into an ERROR
     * notification, while the draft gate keeps publishing no receipt. Both dispositions key on the typed
     * exception, so an implementation that lets a bare {@link RuntimeException} escape instead makes a valid
     * rejection surface to the user as an internal tool defect — asserting the opposite of the truth.
     *
     * <p>The obligation is narrow, and deliberately so: it covers a failure of the consistency check itself,
     * never the deserialize or expand step above it. Those tiers report themselves, and relabelling one as a
     * consistency verdict would publish a gate receipt for a gate that never ran.
     */
    List<Notification> checkConsistency(String dmJson, ModelResolver resolver);
}

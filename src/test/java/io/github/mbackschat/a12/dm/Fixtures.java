package io.github.mbackschat.a12.dm;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.core.ModelIndex;

/**
 * The committed test models, loaded once — so a new family-focused test file costs a few lines,
 * not a copied {@code @BeforeAll} block. (Safe to share across tests: a {@link BoundModel} is an
 * immutable snapshot, F0.)
 */
public final class Fixtures {

    private static BoundModel subscription;
    private static BoundModel order;
    private static BoundModel project;
    private static BoundModel logistics;

    private Fixtures() {
    }

    /** The subscription model (enum+category, bool, confirm, repeatable Addons, Billing). */
    public static synchronized BoundModel subscription() {
        if (subscription == null) {
            subscription = bind("models/subscription.dm.json");
        }
        return subscription;
    }

    /** The order model (string/number/bool/confirm/enum fields, address groups, repeatable Items). */
    public static synchronized BoundModel order() {
        if (order == null) {
            order = bind("models/order.dm.json");
        }
        return order;
    }

    /**
     * The project model bound, so a §10 wildcard-path law can author its condition TYPED.
     *
     * <p>Added 2026-09-22 with {@link #logistics()}: {@code subscription()} and {@code order()} were bound and
     * these two were not, so a law against either fixture could only reach a typed condition by re-binding the
     * JSON itself — which is the friction the raw-String rows in those families are made of (TESTING-SPEC,
     * "A String condition stays correct for exactly two reasons").
     */
    public static synchronized BoundModel project() {
        if (project == null) {
            project = bind("models/project.dm.json");
        }
        return project;
    }

    /** The logistics model (two index-keyed repeatable groups) bound — authored via modelkit, not a resource. */
    public static synchronized BoundModel logistics() {
        if (logistics == null) {
            logistics = BoundModel.of(ModelIndex.ofSelfContained(ModelDrafts.logistics().toJson()));
        }
        return logistics;
    }

    /** The raw DM-JSON, for validator-level tests. */
    public static String json(String classpathLocation) {
        try (InputStream in = stream(classpathLocation)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("cannot read fixture " + classpathLocation, e);
        }
    }

    public static String subscriptionJson() {
        return json("models/subscription.dm.json");
    }

    public static String orderJson() {
        return json("models/order.dm.json");
    }

    /** The project model: nested repeatable groups (Milestones ▸ Tasks), for path/wildcard laws (§10). */
    public static String projectJson() {
        return json("models/project.dm.json");
    }

    private static BoundModel bind(String classpathLocation) {
        // read-only binding over the kernel-free index (decoupling Stage 2; the fixtures are known-valid)
        return BoundModel.of(ModelIndex.ofSelfContained(json(classpathLocation)));
    }

    private static InputStream stream(String classpathLocation) {
        InputStream in = Fixtures.class.getClassLoader().getResourceAsStream(classpathLocation);
        if (in == null) {
            throw new IllegalStateException("fixture not found: " + classpathLocation);
        }
        return in;
    }
}

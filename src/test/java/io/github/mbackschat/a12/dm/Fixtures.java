package io.github.mbackschat.a12.dm;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.core.ModelIndex;
import io.github.mbackschat.a12.dm.core.ModelLoader;

/**
 * The committed test models, loaded once — so a new family-focused test file costs a few lines,
 * not a copied {@code @BeforeAll} block. (Safe to share across tests: a {@link BoundModel} is an
 * immutable snapshot, F0.)
 */
public final class Fixtures {

    private static BoundModel subscription;
    private static BoundModel order;

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

    private static BoundModel bind(String classpathLocation) {
        try (Reader reader = new InputStreamReader(stream(classpathLocation), StandardCharsets.UTF_8)) {
            return BoundModel.of(ModelIndex.of(ModelLoader.loadSelfContained(reader)));
        } catch (Exception e) {
            throw new IllegalStateException("cannot load fixture " + classpathLocation, e);
        }
    }

    private static InputStream stream(String classpathLocation) {
        InputStream in = Fixtures.class.getClassLoader().getResourceAsStream(classpathLocation);
        if (in == null) {
            throw new IllegalStateException("fixture not found: " + classpathLocation);
        }
        return in;
    }
}

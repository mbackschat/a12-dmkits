package io.github.mbackschat.a12.dm.adapter.capture;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.adapter.capture.runner.kernel.GroovyDynamicRunner;
import io.github.mbackschat.a12.dm.adapter.capture.runner.kernel.JavaStaticRunner;

/**
 * The ROUTE-DISTINCTNESS guard (proposal §3): the two kernel runner names must invoke DISTINCT registered
 * adapter/code-generation routes — resolved here as the concrete rt-service implementation each route
 * constructs for the same model. Does-fire: pointing both runners at one route (the W5 mutation) makes
 * the two class names equal and this guard fails. The claim stays cross-route agreement within ONE kernel
 * implementation — never independent engines.
 */
class RouteDistinctnessTest {

    @Test
    void theTwoKernelRunnerNamesResolveToDistinctRoutes() {
        String modelJson = new String(CaptureFixtures.resourceBytes(CaptureFixtures.QUOTE_PREMIUM),
                StandardCharsets.UTF_8);
        String dynamicRoute = GroovyDynamicRunner.resolvedRouteClassName(modelJson);
        String staticRoute = JavaStaticRunner.resolvedRouteClassName(modelJson);
        assertNotEquals(dynamicRoute, staticRoute,
                "kernel-groovy-dynamic and kernel-java-static must construct DISTINCT rt-service routes —"
                        + " equal routes would fake cross-route confirmation");
    }
}

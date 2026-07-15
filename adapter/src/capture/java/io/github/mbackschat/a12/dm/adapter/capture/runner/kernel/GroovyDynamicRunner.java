package io.github.mbackschat.a12.dm.adapter.capture.runner.kernel;

import com.mgmtp.a12.kernel.md.facade.DocumentRtServiceFactory;
import com.mgmtp.a12.kernel.md.facade.DocumentServiceFactory;
import com.mgmtp.a12.kernel.md.model.api.IDocumentModel;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelResolver;
import com.mgmtp.a12.kernel.md.rt.api.IDocumentRtService;

import io.github.mbackschat.a12.dm.adapter.capture.dto.ComputeObservation;
import io.github.mbackschat.a12.dm.adapter.capture.dto.RunnerId;
import io.github.mbackschat.a12.dm.adapter.capture.runner.CaseExecution;
import io.github.mbackschat.a12.dm.adapter.capture.runner.ComputeRunner;

/**
 * The {@code kernel-groovy-dynamic} route (the behavioral ANCHOR): the kernel's md-runtime dynamic
 * service, built FRESH per case (fresh code cache, fresh factories, fresh document). Kernel types
 * terminate in this package.
 */
public final class GroovyDynamicRunner implements ComputeRunner {

    @Override
    public RunnerId id() {
        return RunnerId.KERNEL_GROOVY_DYNAMIC;
    }

    @Override
    public ComputeObservation observe(CaseExecution execution) {
        IDocumentModel model = KernelRouteSupport.expandedModel(execution.modelJson());
        IDocumentModelResolver resolver = id -> model;
        IDocumentRtService rt = new DocumentRtServiceFactory(resolver)
                .createDocumentRtService(KernelRouteSupport.freshDynamicConfig());
        return KernelRouteSupport.observe(id(), rt, new DocumentServiceFactory(resolver), execution);
    }

    /** The resolved rt-service implementation class name — for the ROUTE-DISTINCTNESS guard only (a test
     *  assertion; this string never enters a packet or report). */
    public static String resolvedRouteClassName(String modelJson) {
        IDocumentModel model = KernelRouteSupport.expandedModel(modelJson);
        IDocumentModelResolver resolver = id -> model;
        return new DocumentRtServiceFactory(resolver)
                .createDocumentRtService(KernelRouteSupport.freshDynamicConfig()).getClass().getName();
    }
}

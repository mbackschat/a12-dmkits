package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.mgmtp.a12.kernel.md.facade.DocumentModelServiceFactory;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelSerializer;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelService;
import org.junit.jupiter.api.Test;

/**
 * Scaffold smoke test: proves the public kernel consumer surface (kernel-md-facade) resolves
 * from mavenLocal and that the services rulekit's validate seam (PROJECT-DESIGN §11) will use are
 * reachable through the public API alone — no internal-tier dependency required.
 *
 * <p>This is the Phase-0/1 foundation check: if the bootstrap has published the kernel and the
 * dependency wiring is correct, this passes; otherwise it fails fast with a clear classpath error.
 */
class KernelWiringTest {

    @Test
    void publicKernelServicesAreReachable() {
        DocumentModelServiceFactory factory = new DocumentModelServiceFactory();

        IDocumentModelService modelService = factory.createDocumentModelService();
        IDocumentModelSerializer serializer = factory.createDocumentModelSerializer();

        assertNotNull(modelService, "IDocumentModelService should be creatable via the public facade");
        assertNotNull(serializer, "IDocumentModelSerializer should be creatable via the public facade");
    }
}

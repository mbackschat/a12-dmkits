package io.github.mbackschat.a12.dm.adapter.closure;

import java.io.StringReader;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.mgmtp.a12.kernel.md.combination.a12internal.CombinationModelService;
import com.mgmtp.a12.kernel.md.combination.a12internal.CombinationModelService.CombinationModelExpandParams;
import com.mgmtp.a12.kernel.md.combination.a12internal.IUnexpandedModelResolver;
import com.mgmtp.a12.kernel.md.document.apiV2.DocumentPointer;
import com.mgmtp.a12.kernel.md.document.apiV2.UpdateAction;
import com.mgmtp.a12.kernel.md.document.apiV2.immutable.DocumentV2;
import com.mgmtp.a12.kernel.md.document.apiV2.immutable.FieldInstanceV2;
import com.mgmtp.a12.kernel.md.document.apiV2.immutable.GroupInstanceV2;
import com.mgmtp.a12.kernel.md.facade.DocumentModelServiceFactory;
import com.mgmtp.a12.kernel.md.facade.DocumentRtServiceFactory;
import com.mgmtp.a12.kernel.md.model.api.IDocumentModel;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelResolver;
import com.mgmtp.a12.kernel.md.rt.api.DocumentProcessingConfig;
import com.mgmtp.a12.kernel.md.rt.api.IDocumentDynamicServiceConfig;
import com.mgmtp.a12.kernel.md.rt.api.IDocumentModelIdProvider;
import com.mgmtp.a12.kernel.md.rt.api.IDocumentRtService;
import com.mgmtp.a12.kernel.md.rt.api.IDocumentValidationResult;
import com.mgmtp.a12.kernel.md.rt.api.ILabelProvider;
import com.mgmtp.a12.kernel.md.rt.api.IModelCode;
import com.mgmtp.a12.kernel.md.rt.api.IModelCodeCache;

/**
 * Minimal fresh consumer used only to make the informative kernel runtime-closure comparator capability-equivalent
 * to the interpreter's prepared-document validation surface.
 */
@SuppressWarnings("deprecation")
public final class KernelValidationConsumer {

    // This is intentionally a kernel-only boundary fixture: using a dmkits authoring type here would add the
    // interpreter's own product graph to the independent baseline being measured. The tiny model is embedded so
    // the measured consumer jar is self-contained and the execution/report paths exercise the same artifact.
    private static final String MODEL = """
            {
              "header": {
                "id": "closure-probe",
                "modelType": "document",
                "modelVersion": "29.4.0",
                "locales": [ { "code": "en_US" } ],
                "labels": [],
                "annotations": [],
                "modelReferences": []
              },
              "content": {
                "modelInfo": {
                  "name": "closure-probe",
                  "variant": "cockpit",
                  "revision": "Arbeitsversion",
                  "immutable": false
                },
                "modelConfig": {
                  "decimalSeparator": ".",
                  "timeZone": "UTC",
                  "conditionLanguage": { "code": "en_US" },
                  "fieldRefByShortNameAllowed": true
                },
                "modelRoot": {
                  "rootGroups": [ {
                    "type": "Group",
                    "id": "/Closure",
                    "name": "Closure",
                    "Group": {
                      "repeatability": 1,
                      "elements": [
                        {
                          "type": "Field",
                          "id": "/Closure/Flag",
                          "name": "Flag",
                          "Field": { "fieldType": { "type": "BooleanType" } }
                        },
                        {
                          "type": "Rule",
                          "id": "/Closure/FlagPresent",
                          "name": "FlagPresent",
                          "Rule": {
                            "errorEntityRelPath": "../Flag",
                            "errorCode": "CLOSURE_PROBE",
                            "errorCondition": "FieldFilled(Flag)",
                            "severity": "ERROR",
                            "errorMessage": [ { "locale": "en_US", "text": "probe" } ]
                          }
                        }
                      ]
                    }
                  } ]
                }
              }
            }
            """;

    private KernelValidationConsumer() {
    }

    public static void main(String[] args) throws Exception {
        IDocumentModel model = preparedModel();
        IDocumentModelResolver resolver = ignored -> model;
        DocumentV2 immutable = DocumentV2.empty("closure-probe").withBatchUpdates(List.of(
                UpdateAction.putGroup(DocumentPointer.of("/Closure", List.of(1)), GroupInstanceV2.empty()),
                UpdateAction.putField(DocumentPointer.of("/Closure/Flag", List.of(1, 1)), FieldInstanceV2.ofValue("true"))));
        IDocumentRtService runtime = new DocumentRtServiceFactory(resolver).createDocumentRtService(dynamicConfig());
        IDocumentValidationResult result =
                runtime.validateFull(immutable, DocumentProcessingConfig.builder(Locale.US).build());
        List<String> codes = result.getMessages().stream().map(message -> message.getErrorCode()).toList();
        if (!codes.equals(List.of("CLOSURE_PROBE"))) {
            throw new IllegalStateException("fresh kernel validation consumer returned " + codes);
        }
        System.out.println("fresh kernel validation consumer executed: " + codes);
    }

    private static IDocumentModel preparedModel() throws Exception {
        DocumentModelServiceFactory factory = new DocumentModelServiceFactory();
        IDocumentModel model = factory.createDocumentModelSerializer().deserialize(new StringReader(MODEL));
        return CombinationModelService.expand(model, (IUnexpandedModelResolver) reference -> {
            throw new IllegalStateException("self-contained closure probe requested " + reference);
        }, CombinationModelExpandParams.builder().build()).orElseThrow();
    }

    private static IDocumentDynamicServiceConfig dynamicConfig() {
        IModelCodeCache cache = new IModelCodeCache() {
            private final Map<String, IModelCode> codes = new HashMap<>();

            @Override
            public IModelCode getModelCode(String id) {
                return codes.get(id);
            }

            @Override
            public void addModelCode(String id, IModelCode code) {
                codes.put(id, code);
            }
        };
        return new IDocumentDynamicServiceConfig() {
            @Override
            public IModelCodeCache getCache() {
                return cache;
            }

            @Override
            public Optional<ILabelProvider> getLabelProvider() {
                return Optional.empty();
            }

            @Override
            public Optional<IDocumentModelIdProvider> getModelIdProvider() {
                return Optional.empty();
            }
        };
    }
}

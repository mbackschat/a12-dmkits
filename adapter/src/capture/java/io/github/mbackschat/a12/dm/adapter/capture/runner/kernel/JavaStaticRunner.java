package io.github.mbackschat.a12.dm.adapter.capture.runner.kernel;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import com.mgmtp.a12.kernel.md.facade.DocumentModelServiceFactory;
import com.mgmtp.a12.kernel.md.facade.DocumentRtServiceFactory;
import com.mgmtp.a12.kernel.md.facade.DocumentServiceFactory;
import com.mgmtp.a12.kernel.md.model.api.IDocumentModel;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelResolver;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelService;
import com.mgmtp.a12.kernel.md.model.api.services.IValidationCodeGeneratorConfig;
import com.mgmtp.a12.kernel.md.rt.api.IDocumentRtService;
import com.mgmtp.a12.kernel.md.rt.api.IDocumentStaticServiceConfig;
import com.mgmtp.a12.kernel.md.rt.api.ILabelProvider;

import io.github.mbackschat.a12.dm.adapter.capture.dto.ComputeObservation;
import io.github.mbackschat.a12.dm.adapter.capture.dto.RunnerId;
import io.github.mbackschat.a12.dm.adapter.capture.runner.CaseExecution;
import io.github.mbackschat.a12.dm.adapter.capture.runner.ComputeRunner;

/**
 * The {@code kernel-java-static} route (CROSS-ROUTE CONFIRMATION through the same kernel artifact): the
 * kernel's own generated-JAVA validator, compiled in-memory per case and run through the kernel's STATIC
 * rt-service — a registered route provably distinct from the dynamic one ({@code RouteDistinctnessTest}),
 * while the claim stays cross-route agreement within ONE kernel implementation, never independent
 * engines. Generation + compilation happen fresh per case (no cross-case cache).
 */
public final class JavaStaticRunner implements ComputeRunner {

    @Override
    public RunnerId id() {
        return RunnerId.KERNEL_JAVA_STATIC;
    }

    @Override
    public ComputeObservation observe(CaseExecution execution) {
        IDocumentModel model = KernelRouteSupport.expandedModel(execution.modelJson());
        IDocumentModelResolver resolver = id -> model;
        return KernelRouteSupport.observe(id(), staticRtService(model, resolver),
                new DocumentServiceFactory(resolver), execution);
    }

    /** The resolved rt-service implementation class name — for the ROUTE-DISTINCTNESS guard only (a test
     *  assertion; this string never enters a packet or report). */
    public static String resolvedRouteClassName(String modelJson) {
        IDocumentModel model = KernelRouteSupport.expandedModel(modelJson);
        IDocumentModelResolver resolver = id -> model;
        return staticRtService(model, resolver).getClass().getName();
    }

    private static IDocumentRtService staticRtService(IDocumentModel expanded, IDocumentModelResolver resolver) {
        Properties idToPackage = new Properties();
        Map<String, String> sources = generateSources(expanded, idToPackage);
        ClassLoader codeLoader = compileInMemory(sources);
        return new DocumentRtServiceFactory(resolver)
                .createDocumentRtService(staticConfig(idToPackage, codeLoader));
    }

    /** Generate the kernel's JAVA validation source for {@code expanded}; returns {@code zipPath → source}
     *  and fills {@code idToPackageOut} (the static path's model-id → generated-package mapping). */
    private static Map<String, String> generateSources(IDocumentModel expanded, Properties idToPackageOut) {
        IDocumentModelService modelService = new DocumentModelServiceFactory().createDocumentModelService();
        IValidationCodeGeneratorConfig config = () -> IValidationCodeGeneratorConfig.ProgrammingLanguage.JAVA;
        List<String> notes = new ArrayList<>();
        byte[] zip = modelService.generateValidationCode(expanded, config, idToPackageOut,
                n -> notes.add(n.getSeverity() + ": " + n.getMessage()));
        if (zip == null) {
            throw new IllegalStateException("generateValidationCode(JAVA) returned null; notifications=" + notes);
        }
        Map<String, String> sources = new HashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().endsWith(".java")) {
                    sources.put(entry.getName(), new String(zis.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("failed to read the generated code zip", e);
        }
        return sources;
    }

    private static IDocumentStaticServiceConfig staticConfig(Properties idToPackage, ClassLoader codeLoader) {
        return new IDocumentStaticServiceConfig() {
            @Override
            public Optional<String> getModelPackage(String id) {
                return Optional.ofNullable(idToPackage.getProperty(id));
            }

            @Override
            public Optional<ClassLoader> getModelCodeClassLoader(String id) {
                return Optional.of(codeLoader);
            }

            @Override
            public Optional<ILabelProvider> getLabelProvider() {
                return Optional.empty();
            }

            @Override
            public Optional<String> getVariant() {
                return Optional.empty();
            }
        };
    }

    /** Compile {@code sources} in-memory against this JVM's classpath; return a loader for the classes. */
    private static ClassLoader compileInMemory(Map<String, String> sources) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("a JDK (not a JRE) is required for the kernel-java-static route");
        }
        Map<String, ByteArrayOutputStream> compiled = new HashMap<>();
        StandardJavaFileManager std = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8);
        ForwardingJavaFileManager<StandardJavaFileManager> fm = new ForwardingJavaFileManager<>(std) {
            @Override
            public JavaFileObject getJavaFileForOutput(JavaFileManager.Location loc, String className,
                    JavaFileObject.Kind kind, FileObject sibling) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                compiled.put(className, bytes);
                return new SimpleJavaFileObject(
                        URI.create("mem:///" + className.replace('.', '/') + ".class"), JavaFileObject.Kind.CLASS) {
                    @Override
                    public OutputStream openOutputStream() {
                        return bytes;
                    }
                };
            }
        };
        List<JavaFileObject> units = new ArrayList<>();
        sources.forEach((path, code) -> units.add(
                new SimpleJavaFileObject(URI.create("string:///" + path), JavaFileObject.Kind.SOURCE) {
                    @Override
                    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                        return code;
                    }
                }));
        List<String> options = List.of("-classpath", System.getProperty("java.class.path"));
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        boolean ok = compiler.getTask(null, fm, diagnostics, options, null, units).call();
        if (!ok) {
            StringBuilder sb = new StringBuilder("the generated Java failed to compile:\n");
            for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
                sb.append("  ").append(d).append('\n');
            }
            throw new IllegalStateException(sb.toString());
        }
        return new ClassLoader(JavaStaticRunner.class.getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                ByteArrayOutputStream bytes = compiled.get(name);
                if (bytes == null) {
                    throw new ClassNotFoundException(name);
                }
                byte[] arr = bytes.toByteArray();
                return defineClass(name, arr, 0, arr.length);
            }
        };
    }
}

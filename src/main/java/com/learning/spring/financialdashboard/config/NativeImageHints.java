package com.learning.spring.financialdashboard.config;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;

/**
 * Extra hints for the GraalVM native build (used only while the native image is made; the normal JVM run ignores this class).
 *
 * <p>Hibernate writes its log messages through JBoss Logging interfaces such as {@code JpaLogger}. JBoss Logging finds the class that implements
 * each one by its name ({@code JpaLogger_$logger}) with a plain {@code Class.forName}, which a native image cannot do for a class nobody
 * declared. So every {@code *_$logger} class of Hibernate is declared here, found by scanning the jar while the build runs.
 */
public class NativeImageHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver(classLoader);
            for (Resource resource : resolver.getResources("classpath*:org/hibernate/**/*_$logger.class")) {
                String path = resource.getURL().toString();
                int at = path.lastIndexOf("!/") >= 0 ? path.lastIndexOf("!/") + 2 : path.indexOf("org/hibernate/");   // inside a jar: after the "!/"
                String name = path.substring(at, path.length() - ".class".length()).replace('/', '.');
                hints.reflection().registerType(TypeReference.of(name),
                        MemberCategory.INVOKE_DECLARED_CONSTRUCTORS, MemberCategory.INVOKE_DECLARED_METHODS);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not scan for Hibernate's logger classes", e);
        }
    }
}

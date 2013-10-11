package de.is24.deadcode4j.analyzer;

import de.is24.deadcode4j.Analyzer;
import de.is24.deadcode4j.CodeContext;
import javassist.ClassPool;
import javassist.CtClass;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Collection;

/**
 * Analyzes class files: lists the classes a class is depending on.
 *
 * @since 1.0.0
 */
public class ClassFileAnalyzer implements Analyzer {

    @Override
    public void doAnalysis(@Nonnull CodeContext codeContext, @Nonnull File file) {
        if (file.getName().endsWith(".class")) {
            analyzeClass(codeContext, file);
        }
    }

    @SuppressWarnings("unchecked")
    private void analyzeClass(@Nonnull CodeContext codeContext, @Nonnull File clazz) {
        final CtClass ctClass;
        try {
            ctClass = new ClassPool(false).makeClass(new FileInputStream(clazz));
        } catch (IOException e) {
            throw new RuntimeException("Could not analyze [" + clazz + "]!", e);
        }
        String className = ctClass.getName();

        Collection refClasses = ctClass.getRefClasses();
        refClasses.remove(className);

        codeContext.addAnalyzedClass(className);
        codeContext.addDependencies(className, refClasses);
    }

}
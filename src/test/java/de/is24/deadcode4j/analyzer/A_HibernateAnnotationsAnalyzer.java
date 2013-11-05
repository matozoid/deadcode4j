package de.is24.deadcode4j.analyzer;

import de.is24.deadcode4j.Analyzer;
import de.is24.deadcode4j.CodeContext;
import org.junit.Before;
import org.junit.Test;

import java.util.Map;

import static com.google.common.collect.Iterables.concat;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;

public final class A_HibernateAnnotationsAnalyzer extends AnAnalyzer {

    private Analyzer objectUnderTest;

    @Before
    public void setUp() throws Exception {
        objectUnderTest = new HibernateAnnotationsAnalyzer();
    }

    @Test
    public void shouldRecognizeDependencyFromClassWithTypeAnnotatedFieldToTypeDefAnnotatedClass() {
        CodeContext codeContext = new CodeContext();
        objectUnderTest.doAnalysis(codeContext, getFile("de/is24/deadcode4j/analyzer/hibernateannotations/ClassWithTypeDef.class"));
        objectUnderTest.doAnalysis(codeContext, getFile("de/is24/deadcode4j/analyzer/hibernateannotations/ClassUsingTypeAtField.class"));


        Map<String, ? extends Iterable<String>> codeDependencies = codeContext.getAnalyzedCode().getCodeDependencies();
        assertThat("Should have analyzed the class files!", codeContext.getAnalyzedCode().getAnalyzedClasses(), hasSize(2));
        assertThat(codeDependencies.keySet(), contains("de.is24.deadcode4j.analyzer.hibernateannotations.ClassUsingTypeAtField"));
        assertThat(concat(codeDependencies.values()), contains("de.is24.deadcode4j.analyzer.hibernateannotations.ClassWithTypeDef"));
    }

    @Test
    public void shouldRecognizeDependencyFromClassWithTypeAnnotatedMethodToTypeDefAnnotatedClass() {
        CodeContext codeContext = new CodeContext();
        objectUnderTest.doAnalysis(codeContext, getFile("de/is24/deadcode4j/analyzer/hibernateannotations/ClassWithTypeDef.class"));
        objectUnderTest.doAnalysis(codeContext, getFile("de/is24/deadcode4j/analyzer/hibernateannotations/ClassUsingTypeAtMethod.class"));


        Map<String, ? extends Iterable<String>> codeDependencies = codeContext.getAnalyzedCode().getCodeDependencies();
        assertThat("Should have analyzed the class files!", codeContext.getAnalyzedCode().getAnalyzedClasses(), hasSize(2));
        assertThat(codeDependencies.keySet(), contains("de.is24.deadcode4j.analyzer.hibernateannotations.ClassUsingTypeAtMethod"));
        assertThat(concat(codeDependencies.values()), contains("de.is24.deadcode4j.analyzer.hibernateannotations.ClassWithTypeDef"));
    }

    @Test
    public void shouldRecognizeDependencyFromTypeAnnotatedClassesToTypeDefsAnnotatedPackage() {
        CodeContext codeContext = new CodeContext();
        objectUnderTest.doAnalysis(codeContext, getFile("de/is24/deadcode4j/analyzer/hibernateannotations/Entity.class"));
        objectUnderTest.doAnalysis(codeContext, getFile("de/is24/deadcode4j/analyzer/hibernateannotations/package-info.class"));
        objectUnderTest.doAnalysis(codeContext, getFile("de/is24/deadcode4j/analyzer/hibernateannotations/AnotherEntity.class"));


        Map<String, ? extends Iterable<String>> codeDependencies = codeContext.getAnalyzedCode().getCodeDependencies();
        assertThat("Should have analyzed the class files!", codeContext.getAnalyzedCode().getAnalyzedClasses(), hasSize(3));
        assertThat(codeDependencies.keySet(), containsInAnyOrder(
                "de.is24.deadcode4j.analyzer.hibernateannotations.Entity",
                "de.is24.deadcode4j.analyzer.hibernateannotations.AnotherEntity"));
        assertThat(concat(codeDependencies.values()), contains(
                "de.is24.deadcode4j.analyzer.hibernateannotations.package-info",
                "de.is24.deadcode4j.analyzer.hibernateannotations.package-info"));
    }

}

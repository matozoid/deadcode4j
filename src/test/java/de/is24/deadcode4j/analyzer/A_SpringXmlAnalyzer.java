package de.is24.deadcode4j.analyzer;

import de.is24.deadcode4j.CodeContext;
import javassist.ClassPool;
import org.junit.Test;

import java.util.Map;

import static com.google.common.collect.Iterables.getOnlyElement;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.mock;

public final class A_SpringXmlAnalyzer {

    @Test
    public void shouldParseSpringFiles() {
        SpringXmlAnalyzer objectUnderTest = new SpringXmlAnalyzer();

        CodeContext codeContext = new CodeContext(getClass().getClassLoader(), mock(ClassPool.class));
        objectUnderTest.doAnalysis(codeContext, "spring.xml");

        Map<String, ? extends Iterable<String>> codeDependencies = codeContext.getAnalyzedCode().getCodeDependencies();
        assertThat("Should have analyzed the XML file!", codeDependencies.size(), is(1));
        assertThat(getOnlyElement(codeDependencies.values()), contains("SpringXmlBean"));
    }

}
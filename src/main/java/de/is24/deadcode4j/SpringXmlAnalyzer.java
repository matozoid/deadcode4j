package de.is24.deadcode4j;

import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

import javax.annotation.Nonnull;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import java.util.Collection;

import static com.google.common.collect.Lists.newArrayList;

/**
 * Analyzes Spring XML files: lists the classes being referenced.
 *
 * @since 1.0.2
 */
public class SpringXmlAnalyzer implements Analyzer {
    private final SAXParser parser;
    private final DefaultHandler handler;
    private final Collection<String> referencedClasses = newArrayList();

    public SpringXmlAnalyzer() {
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            this.parser = factory.newSAXParser();
        } catch (Exception e) {
            throw new RuntimeException("Failed to set up XML parser!", e);
        }
        this.handler = new DefaultHandler() {
            private boolean firstElement = true;
            private boolean isSpringFile = false;

            @Override
            public void startElement(String uri, String localName, String qName, Attributes attributes) {
                if (firstElement && "beans".equals(qName)) {
                    isSpringFile = true;
                } else {
                    firstElement = false;
                }
                if (!isSpringFile) {
                    return;
                }
                if (!"bean".equals(qName)) {
                    return;
                }

                final String className = attributes.getValue("class");
                if (className != null) {
                    referencedClasses.add(className);
                }
            }
        };
    }

    public void doAnalysis(@Nonnull CodeContext codeContext, @Nonnull String fileName) {
        if (fileName.endsWith(".xml")) {
            analyzeXmlFile(codeContext, fileName);
        }
    }

    private void analyzeXmlFile(@Nonnull CodeContext codeContext, @Nonnull String file) {
        this.referencedClasses.clear();
        try {
            parser.parse(codeContext.getClassLoader().getResourceAsStream(file), handler);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse [" + file + "]!", e);
        }
        codeContext.addDependencies("_Spring_", this.referencedClasses);
    }

}
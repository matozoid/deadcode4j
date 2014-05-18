package de.is24.deadcode4j.analyzer;

import de.is24.deadcode4j.Analyzer;
import de.is24.deadcode4j.CodeContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;

/**
 * The <code>AnalyzerAdapter</code> implements all non-vital methods defined for an <code>Analyzer</code> with a no-op.
 *
 * @since 1.4
 */
public abstract class AnalyzerAdapter implements Analyzer {

    protected final Logger logger = LoggerFactory.getLogger(getClass());

    @Override
    public String toString() {
        return getClass().getName();
    }

    @Override
    public void finishAnalysis(@Nonnull CodeContext codeContext) {
    }

    @Override
    public void finishAnalysis() {
    }

}

package de.is24.deadcode4j;

import javax.annotation.Nonnull;

/**
 * An <code>Analyzer</code> analyzes code of all flavours: java classes, spring XML files, <tt>web.xml</tt> etc.
 *
 * @since 1.0.2
 */
public interface Analyzer {
    public AnalyzedCode analyze();

    /**
     * Perform an analysis for the specified file.
     *
     * @since 1.0.2
     */
    public void doAnalysis(@Nonnull CodeContext codeContext, @Nonnull String fileName);
}

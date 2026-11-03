package dev.clashsub.parse;

import dev.clashsub.common.Diagnostic;

public class YamlInputException extends RuntimeException {
    private final Diagnostic diagnostic;
    public YamlInputException(Diagnostic diagnostic) { super(diagnostic.message()); this.diagnostic = diagnostic; }
    public Diagnostic diagnostic() { return diagnostic; }
}

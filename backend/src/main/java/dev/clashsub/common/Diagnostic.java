package dev.clashsub.common;

public record Diagnostic(Severity severity, String code, String path, Integer line, Integer column, String message) {
    public enum Severity { ERROR, WARNING }
    public static Diagnostic error(String code, String path, String message) {
        return new Diagnostic(Severity.ERROR, code, path, null, null, message);
    }
    public static Diagnostic warning(String code, String path, String message) {
        return new Diagnostic(Severity.WARNING, code, path, null, null, message);
    }
}

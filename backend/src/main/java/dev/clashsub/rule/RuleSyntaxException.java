package dev.clashsub.rule;

final class RuleSyntaxException extends RuntimeException {
    private final String code;
    RuleSyntaxException(String code, String message) { super(message); this.code = code; }
    String code() { return code; }
}

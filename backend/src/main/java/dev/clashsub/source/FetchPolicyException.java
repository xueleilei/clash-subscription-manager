package dev.clashsub.source;

public class FetchPolicyException extends RuntimeException {
    private final String code;
    public FetchPolicyException(String code) { super(code); this.code = code; }
    public String code() { return code; }
}

package dev.clashsub.common;

/** A safe, user-facing error, distinct from internal exception messages. */
public class ApiException extends RuntimeException {
    private final int status;
    private final String code;

    public ApiException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() { return status; }
    public String code() { return code; }

    public static ApiException invalid(String message) { return new ApiException(400, "A0001", message); }
    public static ApiException missing() { return new ApiException(404, "A0004", "资源不存在"); }
    public static ApiException conflict() { return new ApiException(409, "A0003", "数据已变化，请重新加载后重试"); }
}

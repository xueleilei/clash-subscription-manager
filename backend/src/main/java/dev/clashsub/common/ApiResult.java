package dev.clashsub.common;

import java.util.UUID;

/** The public response envelope; diagnostics never contain credentials. */
public record ApiResult<T>(String code, String message, T data, String traceId) {
    public static <T> ApiResult<T> success(T data) {
        return new ApiResult<>("00000", "OK", data, UUID.randomUUID().toString());
    }

    public static ApiResult<Void> failure(String code, String message) {
        return new ApiResult<>(code, message, null, UUID.randomUUID().toString());
    }
}

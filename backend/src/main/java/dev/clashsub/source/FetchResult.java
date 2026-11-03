package dev.clashsub.source;

import java.time.Instant;

public record FetchResult(byte[] body, int status, String contentType, Instant receivedAt, String errorCode) {
    public FetchResult { body = body.clone(); }
    @Override public byte[] body() { return body.clone(); }
    public boolean success() { return status == 200 && errorCode == null; }
    public static FetchResult failure(String code) { return new FetchResult(new byte[0], 0, "", Instant.now(), code); }
    @Override public String toString() { return "FetchResult[status=" + status + ", errorCode=" + errorCode + "]"; }
}

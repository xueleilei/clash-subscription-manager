package dev.clashsub.source;

import java.time.Duration;

public record FetchLimits(Duration connect, Duration response, Duration total, int maxBytes, int maxRedirects) {
    public FetchLimits {
        if (connect.isNegative() || connect.isZero() || response.isNegative() || response.isZero()
                || total.isNegative() || total.isZero() || maxBytes < 1 || maxRedirects < 0) { throw new IllegalArgumentException(); }
    }
    public static FetchLimits defaults() { return new FetchLimits(Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofSeconds(45), 10 * 1024 * 1024, 3); }
}

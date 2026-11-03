package dev.clashsub.source;

import dev.clashsub.parse.InputFormat;
import java.util.Map;
import java.util.UUID;

public record SourceCommand(UUID id, Long expectedVersion, String name, Boolean enabled, Integer refreshIntervalSeconds,
        InputFormat inputFormat, String url, Map<String, String> headers, String userAgent, String outboundProxy) {
    @Override public String toString() { return "SourceCommand[id=" + id + "]"; }
}

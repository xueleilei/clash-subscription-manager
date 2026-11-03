package dev.clashsub.source;

import java.util.Map;

public record SourceRequest(String url, Map<String, String> headers, String userAgent, String outboundProxy) {
    public SourceRequest {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        userAgent = userAgent == null ? "" : userAgent;
        outboundProxy = outboundProxy == null ? "" : outboundProxy;
    }
    @Override public String toString() { return "SourceRequest[redacted]"; }
}

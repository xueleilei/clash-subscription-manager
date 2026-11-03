package dev.clashsub.source;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

public final class SourceRequestValidator {
    private static final Set<String> FORBIDDEN = Set.of("host", "content-length", "transfer-encoding", "connection",
            "upgrade", "keep-alive", "trailer", "te", "proxy-authorization", "proxy-connection", "accept-encoding", "user-agent");
    private SourceRequestValidator() { }
    public static URI url(String value) {
        try {
            var uri = URI.create(value);
            if (uri.getScheme() == null || !Set.of("http", "https").contains(uri.getScheme().toLowerCase(Locale.ROOT))
                    || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                    || uri.getPort() > 65535 || uri.getPort() == 0 || value.length() > 8192) { throw new IllegalArgumentException(); }
            return uri;
        } catch (IllegalArgumentException | NullPointerException exception) { throw new FetchPolicyException("UPSTREAM_INVALID_URL"); }
    }
    public static void validate(SourceRequest request) {
        url(request.url());
        if (request.headers().size() > 50 || request.userAgent().length() > 1024 || badValue(request.userAgent())) {
            throw new FetchPolicyException("UPSTREAM_INVALID_HEADERS");
        }
        var names = new java.util.HashSet<String>();
        for (var header : request.headers().entrySet()) {
            if (!header.getKey().matches("[!#$%&'*+.^_`|~0-9a-zA-Z-]+")
                    || !names.add(header.getKey().toLowerCase(Locale.ROOT))
                    || FORBIDDEN.contains(header.getKey().toLowerCase(Locale.ROOT)) || header.getValue().length() > 8192
                    || badValue(header.getValue())) { throw new FetchPolicyException("UPSTREAM_INVALID_HEADERS"); }
        }
    }
    private static boolean badValue(String value) { return value.chars().anyMatch(c -> c < 32 && c != '\t' || c == 127); }
}

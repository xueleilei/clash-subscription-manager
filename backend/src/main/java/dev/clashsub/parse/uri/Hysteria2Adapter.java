package dev.clashsub.parse.uri;

import java.util.Set;

public class Hysteria2Adapter implements UriNodeAdapter {
    @Override public boolean supports(String scheme) { return scheme.equals("hy2") || scheme.equals("hysteria2"); }
    @Override public ParsedNodeResult parse(String uri, int line) { return UriSupport.parse(line, () -> {
        var parts = UriSupport.parts(uri);
        var node = UriSupport.base(parts, "hysteria2", 443).put("password", parts.auth());
        var query = parts.query();
        UriSupport.allowed(query, Set.of("sni", "insecure", "obfs", "obfs-password", "pinSHA256", "alpn"));
        for (String key : Set.of("sni", "obfs", "obfs-password")) { UriSupport.copy(node, query, key, key); }
        if (query.containsKey("insecure")) { node.put("skip-cert-verify", UriSupport.bool(query.get("insecure"))); }
        if (query.containsKey("pinSHA256")) {
            String fingerprint = query.get("pinSHA256").replace(":", "");
            if (!fingerprint.matches("[0-9a-fA-F]{64}")) { throw new IllegalArgumentException(); }
            node.put("fingerprint", fingerprint);
        }
        if (query.containsKey("alpn")) { UriSupport.strings(node, "alpn", query.get("alpn")); }
        if (parts.ports().contains(",") || parts.ports().contains("-")) { node.put("ports", parts.ports()); }
        return node;
    }); }
}

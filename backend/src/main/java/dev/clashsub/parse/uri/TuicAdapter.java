package dev.clashsub.parse.uri;

import java.util.Set;

public class TuicAdapter implements UriNodeAdapter {
    @Override public boolean supports(String scheme) { return scheme.equals("tuic"); }
    @Override public ParsedNodeResult parse(String uri, int line) { return UriSupport.parse(line, () -> {
        var parts = UriSupport.parts(uri);
        var node = UriSupport.base(parts, "tuic", -1);
        int colon = parts.auth().indexOf(':');
        if (colon < 0) { node.put("token", parts.auth()); }
        else { node.put("uuid", parts.auth().substring(0, colon)).put("password", parts.auth().substring(colon + 1)); }
        var query = parts.query();
        UriSupport.allowed(query, Set.of("sni", "alpn", "congestion_control", "udp_relay_mode", "allow_insecure", "allowInsecure", "disable_sni", "reduce_rtt"));
        UriSupport.copy(node, query, "sni", "sni");
        UriSupport.copy(node, query, "congestion_control", "congestion-controller");
        UriSupport.copy(node, query, "udp_relay_mode", "udp-relay-mode");
        if (query.containsKey("alpn")) { UriSupport.strings(node, "alpn", query.get("alpn")); }
        var insecure = UriSupport.alias(query, "allow_insecure", "allowInsecure");
        if (insecure != null) { node.put("skip-cert-verify", UriSupport.bool(insecure)); }
        if (query.containsKey("disable_sni")) { node.put("disable-sni", UriSupport.bool(query.get("disable_sni"))); }
        if (query.containsKey("reduce_rtt")) { node.put("reduce-rtt", UriSupport.bool(query.get("reduce_rtt"))); }
        return node;
    }); }
}

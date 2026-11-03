package dev.clashsub.parse.uri;

import dev.clashsub.parse.Base64SubscriptionDecoder;
import java.util.LinkedHashMap;
import java.util.Set;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public class VmessAdapter implements UriNodeAdapter {
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(50).maxStringLength(10 * 1024 * 1024).build()).build()).build();
    private static final Set<String> FIELDS = Set.of("v", "ps", "add", "port", "id", "aid", "scy", "net", "type", "host", "path", "tls", "sni", "alpn", "fp", "allowInsecure");
    @Override public boolean supports(String scheme) { return scheme.equals("vmess"); }
    @Override public ParsedNodeResult parse(String uri, int line) { return UriSupport.parse(line, () -> {
        if (uri.contains("@")) {
            var parts = UriSupport.parts(uri);
            var node = UriSupport.base(parts, "vmess", -1).put("uuid", parts.auth()).put("alterId", 0).put("cipher", "auto");
            UriSupport.transport(node, parts.query(), false); return node;
        }
        var raw = JSON.readTree(Base64SubscriptionDecoder.decode(uri.substring(uri.indexOf("://") + 3)));
        if (!raw.isObject()) { throw new IllegalArgumentException(); }
        for (var entry : raw.properties()) { if (!FIELDS.contains(entry.getKey())) { throw new IllegalArgumentException(); } }
        if (raw.has("v") && !text(raw, "v", "2").equals("2")) { throw new IllegalArgumentException(); }
        String host = text(raw, "add", "");
        if (host.contains(":") && !host.startsWith("[")) { host = "[" + host + "]"; }
        var parts = UriSupport.parts("vmess://" + host + ":" + text(raw, "port", ""));
        var node = UriSupport.base(parts, "vmess", -1).put("uuid", text(raw, "id", ""))
                .put("alterId", Integer.parseInt(text(raw, "aid", "0"))).put("cipher", text(raw, "scy", "auto"));
        if (!text(raw, "ps", "").isEmpty()) { node.put("name", text(raw, "ps", "")); }
        var query = new LinkedHashMap<String, String>();
        query.put("type", text(raw, "net", "tcp"));
        var tls = text(raw, "tls", "");
        query.put("security", tls.isEmpty() ? "none" : tls);
        var header = text(raw, "type", "none");
        if (!header.equals("none") && !header.isEmpty()) { query.put("headerType", header); }
        for (String key : Set.of("host", "sni", "alpn", "fp", "allowInsecure")) {
            if (raw.has(key) && !text(raw, key, "").isEmpty()) { query.put(key, text(raw, key, "")); }
        }
        if (raw.has("path") && !text(raw, "path", "").isEmpty()) {
            query.put(query.get("type").equals("grpc") ? "serviceName" : "path", text(raw, "path", ""));
        }
        UriSupport.transport(node, query, false);
        return node;
    }); }

    private static String text(JsonNode node, String key, String fallback) {
        var value = node.path(key);
        if (value.isMissingNode()) { return fallback; }
        if (value.isString()) { return value.asString(); }
        if (Set.of("port", "aid", "v").contains(key) && value.isIntegralNumber()) { return value.toString(); }
        if (key.equals("allowInsecure") && value.isBoolean()) { return value.asBoolean() ? "true" : "false"; }
        throw new IllegalArgumentException();
    }
}

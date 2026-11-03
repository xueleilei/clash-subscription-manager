package dev.clashsub.parse.uri;

import dev.clashsub.common.Diagnostic;
import dev.clashsub.parse.ParsedNode;
import dev.clashsub.parse.ProtocolFieldValidator;
import java.io.ByteArrayOutputStream;
import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

final class UriSupport {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final Set<String> TRANSPORT_KEYS = Set.of("type", "security", "encryption", "sni", "peer", "fp", "pbk", "sid", "flow",
            "host", "path", "serviceName", "mode", "headerType", "alpn", "allowInsecure", "insecure", "packetEncoding", "ed", "eh");

    record Parts(String scheme, String host, String ports, String auth, Map<String, String> query, String fragment) {
        @Override public String toString() { return "UriParts[redacted]"; }
        int port(int fallback) {
            if (ports.isEmpty()) { if (fallback > 0) { return fallback; } throw new IllegalArgumentException(); }
            int port = Integer.parseInt(ports.split("[,-]")[0]);
            if (port < 1 || port > 65535) { throw new IllegalArgumentException(); }
            return port;
        }
    }

    private UriSupport() { }

    static ParsedNodeResult parse(int line, Supplier<ObjectNode> supplier) {
        try {
            var node = supplier.get();
            var report = new ProtocolFieldValidator().validate(node, "$.lines[" + (line - 1) + "]");
            if (!report.valid()) {
                return new ParsedNodeResult(null, report.errors().stream().map(d ->
                        new Diagnostic(d.severity(), d.code(), d.path(), line, null, d.message())).toList());
            }
            var connection = node.deepCopy();
            connection.remove(List.of("name", "type", "dialer-proxy"));
            return new ParsedNodeResult(new ParsedNode(node.path("name").asString(), node.path("type").asString(), connection, null), List.of());
        } catch (RuntimeException exception) {
            return new ParsedNodeResult(null, List.of(new Diagnostic(Diagnostic.Severity.ERROR, "INVALID_URI",
                    "$.lines[" + (line - 1) + "]", line, null, "链接语法、认证、编码或参数不受支持；请按协议矩阵检查")));
        }
    }

    static Parts parts(String text) {
        var uri = URI.create(text);
        if (uri.getRawAuthority() == null || (uri.getRawPath() != null && !Set.of("", "/").contains(uri.getRawPath()))) {
            throw new IllegalArgumentException();
        }
        var authority = uri.getRawAuthority();
        int separator = authority.lastIndexOf('@');
        String auth = separator < 0 ? "" : decode(authority.substring(0, separator));
        var endpoint = authority.substring(separator + 1);
        String host, ports;
        if (endpoint.startsWith("[")) {
            int end = endpoint.indexOf(']');
            if (end < 0 || end + 1 < endpoint.length() && endpoint.charAt(end + 1) != ':') { throw new IllegalArgumentException(); }
            host = endpoint.substring(1, end);
            if (!host.contains(":") || host.contains("%")) { throw new IllegalArgumentException(); }
            try { InetAddress.getByName(host); } catch (Exception exception) { throw new IllegalArgumentException(); }
            ports = end + 1 == endpoint.length() ? "" : endpoint.substring(end + 2);
        } else {
            int colon = endpoint.lastIndexOf(':');
            host = colon < 0 ? endpoint : endpoint.substring(0, colon);
            if (host.contains(":")) { throw new IllegalArgumentException(); }
            host = IDN.toASCII(decode(host), IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
            ports = colon < 0 ? "" : endpoint.substring(colon + 1);
        }
        if (host.isEmpty() || (!ports.isEmpty() && !ports.matches("[0-9]+(?:-[0-9]+)?(?:,[0-9]+(?:-[0-9]+)?)*"))) { throw new IllegalArgumentException(); }
        return new Parts(uri.getScheme().toLowerCase(Locale.ROOT), host, ports, auth,
                query(uri.getRawQuery()), uri.getRawFragment() == null ? "" : decode(uri.getRawFragment()));
    }

    static ObjectNode base(Parts parts, String protocol, int defaultPort) {
        if (!protocol.equals("hysteria2") && (parts.ports().contains(",") || parts.ports().contains("-"))) { throw new IllegalArgumentException(); }
        var node = JSON.createObjectNode().put("type", protocol).put("server", parts.host()).put("port", parts.port(defaultPort));
        String canonicalHost = parts.host();
        if (canonicalHost.contains(":")) {
            try { canonicalHost = InetAddress.getByName(canonicalHost).getHostAddress().toLowerCase(Locale.ROOT); }
            catch (java.net.UnknownHostException exception) { throw new IllegalArgumentException(); }
        }
        String endpoint = canonicalHost.contains(":") ? "[" + canonicalHost + "]" : canonicalHost;
        node.put("name", parts.fragment().isEmpty() ? protocol + " " + endpoint + ":" + parts.port(defaultPort) : parts.fragment());
        return node;
    }

    static Map<String, String> query(String raw) {
        var result = new LinkedHashMap<String, String>();
        if (raw == null || raw.isEmpty()) { return result; }
        for (String pair : raw.split("&")) {
            if (pair.isEmpty()) { continue; }
            var pieces = pair.split("=", 2);
            String key = decode(pieces[0]), value = pieces.length == 1 ? "" : decode(pieces[1]);
            if (key.isEmpty() || result.putIfAbsent(key, value) != null) { throw new IllegalArgumentException(); }
        }
        return result;
    }

    static String decode(String encoded) {
        var bytes = new ByteArrayOutputStream();
        for (int index = 0; index < encoded.length();) {
            char c = encoded.charAt(index);
            if (c == '%') {
                if (index + 2 >= encoded.length()) { throw new IllegalArgumentException(); }
                int high = Character.digit(encoded.charAt(index + 1), 16), low = Character.digit(encoded.charAt(index + 2), 16);
                if (high < 0 || low < 0) { throw new IllegalArgumentException(); }
                bytes.write(high * 16 + low); index += 3;
            } else {
                int cp = encoded.codePointAt(index);
                bytes.writeBytes(new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8));
                index += Character.charCount(cp);
            }
        }
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes.toByteArray())).toString(); }
        catch (java.nio.charset.CharacterCodingException exception) { throw new IllegalArgumentException(); }
    }

    static void allowed(Map<String, String> query, Set<String> keys) {
        if (!keys.containsAll(query.keySet())) { throw new IllegalArgumentException(); }
    }
    static boolean bool(String value) {
        return switch (value) { case "1", "true" -> true; case "0", "false" -> false; default -> throw new IllegalArgumentException(); };
    }
    static String alias(Map<String, String> query, String... names) {
        String found = null;
        for (String name : names) {
            if (query.containsKey(name)) {
                if (found != null && !found.equals(query.get(name))) { throw new IllegalArgumentException(); }
                found = query.get(name);
            }
        }
        return found;
    }
    static void copy(ObjectNode target, Map<String, String> query, String key, String field) {
        if (query.containsKey(key)) { target.put(field, query.get(key)); }
    }
    static void strings(ObjectNode target, String field, String csv) {
        var values = target.putArray(field);
        for (String value : csv.split(",", -1)) { if (value.isEmpty()) { throw new IllegalArgumentException(); } values.add(value); }
    }

    static void transport(ObjectNode node, Map<String, String> query, boolean alwaysTls) {
        allowed(query, TRANSPORT_KEYS);
        String protocol = node.path("type").asString();
        String security = query.getOrDefault("security", alwaysTls ? "tls" : "none");
        if (!Set.of("none", "tls", "reality").contains(security) || alwaysTls && security.equals("none")) { throw new IllegalArgumentException(); }
        if (!alwaysTls) { node.put("tls", !security.equals("none")); }
        String sni = alias(query, "sni", "peer");
        if (sni != null) { node.put(protocol.equals("trojan") ? "sni" : "servername", sni); }
        copy(node, query, "fp", "client-fingerprint");
        String insecure = alias(query, "allowInsecure", "insecure");
        if (insecure != null) { node.put("skip-cert-verify", bool(insecure)); }
        if (query.containsKey("alpn")) { strings(node, "alpn", query.get("alpn")); }
        copy(node, query, "packetEncoding", "packet-encoding");
        if (query.containsKey("encryption")) {
            if (!protocol.equals("vless") || !query.get("encryption").equals("none")) { throw new IllegalArgumentException(); }
            node.put("encryption", "none");
        }
        if (query.containsKey("flow")) {
            if (!protocol.equals("vless")) { throw new IllegalArgumentException(); }
            node.put("flow", query.get("flow"));
        }
        if (security.equals("reality")) {
            String publicKey = query.get("pbk");
            if (publicKey == null) { throw new IllegalArgumentException(); }
            node.putObject("reality-opts").put("public-key", publicKey).put("short-id", query.getOrDefault("sid", ""));
        } else if (query.containsKey("pbk") || query.containsKey("sid")) { throw new IllegalArgumentException(); }
        String network = query.getOrDefault("type", "tcp");
        if (!Set.of("tcp", "ws", "grpc", "http", "h2").contains(network)) { throw new IllegalArgumentException(); }
        if (query.containsKey("headerType")) {
            if (!network.equals("tcp") || !Set.of("none", "http").contains(query.get("headerType"))) { throw new IllegalArgumentException(); }
            if (query.get("headerType").equals("http")) { network = "http"; }
        }
        node.put("network", network);
        switch (network) {
            case "ws" -> {
                var options = node.putObject("ws-opts").put("path", query.getOrDefault("path", "/"));
                if (query.containsKey("host")) { options.putObject("headers").put("Host", query.get("host")); }
                if (query.containsKey("ed")) { options.put("max-early-data", Integer.parseInt(query.get("ed"))); }
                copy(options, query, "eh", "early-data-header-name");
                absent(query, "serviceName", "mode");
            }
            case "grpc" -> {
                if (query.containsKey("mode") && !query.get("mode").equals("gun")) { throw new IllegalArgumentException(); }
                node.putObject("grpc-opts").put("grpc-service-name", query.getOrDefault("serviceName", ""));
                absent(query, "path", "host", "ed", "eh");
            }
            case "http" -> {
                var options = node.putObject("http-opts");
                strings(options, "path", query.getOrDefault("path", "/"));
                if (query.containsKey("host")) { strings(options.putObject("headers"), "Host", query.get("host")); }
                absent(query, "serviceName", "mode", "ed", "eh");
            }
            case "h2" -> {
                var options = node.putObject("h2-opts").put("path", query.getOrDefault("path", "/"));
                if (query.containsKey("host")) { strings(options, "host", query.get("host")); }
                absent(query, "serviceName", "mode", "ed", "eh");
            }
            default -> absent(query, "path", "host", "serviceName", "mode", "ed", "eh");
        }
    }

    static void absent(Map<String, String> query, String... keys) {
        for (String key : keys) { if (query.containsKey(key) && !query.get(key).isEmpty()) { throw new IllegalArgumentException(); } }
    }

    static List<String> splitPlugin(String text) {
        var pieces = new ArrayList<String>(); var current = new StringBuilder(); boolean escaped = false;
        for (char c : text.toCharArray()) {
            if (escaped) { current.append(c); escaped = false; }
            else if (c == '\\') { escaped = true; }
            else if (c == ';') { pieces.add(current.toString()); current.setLength(0); }
            else { current.append(c); }
        }
        if (escaped) { throw new IllegalArgumentException(); }
        pieces.add(current.toString()); return pieces;
    }
}

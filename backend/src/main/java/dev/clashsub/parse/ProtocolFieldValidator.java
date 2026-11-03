package dev.clashsub.parse;

import dev.clashsub.common.Diagnostic;
import dev.clashsub.common.ValidationReport;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

@Component
public class ProtocolFieldValidator {
    private enum Kind { STRING, BOOLEAN, INTEGER, STRINGS, STRING_MAP, STRINGS_MAP, OBJECT }
    private static final Map<String, Kind> COMMON = schema(
            "s:name,type,server,interface-name,ip-version,dialer-proxy", "i:port,routing-mark", "b:tfo,mptcp");
    private static final Map<String, Map<String, Kind>> PROTOCOLS = Map.of(
            "ss", schema("s:cipher,password,plugin,client-fingerprint", "b:udp,udp-over-tcp", "i:udp-over-tcp-version", "o:plugin-opts"),
            "ssr", schema("s:cipher,password,obfs,obfs-param,protocol,protocol-param", "b:udp"),
            "vmess", schema("s:uuid,cipher,network,packet-encoding,servername,fingerprint,client-fingerprint,name-cert-verify",
                    "i:alterId", "b:udp,tls,skip-cert-verify,packet-addr,xudp,global-padding,authenticated-length",
                    "l:alpn", "o:ws-opts,http-opts,h2-opts,grpc-opts,reality-opts"),
            "vless", schema("s:uuid,flow,encryption,network,packet-encoding,servername,fingerprint,client-fingerprint,name-cert-verify",
                    "b:udp,tls,skip-cert-verify,packet-addr,xudp", "l:alpn", "o:ws-opts,http-opts,h2-opts,grpc-opts,reality-opts"),
            "trojan", schema("s:password,network,sni,fingerprint,client-fingerprint,name-cert-verify",
                    "b:udp,skip-cert-verify", "l:alpn", "o:ws-opts,grpc-opts,reality-opts,ss-opts"),
            "hysteria2", schema("s:password,ports,hop-interval,up,down,obfs,obfs-password,sni,fingerprint,name-cert-verify,bbr-profile",
                    "b:skip-cert-verify", "l:alpn", "i:obfs-min-packet-size,obfs-max-packet-size,cwnd,udp-mtu,handshake-timeout,initial-stream-receive-window,max-stream-receive-window,initial-connection-receive-window,max-connection-receive-window"),
            "tuic", schema("s:uuid,password,token,ip,udp-relay-mode,congestion-controller,bbr-profile,sni,fingerprint,name-cert-verify",
                    "b:reduce-rtt,disable-sni,fast-open,skip-cert-verify,disable-mtu-discovery,udp-over-stream", "l:alpn",
                    "i:heartbeat-interval,request-timeout,max-udp-relay-packet-size,max-open-streams,cwnd,recv-window-conn,recv-window,max-datagram-frame-size,udp-over-stream-version"),
            "http", schema("s:username,password,sni,fingerprint,name-cert-verify", "b:tls,skip-cert-verify", "m:headers"),
            "socks5", schema("s:username,password,fingerprint,name-cert-verify", "b:tls,udp,skip-cert-verify"));
    private static final Map<String, Map<String, Kind>> NESTED = Map.of(
            "ws-opts", schema("s:path,early-data-header-name", "m:headers", "i:max-early-data", "b:v2ray-http-upgrade,v2ray-http-upgrade-fast-open"),
            "grpc-opts", schema("s:grpc-service-name,grpc-user-agent", "i:ping-interval,max-connections,min-streams,max-streams"),
            "http-opts", schema("s:method", "l:path", "a:headers"),
            "h2-opts", schema("s:path", "l:host"),
            "reality-opts", schema("s:public-key,short-id"),
            "ss-opts", schema("b:enabled", "s:method,password"));
    private static final Set<String> SS_CIPHERS = Set.of("aes-128-gcm", "aes-256-gcm", "chacha20-ietf-poly1305", "xchacha20-ietf-poly1305",
            "2022-blake3-aes-128-gcm", "2022-blake3-aes-256-gcm", "2022-blake3-chacha20-poly1305",
            "aes-128-cfb", "aes-192-cfb", "aes-256-cfb", "aes-128-ctr", "aes-192-ctr", "aes-256-ctr", "chacha20-ietf", "rc4-md5", "none");
    private static final Set<String> SSR_CIPHERS = Set.of("aes-128-cfb", "aes-192-cfb", "aes-256-cfb", "aes-128-ctr", "aes-192-ctr", "aes-256-ctr", "rc4-md5", "chacha20", "chacha20-ietf", "none");

    public ValidationReport validate(ObjectNode node, String path) {
        var errors = new ArrayList<Diagnostic>();
        String protocol = node.path("type").asString("");
        var allowed = new HashMap<>(COMMON);
        if (!PROTOCOLS.containsKey(protocol)) {
            return new ValidationReport(List.of(Diagnostic.error("UNSUPPORTED_PROTOCOL", path + ".type", "节点协议不在第一版支持矩阵中")), List.of());
        }
        allowed.putAll(PROTOCOLS.get(protocol));
        checkObject(node, allowed, path, errors);
        required(node, "name", path, errors);
        required(node, "server", path, errors);
        if (node.path("name").asString("").getBytes(StandardCharsets.UTF_8).length > 1024) { bad(errors, path + ".name", "节点名超过 1024 字节"); }
        if (!node.path("server").asString("").matches("[^\\s/@?#]+")) { bad(errors, path + ".server", "服务器必须是主机名或 IP 地址"); }
        if (!protocol.equals("hysteria2") || !node.has("ports")) { port(node.path("port"), path + ".port", errors); }
        else if (node.has("port")) { port(node.path("port"), path + ".port", errors); }
        if (node.has("ports") && !validPorts(node.path("ports").asString(""))) { bad(errors, path + ".ports", "端口范围格式不正确"); }
        if (Set.of("ss", "ssr", "trojan", "hysteria2").contains(protocol)) { required(node, "password", path, errors); }
        if (Set.of("vmess", "vless").contains(protocol)) { uuid(node, path, errors); }
        if (protocol.equals("tuic")) {
            if (node.has("token")) {
                required(node, "token", path, errors);
                if (node.has("uuid") || node.has("password")) { bad(errors, path, "TUIC token 与 UUID/password 不能同时设置"); }
            } else { uuid(node, path, errors); required(node, "password", path, errors); }
        }
        if (protocol.equals("ss") || protocol.equals("ssr")) {
            if (!(protocol.equals("ss") ? SS_CIPHERS : SSR_CIPHERS).contains(node.path("cipher").asString(""))) {
                bad(errors, path + ".cipher", "不支持的加密方式");
            }
        }
        if (protocol.equals("ss") && node.path("cipher").asString("").startsWith("2022-")) {
            int length = node.path("cipher").asString("").contains("aes-128") ? 16 : 32;
            for (String part : node.path("password").asString("").split(":", -1)) {
                try { if (Base64.getDecoder().decode(part).length != length) { bad(errors, path + ".password", "SS 2022 密钥长度不正确"); } }
                catch (IllegalArgumentException exception) { bad(errors, path + ".password", "SS 2022 密钥编码不正确"); }
            }
        }
        if (protocol.equals("ssr")) {
            enumValue(node, "protocol", Set.of("origin", "auth_sha1_v4", "auth_aes128_md5", "auth_aes128_sha1", "auth_chain_a", "auth_chain_b"), path, errors, true);
            enumValue(node, "obfs", Set.of("plain", "http_simple", "http_post", "random_head", "tls1.2_ticket_auth", "tls1.2_ticket_fastauth"), path, errors, true);
        }
        if (protocol.equals("vmess")) {
            enumValue(node, "cipher", Set.of("auto", "none", "zero", "aes-128-gcm", "chacha20-poly1305"), path, errors, true);
        }
        enumValue(node, "ip-version", Set.of("dual", "ipv4", "ipv6", "ipv4-prefer", "ipv6-prefer"), path, errors, false);
        enumValue(node, "network", protocol.equals("trojan") ? Set.of("tcp", "ws", "grpc") : Set.of("tcp", "ws", "grpc", "http", "h2"), path, errors, false);
        enumValue(node, "packet-encoding", Set.of("xudp", "packetaddr"), path, errors, false);
        enumValue(node, "flow", Set.of("", "xtls-rprx-vision"), path, errors, false);
        if (node.path("flow").asString("").equals("xtls-rprx-vision") && !node.path("tls").asBoolean(false)) {
            bad(errors, path + ".flow", "Vision 要求启用 TLS");
        }
        enumValue(node, "encryption", Set.of("none", ""), path, errors, false);
        if (protocol.equals("hysteria2")) {
            enumValue(node, "obfs", Set.of("salamander", ""), path, errors, false);
            if (node.path("obfs").asString("").equals("salamander")) { required(node, "obfs-password", path, errors); }
        }
        enumValue(node, "congestion-controller", Set.of("cubic", "new_reno", "bbr"), path, errors, false);
        enumValue(node, "udp-relay-mode", Set.of("native", "quic"), path, errors, false);
        for (var nested : NESTED.entrySet()) {
            if (node.has(nested.getKey())) { checkObject(node.path(nested.getKey()), nested.getValue(), path + "." + nested.getKey(), errors); }
        }
        if (node.has("reality-opts")) {
            var reality = node.path("reality-opts");
            if (!protocol.equals("trojan") && !node.path("tls").asBoolean(false)) { bad(errors, path + ".tls", "Reality 要求启用 TLS"); }
            try {
                if (Base64.getUrlDecoder().decode(reality.path("public-key").asString("")).length != 32) { bad(errors, path + ".reality-opts.public-key", "Reality 公钥长度不正确"); }
            } catch (IllegalArgumentException exception) { bad(errors, path + ".reality-opts.public-key", "Reality 公钥编码不正确"); }
            if (!reality.path("short-id").asString("").matches("(?:[0-9a-fA-F]{2}){0,8}")) { bad(errors, path + ".reality-opts.short-id", "Reality short-id 必须是最多 8 字节的十六进制"); }
        }
        if (node.has("plugin")) {
            String plugin = node.path("plugin").asString("");
            if (!Set.of("obfs", "v2ray-plugin").contains(plugin)) { bad(errors, path + ".plugin", "第一版仅支持 obfs 和 v2ray-plugin"); }
            else {
                var schema = plugin.equals("obfs") ? schema("s:mode,host") : schema("s:mode,host,path", "b:tls,skip-cert-verify,mux", "m:headers");
                checkObject(node.path("plugin-opts"), schema, path + ".plugin-opts", errors);
                enumValue(node.path("plugin-opts"), "mode", plugin.equals("obfs") ? Set.of("http", "tls") : Set.of("websocket", "quic"),
                        path + ".plugin-opts", errors, true);
            }
        } else if (node.has("plugin-opts")) { bad(errors, path + ".plugin", "plugin-opts 需要 plugin"); }
        return new ValidationReport(errors, List.of());
    }

    private void checkObject(JsonNode node, Map<String, Kind> fields, String path, List<Diagnostic> errors) {
        if (!node.isObject()) { bad(errors, path, "此字段必须是对象"); return; }
        for (var field : node.properties()) {
            Kind kind = fields.get(field.getKey());
            if (kind == null) { errors.add(Diagnostic.error("UNKNOWN_PROXY_FIELD", path + "." + field.getKey(), "不支持此连接字段，已拒绝整个订阅")); }
            else if (!matches(field.getValue(), kind)) { bad(errors, path + "." + field.getKey(), "字段类型不正确"); }
        }
    }

    private boolean matches(JsonNode value, Kind kind) {
        return switch (kind) {
            case STRING -> value.isString();
            case BOOLEAN -> value.isBoolean();
            case INTEGER -> value.isIntegralNumber() && value.canConvertToLong() && value.asLong() >= 0;
            case STRINGS -> value.isArray() && all(value, JsonNode::isString);
            case STRING_MAP -> value.isObject() && all(value, JsonNode::isString);
            case STRINGS_MAP -> value.isObject() && all(value, element -> element.isArray() && all(element, JsonNode::isString));
            case OBJECT -> value.isObject();
        };
    }
    private boolean all(JsonNode node, Predicate<JsonNode> predicate) { for (var item : node) { if (!predicate.test(item)) { return false; } } return true; }
    private void required(JsonNode node, String key, String path, List<Diagnostic> errors) {
        if (!node.path(key).isString() || node.path(key).asString("").isBlank()) { bad(errors, path + "." + key, "缺少必需的非空文本字段"); }
    }
    private void uuid(JsonNode node, String path, List<Diagnostic> errors) {
        String value = node.path("uuid").asString("");
        try { if (!UUID.fromString(value).toString().equalsIgnoreCase(value)) { bad(errors, path + ".uuid", "UUID 格式不正确"); } }
        catch (IllegalArgumentException exception) { bad(errors, path + ".uuid", "UUID 格式不正确"); }
    }
    private void port(JsonNode value, String path, List<Diagnostic> errors) {
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.asInt() < 1 || value.asInt() > 65535) { bad(errors, path, "端口必须是 1–65535 的整数"); }
    }
    private boolean validPorts(String value) {
        if (!value.matches("[0-9]+(?:-[0-9]+)?(?:,[0-9]+(?:-[0-9]+)?)*")) { return false; }
        try {
            for (String piece : value.split(",")) {
                var ends = piece.split("-");
                int start = Integer.parseInt(ends[0]), end = Integer.parseInt(ends[ends.length - 1]);
                if (start < 1 || end > 65535 || start > end) { return false; }
            }
            return true;
        } catch (NumberFormatException exception) { return false; }
    }
    private void enumValue(JsonNode node, String field, Set<String> values, String path, List<Diagnostic> errors, boolean required) {
        if ((required || node.has(field)) && !values.contains(node.path(field).asString(""))) { bad(errors, path + "." + field, "不支持的字段选项"); }
    }
    private void bad(List<Diagnostic> errors, String path, String message) { errors.add(Diagnostic.error("INVALID_NODE_FIELD", path, message)); }
    private static Map<String, Kind> schema(String... groups) {
        var result = new HashMap<String, Kind>();
        for (String group : groups) {
            Kind kind = switch (group.charAt(0)) {
                case 's' -> Kind.STRING; case 'b' -> Kind.BOOLEAN; case 'i' -> Kind.INTEGER; case 'l' -> Kind.STRINGS;
                case 'm' -> Kind.STRING_MAP; case 'a' -> Kind.STRINGS_MAP; case 'o' -> Kind.OBJECT; default -> throw new IllegalArgumentException();
            };
            for (String name : group.substring(2).split(",")) { result.put(name, kind); }
        }
        return Map.copyOf(result);
    }
}

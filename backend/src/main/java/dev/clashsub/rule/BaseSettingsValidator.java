package dev.clashsub.rule;

import dev.clashsub.common.Diagnostic;
import dev.clashsub.common.ValidationReport;
import dev.clashsub.parse.SafeYaml;
import dev.clashsub.parse.YamlInputException;
import dev.clashsub.profile.ReferenceIndex;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class BaseSettingsValidator {
    private static final Set<String> MANAGED = Set.of("proxies", "proxy-groups", "rules", "rule-providers", "proxy-providers");
    private static final Set<String> PORTS = Set.of("port", "socks-port", "mixed-port", "redir-port", "tproxy-port");
    private static final Set<String> BOOLEANS = Set.of("allow-lan", "ipv6", "unified-delay", "tcp-concurrent", "inbound-tfo", "inbound-mptcp", "geodata-mode", "geo-auto-update", "etag-support", "disable-keep-alive");
    private static final Set<String> NUMBERS = Set.of("routing-mark", "external-controller-routing-mark", "geo-update-interval", "keep-alive-idle", "keep-alive-interval");
    private static final Set<String> STRINGS = Set.of("mode", "log-level", "bind-address", "external-controller", "secret", "interface-name", "geodata-loader", "geosite-matcher", "find-process-mode", "global-client-fingerprint", "global-ua", "external-doh-server");
    private static final Set<String> OBJECTS = Set.of("dns", "hosts", "profile", "tun", "sniffer", "ntp", "experimental", "geox-url", "external-controller-cors", "iptables");
    private static final Set<String> ARRAYS = Set.of("authentication", "skip-auth-prefixes", "lan-allowed-ips", "lan-disallowed-ips");
    private static final Set<String> UNSAFE = Set.of("listeners", "tunnels", "tls", "script", "tuic-server", "external-ui", "external-ui-url", "external-ui-name", "external-controller-unix", "external-controller-pipe", "external-controller-tls", "ss-config", "vmess-config");
    private static final Set<String> NESTED_FILES = Set.of("certificate", "private-key", "ca", "ca-str", "client-cert", "client-key", "client-auth-cert", "custom-certificates", "custom-certifactes", "certificate-path", "private-key-path", "ech-key", "path-in-bundle", "file-descriptor");
    private static final Set<String> DNS_BOOL = Set.of("enable", "ipv6", "use-hosts", "use-system-hosts", "respect-rules", "prefer-h3", "direct-nameserver-follow-policy", "fallback-lazy-query");
    private static final Set<String> DNS_STRING = Set.of("listen", "enhanced-mode", "fake-ip-range", "fake-ip-range6", "fake-ip-filter-mode", "cache-algorithm");
    private static final Set<String> DNS_LIST = Set.of("default-nameserver", "nameserver", "fallback", "proxy-server-nameserver", "direct-nameserver", "fake-ip-filter");
    private static final Set<String> DNS_OBJECT = Set.of("nameserver-policy", "proxy-server-nameserver-policy", "fallback-filter");
    private final SafeYaml yaml;
    public BaseSettingsValidator(SafeYaml yaml) { this.yaml = yaml; }

    public ValidationReport validate(String text, ReferenceIndex references) {
        final JsonNode root;
        try { root = yaml.load(text).value(); }
        catch (YamlInputException exception) { return ValidationReport.of(List.of(exception.diagnostic())); }
        var issues = new ArrayList<Diagnostic>();
        for (var entry : root.properties()) {
            String key = entry.getKey(), path = "baseYaml." + key; var value = entry.getValue();
            if (MANAGED.contains(key)) { issues.add(Diagnostic.error("MANAGED_FIELD", path, "此字段由管理器的节点、代理组或规则编辑器维护")); }
            else if (key.equals("sub-rules")) { issues.add(reference(path)); }
            else if (UNSAFE.contains(key)) { issues.add(Diagnostic.error("UNSUPPORTED_BASE_SETTING", path, "此设置尚不支持安全的离线校验")); }
            else if (PORTS.contains(key)) { if (!value.isIntegralNumber() || value.asLong(-1) < 0 || value.asLong(Long.MAX_VALUE) > 65_535) { issues.add(invalid(path)); } }
            else if (BOOLEANS.contains(key)) { if (!value.isBoolean()) { issues.add(invalid(path)); } }
            else if (NUMBERS.contains(key)) { if (!value.isIntegralNumber() || value.asLong(-1) < 0 || value.asLong(Long.MAX_VALUE) > Integer.MAX_VALUE) { issues.add(invalid(path)); } }
            else if (STRINGS.contains(key)) { if (!value.isString()) { issues.add(invalid(path)); } }
            else if (OBJECTS.contains(key)) { if (!value.isObject()) { issues.add(invalid(path)); } }
            else if (ARRAYS.contains(key)) { if (!strings(value)) { issues.add(invalid(path)); } }
            else { issues.add(Diagnostic.error("UNKNOWN_BASE_SETTING", path, "固定内核版本的设置白名单中未包含此字段，请检查拼写或迁移高级设置")); }
        }
        if (root.has("mode") && !Set.of("rule", "global", "direct").contains(root.path("mode").asString(""))) { issues.add(invalid("baseYaml.mode")); }
        if (root.has("log-level") && !Set.of("silent", "error", "warning", "info", "debug").contains(root.path("log-level").asString(""))) { issues.add(invalid("baseYaml.log-level")); }
        var dns = root.path("dns");
        if (dns.isObject()) { dns(dns, issues); }
        inspect(root, "baseYaml", issues);
        return ValidationReport.of(issues);
    }
    private void dns(JsonNode dns, List<Diagnostic> issues) {
        for (var entry : dns.properties()) {
            String key = entry.getKey(), path = "baseYaml.dns." + key; var value = entry.getValue();
            if (DNS_BOOL.contains(key)) { if (!value.isBoolean()) { issues.add(invalid(path)); } }
            else if (DNS_STRING.contains(key)) { if (!value.isString()) { issues.add(invalid(path)); } }
            else if (DNS_LIST.contains(key)) { if (!strings(value)) { issues.add(invalid(path)); } }
            else if (DNS_OBJECT.contains(key)) { if (!value.isObject()) { issues.add(invalid(path)); } }
            else if (Set.of("cache-max-size", "fake-ip-ttl", "ipv6-timeout", "listen-routing-mark").contains(key)) { if (!value.isIntegralNumber() || value.asLong(-1) < 0) { issues.add(invalid(path)); } }
            else { issues.add(Diagnostic.error("UNKNOWN_BASE_SETTING", path, "此 DNS 字段尚未被当前版本识别")); }
        }
    }
    private void inspect(JsonNode node, String path, List<Diagnostic> issues) {
        if (node.isObject()) {
            for (var entry : node.properties()) {
                String key = entry.getKey(), next = path + "." + key; var value = entry.getValue();
                if (NESTED_FILES.contains(key)) { issues.add(Diagnostic.error("UNSUPPORTED_BASE_SETTING", next, "不支持引用宿主文件或证书材料")); }
                if (key.contains("rule-set:") || Set.of("route-address-set", "route-exclude-address-set", "sub-rules").contains(key)) { issues.add(reference(next)); }
                if (Set.of("proxy", "dialer-proxy").contains(key) && (!value.isString() || !Set.of("DIRECT", "REJECT").contains(value.asString("")))) { issues.add(reference(next)); }
                inspect(value, next, issues);
            }
        } else if (node.isArray()) { for (int i = 0; i < node.size(); i++) { inspect(node.path(i), path + "[" + i + "]", issues); } }
        else if (node.isString() && path.startsWith("baseYaml.dns.")) {
            String text = node.asString(); int hash = text.indexOf('#');
            if (hash >= 0) {
                for (String option : text.substring(hash + 1).split("&", -1)) {
                    if (!Set.of("DIRECT", "RULES", "h3").contains(option) && !option.matches("(?:h3|ecs|ecs-override|disable-ipv6)=[^#&]+")) { issues.add(reference(path)); break; }
                }
            }
        }
    }
    private boolean strings(JsonNode value) { if (!value.isArray()) { return false; } for (var item : value) { if (!item.isString()) { return false; } } return true; }
    private Diagnostic invalid(String path) { return Diagnostic.error("INVALID_BASE_SETTING", path, "基础设置类型或取值范围不正确"); }
    private Diagnostic reference(String path) { return Diagnostic.error("UNSUPPORTED_BASE_REFERENCE", path, "此高级文本引用尚不能随组或规则集改名安全更新，请迁移后发布"); }
}

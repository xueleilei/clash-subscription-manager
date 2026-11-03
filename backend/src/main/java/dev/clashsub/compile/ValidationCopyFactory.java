package dev.clashsub.compile;

import dev.clashsub.common.Diagnostic;
import dev.clashsub.common.ValidationReport;
import dev.clashsub.parse.SafeYaml;
import dev.clashsub.parse.YamlDocument;
import dev.clashsub.parse.YamlInputException;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

public class ValidationCopyFactory {
    private static final Set<String> BLOCKED_TOP = Set.of("proxy-providers", "listeners", "tunnels", "tuic-server",
            "tls", "script", "external-ui", "external-ui-url", "external-ui-name", "external-controller-unix",
            "external-controller-pipe", "ss-config", "vmess-config", "sub-rules");
    private static final Set<String> BLOCKED_NESTED = Set.of("certificate", "private-key", "ca", "ca-str",
            "client-cert", "client-key", "client-auth-cert", "custom-certificates", "custom-certifactes",
            "certificate-path", "private-key-path", "ech-key", "path-in-bundle", "file-descriptor");
    private static final Set<String> PROXY_TYPES = Set.of("ss", "ssr", "vmess", "vless", "trojan", "hysteria2", "tuic", "http", "socks5");
    private static final Set<String> PROVIDER_FIELDS = Set.of("type", "url", "path", "behavior", "format", "interval", "proxy", "header", "size-limit");
    private static final Map<String, String> RESOURCE_NAMES = Map.of(
            "geosite.dat", "GeoSite.dat", "geoip.dat", "GeoIP.dat",
            "country.mmdb", "Country.mmdb", "GeoLite2-ASN.mmdb", "ASN.mmdb");
    private final SafeYaml yaml;
    private final Path resources;

    public ValidationCopyFactory(SafeYaml yaml, Path resources) { this.yaml = yaml; this.resources = resources; }

    public ValidationInput create(String outputYaml, Path workDir) {
        var errors = new ArrayList<Diagnostic>();
        var changes = new ArrayList<ValidationChange>();
        final ObjectNode root;
        try { root = yaml.load(outputYaml).value(); }
        catch (YamlInputException exception) { return failed(List.of(exception.diagnostic()), changes); }
        for (String field : BLOCKED_TOP) {
            if (root.has(field)) { errors.add(unsafe("$." + field)); }
        }
        inspectNested(root, "$", errors);
        for (String field : List.of("geodata-mode", "geo-auto-update")) {
            if (root.has(field) && !root.path(field).isBoolean()) { errors.add(invalid("$." + field)); }
        }
        var proxies = root.path("proxies");
        if (!proxies.isMissingNode() && !proxies.isArray()) { errors.add(invalid("$.proxies")); }
        for (int i = 0; i < proxies.size(); i++) {
            if (!PROXY_TYPES.contains(proxies.path(i).path("type").asString(""))) { errors.add(unsafe("$.proxies[" + i + "].type")); }
        }
        var providers = root.path("rule-providers");
        if (!providers.isMissingNode() && !providers.isObject()) { errors.add(invalid("$.rule-providers")); }
        if (providers.isObject()) {
            for (var entry : providers.properties()) {
                var provider = entry.getValue();
                String path = "$.rule-providers." + entry.getKey();
                String behavior = provider.path("behavior").asString("");
                String format = provider.path("format").asString("yaml");
                boolean valid = provider.isObject() && provider.path("type").asString("").equals("http")
                        && Set.of("domain", "ipcidr", "classical").contains(behavior)
                        && Set.of("yaml", "text", "mrs").contains(format)
                        && !(format.equals("mrs") && behavior.equals("classical"))
                        && httpUrl(provider.path("url").asString(""))
                        && provider.path("interval").isIntegralNumber() && provider.path("interval").asLong(0) > 0
                        && provider.path("interval").asLong(Long.MAX_VALUE) <= Integer.MAX_VALUE
                        && providerOptionsValid(provider);
                if (provider.has("proxy") && !knownTarget(root, provider.path("proxy").asString(""))) { valid = false; }
                if (!valid) { errors.add(invalid(path)); continue; }
                var replacement = ((ObjectNode) provider).objectNode();
                replacement.put("type", "inline").put("behavior", behavior).put("format", "yaml");
                replacement.putArray("payload").add(switch (behavior) {
                    case "domain" -> "example.invalid";
                    case "ipcidr" -> "192.0.2.0/24";
                    default -> "DOMAIN,example.invalid";
                });
                ((ObjectNode) providers).set(entry.getKey(), replacement);
                changes.add(new ValidationChange(path, "远程规则集替换为相同行为的受控测试内容；远端可用性及内容未检查"));
            }
        }
        if (root.has("geox-url")) {
            var urls = root.path("geox-url");
            if (!urls.isObject()) { errors.add(invalid("$.geox-url")); }
            else { for (var entry : urls.properties()) {
                if (!Set.of("geoip", "geosite", "mmdb", "asn").contains(entry.getKey())
                        || !entry.getValue().isString() || !httpUrl(entry.getValue().asString(""))) { errors.add(invalid("$.geox-url")); }
            } }
        }
        if (!errors.isEmpty()) { return failed(errors, changes); }
        root.put("geo-auto-update", false);
        root.remove("geox-url");
        root.put("geodata-mode", true);
        changes.add(new ValidationChange("$.geodata-mode", "校验副本使用固定 GeoIP/GeoSite 数据，不执行自动更新"));
        try {
            Files.createDirectories(workDir);
            for (var entry : RESOURCE_NAMES.entrySet()) {
                Files.copy(resources.resolve(entry.getKey()), workDir.resolve(entry.getValue()), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) { return failed(List.of(Diagnostic.error("CORE_RESOURCES_MISSING", "$", "内核校验资源缺失，请运行资源准备脚本")), changes); }
        return new ValidationInput(yaml.dump(new YamlDocument(root)), List.copyOf(RESOURCE_NAMES.values()), changes, ValidationReport.ok());
    }

    private void inspectNested(JsonNode node, String path, List<Diagnostic> errors) {
        if (node.isObject()) {
            for (var entry : node.properties()) {
                String next = path + "." + entry.getKey();
                if (BLOCKED_NESTED.contains(entry.getKey())) { errors.add(unsafe(next)); }
                inspectNested(entry.getValue(), next, errors);
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) { inspectNested(node.path(i), path + "[" + i + "]", errors); }
        }
    }

    private boolean providerOptionsValid(JsonNode provider) {
        for (var entry : provider.properties()) { if (!PROVIDER_FIELDS.contains(entry.getKey())) { return false; } }
        for (String field : List.of("type", "url", "path", "behavior", "format", "proxy")) {
            if (provider.has(field) && !provider.path(field).isString()) { return false; }
        }
        if (provider.has("size-limit") && (!provider.path("size-limit").isIntegralNumber()
                || provider.path("size-limit").asLong(0) <= 0)) { return false; }
        if (provider.has("header")) {
            var headers = provider.path("header");
            if (!headers.isObject()) { return false; }
            for (var header : headers.properties()) {
                if (!header.getValue().isArray()) { return false; }
                for (var value : header.getValue()) { if (!value.isString()) { return false; } }
            }
        }
        return true;
    }

    private boolean knownTarget(ObjectNode root, String name) {
        if (name.equals("DIRECT") || name.equals("REJECT")) { return true; }
        for (var node : root.path("proxies")) { if (node.path("name").asString("").equals(name)) { return true; } }
        for (var group : root.path("proxy-groups")) { if (group.path("name").asString("").equals(name)) { return true; } }
        return false;
    }

    private boolean httpUrl(String text) {
        try {
            var uri = URI.create(text);
            return Set.of("http", "https").contains(uri.getScheme()) && uri.getHost() != null
                    && uri.getUserInfo() == null && uri.getFragment() == null;
        } catch (IllegalArgumentException | NullPointerException exception) { return false; }
    }
    private Diagnostic unsafe(String path) { return Diagnostic.error("UNSAFE_VALIDATION_FIELD", path, "此字段尚不支持安全的离线内核检查"); }
    private Diagnostic invalid(String path) { return Diagnostic.error("INVALID_VALIDATION_INPUT", path, "字段类型、规则集地址、格式或行为不正确"); }
    private ValidationInput failed(List<Diagnostic> errors, List<ValidationChange> changes) {
        return new ValidationInput("", List.of(), changes, new ValidationReport(errors, List.of()));
    }
}

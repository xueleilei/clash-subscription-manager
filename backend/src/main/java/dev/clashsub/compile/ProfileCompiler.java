package dev.clashsub.compile;

import dev.clashsub.common.Diagnostic;
import dev.clashsub.common.ValidationReport;
import dev.clashsub.node.NodeCatalog;
import dev.clashsub.parse.ProtocolFieldValidator;
import dev.clashsub.parse.SafeYaml;
import dev.clashsub.parse.YamlDocument;
import dev.clashsub.profile.ProfileDocument;
import dev.clashsub.profile.ReferenceIndex;
import dev.clashsub.profile.ReferenceValidator;
import dev.clashsub.rule.BaseSettingsValidator;
import dev.clashsub.rule.ProviderValidator;
import dev.clashsub.rule.RuleValidator;
import dev.clashsub.source.SourceState;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Pure compilation: no database reads, downloads, or child processes. */
@Component
public class ProfileCompiler {
    private static final JsonMapper JSON = new JsonMapper();
    private final SafeYaml yaml;
    private final ReferenceValidator references;
    private final BaseSettingsValidator settings;
    private final ProviderValidator providers;
    private final RuleValidator rules;
    private final DependencyResolver dependencies;
    private final OutputNameAllocator allocator;
    public ProfileCompiler(SafeYaml yaml, ReferenceValidator references, BaseSettingsValidator settings, ProviderValidator providers,
            RuleValidator rules, DependencyResolver dependencies, OutputNameAllocator allocator) {
        this.yaml = yaml; this.references = references; this.settings = settings; this.providers = providers;
        this.rules = rules; this.dependencies = dependencies; this.allocator = allocator;
    }

    public CompileResult compile(UUID profileId, ProfileDocument definition, NodeCatalog catalog, Map<UUID, SourceState> sources) {
        if (profileId == null || definition == null || definition.schemaVersion() != 1) {
            return failed(ValidationReport.of(List.of(Diagnostic.error("INVALID_SCHEMA", "$", "配置文档版本或方案 ID 不受支持"))));
        }
        var report = references.validate(definition, catalog).merge(settings.validate(definition.baseYaml(), ReferenceIndex.of(definition, catalog)))
                .merge(providers.validate(definition.providers())).merge(rules.validate(definition.rules()))
                .merge(new GroupOptionsValidator().validate(definition.groups())).merge(ValidationReport.of(definition.importIssues()));
        if (!report.valid()) { return failed(report); }
        var used = dependencies.resolve(definition, catalog); report = report.merge(used.report());
        if (!report.valid()) { return failed(report); }
        var names = allocator.allocate(definition, used, sources); report = report.merge(names.report());
        if (!report.valid()) { return failed(report); }
        var errors = new ArrayList<Diagnostic>(); var root = yaml.load(definition.baseYaml()).value();
        var proxies = root.putArray("proxies"); var versions = new LinkedHashMap<UUID, Long>();
        for (var node : used.nodes().values().stream().sorted(Comparator.comparing(value -> value.id().toString())).toList()) {
            String path = "nodes." + node.id(); var connection = node.connection();
            if (connection.has("name") || connection.has("type") || connection.has("dialer-proxy")) {
                errors.add(Diagnostic.error("MANAGED_NODE_FIELD", path, "节点名称、协议和链式引用必须使用结构化字段"));
            }
            var validation = connection.deepCopy().put("name", node.originalName()).put("type", node.protocol());
            errors.addAll(new ProtocolFieldValidator().validate(validation, path).errors());
            var exported = JSON.createObjectNode().put("name", names.nodes().get(node.id())).put("type", node.protocol());
            sorted(connection).properties().forEach(entry -> exported.set(entry.getKey(), entry.getValue()));
            if (node.dialerRef() != null) { exported.put("dialer-proxy", names.target(node.dialerRef())); }
            proxies.add(exported);
            if (node.sourceId() != null) {
                var source = sources.get(node.sourceId());
                if (source == null || source.snapshotVersion() < 0) { errors.add(Diagnostic.error("MISSING_SOURCE_VERSION", path, "节点来源的有效快照版本缺失")); }
                else { versions.put(node.sourceId(), source.snapshotVersion()); }
            }
        }
        var groups = root.putArray("proxy-groups");
        for (var group : definition.groups()) {
            var exported = JSON.createObjectNode().put("name", names.groups().get(group.id())).put("type", group.type());
            var members = exported.putArray("proxies");
            if (group.members().isEmpty()) { members.add("REJECT"); }
            else { group.members().forEach(member -> members.add(names.target(member))); }
            exported.put("empty-fallback", "REJECT");
            if (group.type().equals("url-test")) { exported.put("url", "https://www.gstatic.com/generate_204").put("interval", 300); }
            sorted(group.options()).properties().forEach(entry -> exported.set(entry.getKey(), entry.getValue()));
            groups.add(exported);
        }
        var providerOutput = root.putObject("rule-providers");
        for (var provider : definition.providers()) {
            var exported = JSON.createObjectNode().put("type", "http").put("url", provider.url()).put("behavior", provider.behavior())
                    .put("format", provider.format()).put("interval", provider.interval())
                    .put("path", "./rules/" + profileId + "/" + provider.id() + "." + provider.format());
            sorted(provider.options()).properties().forEach(entry -> exported.set(entry.getKey(), entry.getValue()));
            providerOutput.set(names.providers().get(provider.id()), exported);
        }
        var ruleOutput = root.putArray("rules");
        for (int i = 0; i < definition.rules().size(); i++) {
            var rule = definition.rules().get(i); var expression = rule.expression(); String target = names.target(rule.target());
            if (target == null || target.contains(",") || !target.equals(target.strip()) || target.chars().anyMatch(c -> c < 32 || c == 127)) {
                errors.add(Diagnostic.error("UNREPRESENTABLE_RULE_TARGET", "rules[" + i + "].target", "目标名称包含规则文本不能安全表达的字符，请修改组名称或通过代理组引用节点")); continue;
            }
            String payload = expression.providerId() == null ? expression.value() : names.providers().get(expression.providerId());
            String line = expression.type() + (expression.type().equals("MATCH") ? "" : "," + payload) + "," + target;
            if (!rule.modifiers().isEmpty()) { line += "," + String.join(",", rule.modifiers()); }
            if (line.chars().anyMatch(c -> c < 32 || c == 127 || c == 0x85 || c == 0x2028 || c == 0x2029)) {
                errors.add(Diagnostic.error("MULTILINE_RULE", "rules[" + i + "]", "规则及引用名称必须为单行文本")); continue;
            }
            ruleOutput.add(line);
        }
        report = report.merge(ValidationReport.of(errors));
        if (!report.valid()) { return failed(report); }
        String output = dev.clashsub.rule.RuleYamlComments.write(yaml.dump(new YamlDocument(root)), definition.rules());
        if (output.getBytes(StandardCharsets.UTF_8).length > SafeYaml.MAX_BYTES) {
            return failed(report.merge(ValidationReport.of(List.of(Diagnostic.error("OUTPUT_LIMIT", "$", "编译输出超过 10 MiB")))));
        }
        return new CompileResult(new CompiledProfile(output, digest(output), used.nodes().keySet(), report.warnings(), versions), report);
    }
    private JsonNode sorted(JsonNode node) {
        if (node.isObject()) {
            var result = JSON.createObjectNode(); node.properties().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> result.set(entry.getKey(), sorted(entry.getValue()))); return result;
        }
        if (node.isArray()) { var result = JSON.createArrayNode(); node.forEach(value -> result.add(sorted(value))); return result; }
        return node.deepCopy();
    }
    private String digest(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 unavailable", exception); }
    }
    private CompileResult failed(ValidationReport report) { return new CompileResult(null, report); }
}

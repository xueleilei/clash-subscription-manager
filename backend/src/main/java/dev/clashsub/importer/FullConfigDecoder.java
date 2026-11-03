package dev.clashsub.importer;

import dev.clashsub.common.*;
import dev.clashsub.compile.DependencyResolver;
import dev.clashsub.compile.GroupOptionsValidator;
import dev.clashsub.node.*;
import dev.clashsub.parse.*;
import dev.clashsub.profile.*;
import dev.clashsub.rule.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
public class FullConfigDecoder {
    public record Decoded(ProfileDocument document, NodeCatalog nodes, ValidationReport report) { }
    private final SafeYaml yaml;
    private final RuleParser ruleParser;
    private final ReferenceValidator references;
    private final BaseSettingsValidator settings;
    private final ProviderValidator providers;
    private final RuleValidator rules;
    private final DependencyResolver dependencies;
    private final JsonMapper json;
    public FullConfigDecoder(SafeYaml yaml, RuleParser ruleParser, ReferenceValidator references, BaseSettingsValidator settings,
            ProviderValidator providers, RuleValidator rules, DependencyResolver dependencies, JsonMapper json) {
        this.yaml = yaml; this.ruleParser = ruleParser; this.references = references; this.settings = settings;
        this.providers = providers; this.rules = rules; this.dependencies = dependencies; this.json = json;
    }
    public Decoded decode(String text) {
        final ObjectNode root;
        try { root = yaml.load(text).value(); } catch (YamlInputException exception) { throw new ApiValidationException(ValidationReport.of(List.of(exception.diagnostic()))); }
        var nodeRows = array(root, "proxies", 20_000); var groupRows = array(root, "proxy-groups", 1000); var ruleRows = array(root, "rules", 10_000);
        var nodes = new LinkedHashMap<UUID, NodeSpec>(); var nodeDiagnostics = new ArrayList<Diagnostic>(); var groupIds = new ArrayList<UUID>(); var groups = new ArrayList<GroupDefinition>();
        for (int i = 0; i < nodeRows.size(); i++) {
            var row = object(nodeRows.get(i), "proxies[" + i + "]"); String name = name(row, "name", 1024), type = name(row, "type", 32);
            nodeDiagnostics.addAll(new ProtocolFieldValidator().validate(row, "proxies[" + i + "]").errors());
            var connection = row.deepCopy(); connection.remove(List.of("name", "type", "dialer-proxy"));
            if (row.has("dialer-proxy") && !row.path("dialer-proxy").isString()) { throw ApiException.invalid("链式代理引用必须是文本名称"); }
            UUID id = UUID.randomUUID(); nodes.put(id, new NodeSpec(id, null, name, type, connection, row.has("dialer-proxy") ? TargetRef.unresolved(row.path("dialer-proxy").asString()) : null));
        }
        for (int i = 0; i < groupRows.size(); i++) {
            var row = object(groupRows.get(i), "proxy-groups[" + i + "]"); UUID id = UUID.randomUUID(); groupIds.add(id);
            var options = row.deepCopy(); options.remove(List.of("name", "type", "proxies"));
            groups.add(new GroupDefinition(id, name(row, "name", 1024), name(row, "type", 32), List.of(), options));
        }
        var providerRows = root.has("rule-providers") ? object(root.path("rule-providers"), "rule-providers") : json.createObjectNode();
        if (providerRows.size() > 1000) { throw ApiException.invalid("规则集超过 1000 个"); }
        var providerDefinitions = new ArrayList<ProviderDefinition>();
        for (var entry : providerRows.properties()) {
            var row = object(entry.getValue(), "rule-providers"); var options = row.deepCopy();
            options.remove(List.of("type", "url", "behavior", "format", "interval"));
            String type = text(row, "type", "http");
            if (type.equals("http")) { options.remove("path"); } // The manager owns the client cache path; original stays in the batch.
            long interval = row.has("interval") ? integer(row.path("interval")) : 3600;
            providerDefinitions.add(new ProviderDefinition(UUID.randomUUID(), entry.getKey(), type, text(row, "url", ""), text(row, "behavior", ""), text(row, "format", "yaml"), interval, options));
        }
        var skeleton = new ProfileDocument(1, "mode: rule\n", groups, List.of(), providerDefinitions, List.of());
        var index = ReferenceIndex.of(skeleton, new NodeCatalog(nodes));
        for (int i = 0; i < groups.size(); i++) {
            var members = new ArrayList<TargetRef>();
            for (var member : array(object(groupRows.get(i), "proxy-groups"), "proxies", 20_000)) {
                if (!member.isString()) { throw ApiException.invalid("代理组成员必须是文本名称"); } members.add(index.resolve(member.asString()));
            }
            var group = groups.get(i); groups.set(i, new GroupDefinition(group.id(), group.name(), group.type(), members, group.options()));
        }
        nodes.replaceAll((id, node) -> new NodeSpec(id, null, node.originalName(), node.protocol(), node.connection(),
                node.dialerRef() == null ? null : index.resolve(node.dialerRef().unresolvedName())));
        var ruleLines = new ArrayList<String>();
        for (var row : ruleRows) { if (!row.isString() || row.asString().isBlank() || row.asString().matches("(?s).*\\R.*")) { throw ApiException.invalid("规则必须为非空单行文本"); } ruleLines.add(row.asString()); }
        var parsedRules = ruleParser.parseValues(ruleLines, index);
        var commentedRules = RuleYamlComments.read(text, parsedRules.rules());
        var base = root.deepCopy(); base.remove(List.of("proxies", "proxy-groups", "rule-providers", "rules"));
        var document = new ProfileDocument(1, yaml.dump(new YamlDocument(base)), groups, commentedRules, providerDefinitions, List.of());
        var catalog = new NodeCatalog(nodes);
        var report = ValidationReport.of(nodeDiagnostics).merge(parsedRules.report()).merge(references.validate(document, catalog))
                .merge(settings.validate(document.baseYaml(), index)).merge(providers.validate(providerDefinitions)).merge(rules.validate(document.rules()))
                .merge(new GroupOptionsValidator().validate(groups)).merge(dependencies.resolve(document, catalog).report());
        var issues = new ArrayList<>(new LinkedHashSet<>(report.errors()));
        document = new ProfileDocument(1, document.baseYaml(), groups, document.rules(), providerDefinitions, issues);
        return new Decoded(document, catalog, report);
    }
    private List<JsonNode> array(ObjectNode parent, String field, int max) {
        if (!parent.has(field)) { return List.of(); }
        var node = parent.path(field); if (!node.isArray() || node.size() > max) { throw ApiException.invalid("配置数组格式或数量超出限制：" + field); }
        var result = new ArrayList<JsonNode>(); node.forEach(result::add); return result;
    }
    private ObjectNode object(JsonNode value, String path) { if (!value.isObject()) { throw ApiException.invalid("配置字段必须是对象：" + path); } return (ObjectNode) value; }
    private String name(ObjectNode value, String field, int maxBytes) {
        String text = text(value, field, ""); if (text.isBlank() || text.getBytes(StandardCharsets.UTF_8).length > maxBytes) { throw ApiException.invalid("名称或类型为空或超过大小限制"); } return text;
    }
    private String text(ObjectNode node, String field, String defaultValue) { if (!node.has(field)) { return defaultValue; } if (!node.path(field).isString()) { throw ApiException.invalid("配置字段类型不正确：" + field); } return node.path(field).asString(); }
    private long integer(JsonNode node) { if (!node.isIntegralNumber() || !node.canConvertToLong()) { throw ApiException.invalid("规则集刷新周期必须是整数"); } return node.asLong(); }
}

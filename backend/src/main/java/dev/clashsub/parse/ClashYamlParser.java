package dev.clashsub.parse;

import dev.clashsub.common.Diagnostic;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ObjectNode;

@Component
public class ClashYamlParser {
    public static final int MAX_NODES = 20_000;
    private final SafeYaml yaml;
    private final ProtocolFieldValidator fields;
    public ClashYamlParser(SafeYaml yaml, ProtocolFieldValidator fields) { this.yaml = yaml; this.fields = fields; }

    public ParseResult parse(String text) {
        final ObjectNode root;
        try { root = yaml.load(text).value(); }
        catch (YamlInputException exception) { return ParseResult.failure(exception.diagnostic()); }
        if (root.has("proxy-providers") && (!root.has("proxies") || root.path("proxy-providers").size() > 0)) {
            return ParseResult.failure(Diagnostic.error("PROVIDER_ONLY_UNSUPPORTED", "$.proxy-providers", "第一版不展开 proxy-providers，请提供直接含 proxies 的订阅"));
        }
        if (!root.path("proxies").isArray()) { return ParseResult.failure(Diagnostic.error("MISSING_PROXIES", "$.proxies", "订阅必须含 proxies 列表")); }
        if (root.path("proxies").size() > MAX_NODES) { return ParseResult.failure(Diagnostic.error("NODE_LIMIT", "$.proxies", "节点数量超过 20000")); }
        var errors = new ArrayList<Diagnostic>();
        var nodes = new ArrayList<ParsedNode>();
        var names = new HashSet<String>();
        for (int index = 0; index < root.path("proxies").size(); index++) {
            var value = root.path("proxies").path(index);
            var path = "$.proxies[" + index + "]";
            if (!(value instanceof ObjectNode node)) { errors.add(Diagnostic.error("INVALID_NODE", path, "节点必须是对象")); continue; }
            var report = fields.validate(node, path);
            errors.addAll(report.errors());
            if (!report.valid()) { continue; }
            String name = node.path("name").asString();
            if (!names.add(name)) { errors.add(Diagnostic.error("DUPLICATE_NODE_NAME", path + ".name", "同一订阅内节点名称重复")); continue; }
            var connection = node.deepCopy();
            connection.remove(List.of("name", "type", "dialer-proxy"));
            nodes.add(new ParsedNode(name, node.path("type").asString(), connection,
                    node.has("dialer-proxy") ? node.path("dialer-proxy").asString() : null));
        }
        return new ParseResult(nodes, errors, List.of(), root.path("proxies").isEmpty());
    }
}

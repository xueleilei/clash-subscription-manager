package dev.clashsub.node;

import dev.clashsub.common.ApiValidationException;
import dev.clashsub.common.Diagnostic;
import dev.clashsub.common.ValidationReport;
import dev.clashsub.parse.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class LocalNodeInputParser {
    public static final int MAX_BYTES = 256 * 1024;
    public static final int MAX_BATCH_NODES = 500;
    private final SubscriptionParser parser;
    private final SafeYaml yaml;
    public LocalNodeInputParser(SubscriptionParser parser, SafeYaml yaml) { this.parser = parser; this.yaml = yaml; }

    public ParsedNode parse(String text) {
        var result = parseAll(text);
        if (result.size() != 1) { throw invalid("LOCAL_NODE_COUNT", "正在编辑单个节点，请只提供一个节点；多个节点请通过“添加节点”批量导入"); }
        return result.getFirst();
    }

    public List<ParsedNode> parseAll(String text) {
        if (text == null || text.isBlank() || text.length() > MAX_BYTES || text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw invalid("LOCAL_NODE_INPUT", "请输入节点内容，每次不能超过 256 KiB");
        }
        String clean = text.startsWith("\uFEFF") ? text.substring(1) : text;
        String input = normalize(clean);
        var result = parser.parse(input.getBytes(StandardCharsets.UTF_8), InputFormat.AUTO);
        if (result.hasErrors()) {
            var errors = result.errors().stream().map(error -> error.line() != null && !input.equals(clean)
                    ? new Diagnostic(error.severity(), error.code(), error.path(), Math.max(1, error.line() - 1), null, error.message()) : error).toList();
            throw new ApiValidationException(new ValidationReport(errors, result.warnings()));
        }
        if (result.nodes().isEmpty() || result.nodes().size() > MAX_BATCH_NODES) { throw invalid("LOCAL_NODE_COUNT", "每次请提供 1 至 500 个节点"); }
        return List.copyOf(result.nodes());
    }

    private String normalize(String text) {
        var lines = text.lines().toList();
        var content = lines.stream().map(String::strip).filter(line -> !line.isEmpty() && !line.startsWith("#")).toList();
        if (!content.isEmpty()) {
            String first = content.getFirst();
            if (first.startsWith("- ") || first.equals("-") || first.startsWith("[")) { return "proxies:\n" + text.indent(2); }
            // Clipboard exports often contain one flow mapping per line without YAML list markers.
            if (content.size() > 1 && content.stream().allMatch(line -> line.startsWith("{") && line.endsWith("}"))) {
                return "proxies:\n" + lines.stream().map(line -> line.isBlank() || line.stripLeading().startsWith("#") ? line : "- " + line.strip())
                        .collect(java.util.stream.Collectors.joining("\n"));
            }
        }
        try {
            var object = yaml.load(text).value();
            if (!object.has("proxies") && object.has("type")) {
                var root = object.objectNode(); root.putArray("proxies").add(object);
                return yaml.dump(new YamlDocument(root));
            }
        } catch (YamlInputException ignored) { /* URI and Base64 input is handled by the existing parser. */ }
        return text;
    }
    private ApiValidationException invalid(String code, String message) {
        return new ApiValidationException(ValidationReport.of(List.of(Diagnostic.error(code, "node", message))));
    }
}

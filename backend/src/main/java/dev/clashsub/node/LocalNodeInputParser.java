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
    private final SubscriptionParser parser;
    private final SafeYaml yaml;
    public LocalNodeInputParser(SubscriptionParser parser, SafeYaml yaml) { this.parser = parser; this.yaml = yaml; }

    public ParsedNode parse(String text) {
        if (text == null || text.isBlank() || text.length() > MAX_BYTES || text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw invalid("LOCAL_NODE_INPUT", "请输入单个节点，内容不能超过 256 KiB");
        }
        String input = text;
        try {
            var object = yaml.load(text).value();
            if (!object.has("proxies") && object.has("type")) {
                var root = object.objectNode(); root.putArray("proxies").add(object);
                input = yaml.dump(new YamlDocument(root));
            }
        } catch (YamlInputException ignored) { /* URI and Base64 input is handled by the existing parser. */ }
        var result = parser.parse(input.getBytes(StandardCharsets.UTF_8), InputFormat.AUTO);
        if (result.hasErrors()) { throw new ApiValidationException(new ValidationReport(result.errors(), result.warnings())); }
        if (result.nodes().size() != 1) { throw invalid("LOCAL_NODE_COUNT", "此入口每次维护一个节点，请只提供一个节点"); }
        return result.nodes().getFirst();
    }
    private ApiValidationException invalid(String code, String message) {
        return new ApiValidationException(ValidationReport.of(List.of(Diagnostic.error(code, "node", message))));
    }
}

package dev.clashsub.parse;

import dev.clashsub.common.Diagnostic;
import java.nio.charset.StandardCharsets;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
public class SafeYaml {
    public static final int MAX_BYTES = 10 * 1024 * 1024;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public YamlDocument load(String text) {
        if (text == null || text.isBlank() || text.length() > MAX_BYTES
                || text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw invalid(null, null, "YAML 为空或超过 10 MiB");
        }
        var options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setAllowRecursiveKeys(false);
        options.setNestingDepthLimit(50);
        options.setMaxAliasesForCollections(50);
        options.setCodePointLimit(MAX_BYTES);
        try {
            Object tree = new Yaml(new SafeConstructor(options)).load(text);
            var node = convert(tree, 0, new int[]{0}, new IdentityHashMap<>());
            if (!(node instanceof ObjectNode object)) { throw invalid(null, null, "YAML 顶层必须是对象"); }
            return new YamlDocument(object);
        } catch (YamlInputException exception) { throw exception; }
        catch (MarkedYAMLException exception) {
            var mark = exception.getProblemMark();
            throw invalid(mark == null ? null : mark.getLine() + 1, mark == null ? null : mark.getColumn() + 1,
                    "YAML 结构无效、键重复或使用了不支持的标签");
        } catch (RuntimeException exception) { throw invalid(null, null, "YAML 结构超过限制或含不支持的值"); }
    }

    public String dump(YamlDocument document) {
        var options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setAllowUnicode(true);
        options.setSplitLines(false);
        options.setIndent(2);
        return new Yaml(options).dump(JSON.convertValue(document.value(), Object.class));
    }

    private JsonNode convert(Object value, int depth, int[] count, IdentityHashMap<Object, Boolean> stack) {
        if (depth > 50 || ++count[0] > 200_000) { throw invalid(null, null, "YAML 展开后的结构超过限制"); }
        if (value == null) { return JSON.nullNode(); }
        if (value instanceof String || value instanceof Boolean || value instanceof Number) {
            if (value instanceof Double d && !Double.isFinite(d) || value instanceof Float f && !Float.isFinite(f)) {
                throw invalid(null, null, "YAML 不支持非有限数值");
            }
            return JSON.valueToTree(value);
        }
        if (stack.put(value, true) != null) { throw invalid(null, null, "YAML 不支持循环引用"); }
        try {
            if (value instanceof Map<?, ?> mapping) {
                var object = JSON.createObjectNode();
                for (var entry : mapping.entrySet()) {
                    if (!(entry.getKey() instanceof String key)) { throw invalid(null, null, "YAML 对象键必须是字符串"); }
                    object.set(key, convert(entry.getValue(), depth + 1, count, stack));
                }
                return object;
            }
            if (value instanceof List<?> sequence) {
                var array = JSON.createArrayNode();
                for (var entry : sequence) { array.add(convert(entry, depth + 1, count, stack)); }
                return array;
            }
            throw invalid(null, null, "YAML 只支持对象、列表及 JSON 标量");
        } finally { stack.remove(value); }
    }

    private YamlInputException invalid(Integer line, Integer column, String message) {
        return new YamlInputException(new Diagnostic(Diagnostic.Severity.ERROR, "INVALID_YAML", "$", line, column, message));
    }
}

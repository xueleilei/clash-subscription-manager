package dev.clashsub.parse;

import dev.clashsub.common.Diagnostic;
import dev.clashsub.parse.uri.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class UriSubscriptionParser {
    private final List<UriNodeAdapter> adapters = List.of(new ShadowsocksAdapter(), new ShadowsocksRAdapter(), new VmessAdapter(),
            new VlessAdapter(), new TrojanAdapter(), new Hysteria2Adapter(), new TuicAdapter());

    public ParseResult parse(String text) {
        var errors = new ArrayList<Diagnostic>();
        var nodes = new ArrayList<ParsedNode>();
        var names = new HashSet<String>();
        String[] lines = text.split("\\R", -1);
        int count = 0;
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index].strip();
            if (line.isEmpty() || line.startsWith("#")) { continue; }
            if (++count > ClashYamlParser.MAX_NODES) { return ParseResult.failure(Diagnostic.error("NODE_LIMIT", "$", "节点数量超过 20000")); }
            int schemeEnd = line.indexOf("://");
            String scheme = schemeEnd < 0 ? "" : line.substring(0, schemeEnd).toLowerCase(Locale.ROOT);
            var adapter = adapters.stream().filter(item -> item.supports(scheme)).findFirst();
            if (adapter.isEmpty()) {
                errors.add(new Diagnostic(Diagnostic.Severity.ERROR, "UNSUPPORTED_SCHEME", "$.lines[" + index + "]", index + 1, null, "不支持的节点链接协议"));
                continue;
            }
            var parsed = adapter.get().parse(line, index + 1);
            errors.addAll(parsed.errors());
            if (parsed.node() == null) { continue; }
            if (!names.add(parsed.node().originalName())) {
                errors.add(new Diagnostic(Diagnostic.Severity.ERROR, "DUPLICATE_NODE_NAME", "$.lines[" + index + "]", index + 1, null, "同一订阅内节点名称重复；请为无名链接指定不同名称"));
            } else { nodes.add(parsed.node()); }
        }
        if (count == 0) { errors.add(Diagnostic.error("EMPTY_URI_LIST", "$", "链接列表未包含节点")); }
        return new ParseResult(nodes, errors, List.of(), false);
    }
}

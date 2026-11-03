package dev.clashsub.rule;

import dev.clashsub.parse.SafeYaml;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.comments.CommentLine;
import org.yaml.snakeyaml.comments.CommentType;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;

/** Reads comments only after SafeYaml has validated the document; does not interpret rule values. */
public final class RuleYamlComments {
    private RuleYamlComments() { }

    public static List<RuleDefinition> read(String text, List<RuleDefinition> rules) {
        if (rules.isEmpty()) { return rules; }
        var root = (MappingNode) yaml().compose(new StringReader(text)); var tuple = ruleTuple(root);
        var sequence = (SequenceNode) tuple.getValueNode();
        if (sequence.getValue().size() != rules.size()) { throw new IllegalArgumentException("规则行数不一致"); }
        var result = new ArrayList<RuleDefinition>();
        for (int i = 0; i < rules.size(); i++) {
            var node = sequence.getValue().get(i); var leading = new ArrayList<String>();
            if (i == 0) { leading.addAll(lines(tuple.getKeyNode().getBlockComments())); leading.addAll(lines(tuple.getKeyNode().getInLineComments())); }
            leading.addAll(lines(node.getBlockComments()));
            var inline = lines(node.getInLineComments()); var trailing = new ArrayList<>(lines(node.getEndComments()));
            if (i == rules.size() - 1) {
                trailing.addAll(lines(sequence.getEndComments()));
                if (root.getValue().getLast() == tuple) { trailing.addAll(lines(root.getEndComments())); }
            }
            result.add(rules.get(i).withTrivia(new RuleTrivia(leading, inline.isEmpty() ? "" : " " + inline.getFirst(), trailing)));
        }
        return List.copyOf(result);
    }

    public static String write(String text, List<RuleDefinition> rules) {
        if (rules.stream().allMatch(rule -> rule.trivia().equals(RuleTrivia.EMPTY))) { return text; }
        var root = (MappingNode) yaml().compose(new StringReader(text));
        var sequence = (SequenceNode) ruleTuple(root).getValueNode(); var byLine = new HashMap<Integer, RuleTrivia>();
        for (int i = 0; i < rules.size(); i++) {
            var node = sequence.getValue().get(i);
            if (node.getStartMark().getLine() != node.getEndMark().getLine()) { throw new IllegalArgumentException("规则输出必须为单行"); }
            byLine.put(node.getStartMark().getLine(), rules.get(i).trivia());
        }
        String[] lines = text.split("\n", -1); var output = new ArrayList<String>();
        for (int i = 0; i < lines.length - 1; i++) { output.add(byLine.getOrDefault(i, RuleTrivia.EMPTY).render(lines[i])); }
        return String.join("\n", output) + "\n";
    }

    private static NodeTuple ruleTuple(MappingNode root) {
        return root.getValue().stream().filter(tuple -> tuple.getKeyNode() instanceof ScalarNode key && "rules".equals(key.getValue())).findFirst().orElseThrow();
    }

    private static List<String> lines(List<CommentLine> comments) {
        return comments == null ? List.of() : comments.stream().map(comment -> comment.getCommentType() == CommentType.BLANK_LINE ? "" : "#" + comment.getValue()).toList();
    }

    private static Yaml yaml() {
        var load = new LoaderOptions(); load.setProcessComments(true); load.setAllowDuplicateKeys(false); load.setAllowRecursiveKeys(false);
        load.setNestingDepthLimit(50); load.setMaxAliasesForCollections(50); load.setCodePointLimit(SafeYaml.MAX_BYTES);
        return new Yaml(new SafeConstructor(load));
    }
}

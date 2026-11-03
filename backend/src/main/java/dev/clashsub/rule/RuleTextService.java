package dev.clashsub.rule;

import dev.clashsub.common.ValidationReport;
import dev.clashsub.compile.OutputNameAllocator;
import dev.clashsub.compile.OutputNames;
import dev.clashsub.compile.UsedDependencies;
import dev.clashsub.node.NodeCatalog;
import dev.clashsub.profile.DraftSnapshot;
import dev.clashsub.profile.ReferenceIndex;
import dev.clashsub.profile.TargetRef;
import dev.clashsub.source.SourceState;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;

/** Whole-list text editing is a preview; persistence remains in the existing draft/publish flow. */
@Service
public class RuleTextService {
    private static final Pattern QUOTED_NAME = Pattern.compile("[,#\"'()\\\\]|^\\s|\\s$");
    private final RuleParser parser;
    private final RuleValidator validator;
    private final OutputNameAllocator allocator;

    public RuleTextService(RuleParser parser, RuleValidator validator, OutputNameAllocator allocator) {
        this.parser = parser; this.validator = validator; this.allocator = allocator;
    }

    public RuleTextDocument text(DraftSnapshot draft, NodeCatalog catalog, Map<UUID, SourceState> sources, long ownerRevision) {
        var names = names(draft, catalog, sources);
        String text = String.join("\n", draft.document().rules().stream().map(rule -> rule.trivia().render(format(rule, names))).toList());
        return new RuleTextDocument(Long.toString(draft.version()), text.isEmpty() ? "" : text + "\n", draft.document().rules().size(), Long.toString(ownerRevision));
    }

    public RuleImportPreview preview(DraftSnapshot draft, String text, NodeCatalog catalog, Map<UUID, SourceState> sources) {
        var names = names(draft, catalog, sources);
        var references = ReferenceIndex.of(draft.document(), catalog);
        var targets = new LinkedHashMap<>(references.targets());
        // Generated, source-qualified names take precedence over optional raw-name shortcuts.
        names.nodes().forEach((id, name) -> targets.put(name, List.of(TargetRef.node(id))));
        var parsed = parser.parse(text, new ReferenceIndex(targets, references.providers()));
        var checked = validator.validate(parsed.rules());
        var report = new ValidationReport(Stream.concat(parsed.report().errors().stream(), checked.errors().stream()).distinct().toList(),
                Stream.concat(parsed.report().warnings().stream(), checked.warnings().stream()).distinct().toList());
        var rules = report.valid() ? preserveUnchanged(draft.document().rules(), parsed.rules()) : draft.document().rules();
        if (report.valid()) { report = validator.validate(rules); }
        return new RuleImportPreview(report.valid(), Long.toString(draft.version()), rules, parsed.rules(), report);
    }

    private OutputNames names(DraftSnapshot draft, NodeCatalog catalog, Map<UUID, SourceState> sources) {
        return allocator.allocate(draft.document(), new UsedDependencies(catalog.nodes(), ValidationReport.ok()), sources);
    }

    private String format(RuleDefinition rule, OutputNames names) {
        if (rule.expression() == null || rule.target() == null || "UNSUPPORTED".equals(rule.expression().type())
                || rule.issues().stream().anyMatch(issue -> issue.severity() == dev.clashsub.common.Diagnostic.Severity.ERROR)) {
            return rule.rawText() == null || rule.rawText().isBlank() ? "UNSUPPORTED,,REJECT" : rule.rawText();
        }
        var expression = rule.expression();
        var fields = new ArrayList<String>(); fields.add(expression.type());
        if (!"MATCH".equals(expression.type())) {
            fields.add(expression.providerId() == null ? expression.value() : quote(names.providers().get(expression.providerId())));
        }
        String target = names.target(rule.target());
        fields.add(quote(target == null ? rule.target().unresolvedName() : target));
        fields.addAll(rule.modifiers());
        String line = String.join(",", fields);
        // A literal whitespace/hash inside a payload must not be re-read as an inline comment.
        return RuleLexer.commentStart(line) < 0 ? line : "- '" + line.replace("'", "''") + "'";
    }

    private String quote(String name) {
        if (name == null) { return "''"; }
        return QUOTED_NAME.matcher(name).find() ? "'" + name.replace("'", "''") + "'" : name;
    }

    private List<RuleDefinition> preserveUnchanged(List<RuleDefinition> original, List<RuleDefinition> parsed) {
        var existing = new HashMap<RuleKey, ArrayDeque<RuleDefinition>>();
        original.forEach(rule -> {
            if (rule.expression() != null && rule.issues().stream().noneMatch(issue -> issue.severity() == dev.clashsub.common.Diagnostic.Severity.ERROR)) {
                existing.computeIfAbsent(key(rule), ignored -> new ArrayDeque<>()).add(rule);
            }
        });
        var rules = new ArrayList<RuleDefinition>();
        for (var rule : parsed) {
            var matches = existing.get(key(rule));
            rules.add(matches == null || matches.isEmpty() ? rule : matches.removeFirst().withTrivia(rule.trivia()));
        }
        return List.copyOf(rules);
    }

    private RuleKey key(RuleDefinition rule) {
        var expression = rule.expression();
        return new RuleKey(expression.type(), expression.providerId() == null ? expression.value() : "",
                expression.providerId(), rule.target(), rule.modifiers());
    }

    private record RuleKey(String type, String value, UUID providerId, TargetRef target, List<String> modifiers) { }
}

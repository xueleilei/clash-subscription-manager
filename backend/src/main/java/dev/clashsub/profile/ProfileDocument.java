package dev.clashsub.profile;

import dev.clashsub.common.Diagnostic;
import dev.clashsub.rule.ProviderDefinition;
import dev.clashsub.rule.RuleDefinition;
import java.util.List;

public record ProfileDocument(int schemaVersion, String baseYaml, List<GroupDefinition> groups,
        List<RuleDefinition> rules, List<ProviderDefinition> providers, List<Diagnostic> importIssues) {
    public ProfileDocument {
        groups = List.copyOf(groups); rules = List.copyOf(rules);
        providers = List.copyOf(providers); importIssues = List.copyOf(importIssues);
    }
    @Override public String toString() { return "ProfileDocument[schemaVersion=" + schemaVersion + "]"; }
}

package dev.clashsub.profile;

import dev.clashsub.common.Diagnostic;
import dev.clashsub.node.NodeCatalog;
import dev.clashsub.rule.RuleDefinition;
import dev.clashsub.rule.RuleExpression;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

class ReferenceValidatorTest {
    private final ReferenceValidator validator = new ReferenceValidator();
    private final JsonMapper json = new JsonMapper();
    private final NodeCatalog empty = new NodeCatalog(Map.of());

    @Test void reportsCycleMissingTargetAndDuplicateMembersAtTheirPaths() {
        var a = UUID.randomUUID(); var b = UUID.randomUUID();
        var report = validator.validate(document(List.of(
                group(a, "A", TargetRef.group(b), TargetRef.group(b)),
                group(b, "B", TargetRef.group(a), TargetRef.node(UUID.randomUUID())))), empty);
        assertThat(report.errors()).extracting(Diagnostic::code).contains("GROUP_CYCLE", "DUPLICATE_MEMBER", "MISSING_NODE");
        assertThat(report.errors()).allSatisfy(issue -> assertThat(issue.path()).isNotBlank());
    }

    @Test void emptyGroupsAreValidWithExplicitFallbackWarning() {
        var report = validator.validate(document(List.of(group(UUID.randomUUID(), "Empty"))), empty);
        assertThat(report.valid()).isTrue();
        assertThat(report.warnings()).extracting(Diagnostic::code).contains("EMPTY_GROUP");
    }

    @Test void rejectsCrossProfileReferencesDuplicateNamesAndDynamicMembership() {
        var first = group(UUID.randomUUID(), "A", TargetRef.group(UUID.randomUUID()));
        var dynamic = new GroupDefinition(UUID.randomUUID(), "A", "select", List.of(), json.createObjectNode().put("include-all", true));
        var report = validator.validate(document(List.of(first, dynamic)), empty);
        assertThat(report.errors()).extracting(Diagnostic::code).contains("MISSING_GROUP", "DUPLICATE_GROUP_NAME", "DYNAMIC_MEMBERSHIP");
    }

    @Test void idsPreserveReferencesAfterGroupRename() {
        var id = UUID.randomUUID();
        var doc = new ProfileDocument(1, "mode: rule\n", List.of(group(id, "New name")),
                List.of(new RuleDefinition(UUID.randomUUID(), new RuleExpression("MATCH", "", null), TargetRef.group(id), List.of(), "", List.of())), List.of(), List.of());
        assertThat(validator.validate(doc, empty).valid()).isTrue();
    }

    private GroupDefinition group(UUID id, String name, TargetRef... members) {
        return new GroupDefinition(id, name, "select", List.of(members), json.createObjectNode());
    }
    private ProfileDocument document(List<GroupDefinition> groups) {
        return new ProfileDocument(1, "mode: rule\n", groups, List.of(), List.of(), List.of());
    }
}

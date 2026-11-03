package dev.clashsub.sync;

import dev.clashsub.profile.*;
import dev.clashsub.rule.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ReferenceReconcilerTest {
    @Test void removedDirectRuleKeepsAVisibleNonBlockingReasonAcrossLaterRefreshes() {
        UUID node = UUID.randomUUID();
        var rule = new RuleDefinition(UUID.randomUUID(), new RuleExpression("DOMAIN", "example.com", null), TargetRef.node(node), List.of(), "DOMAIN,example.com,old", List.of());
        var fallback = new RuleDefinition(UUID.randomUUID(), new RuleExpression("MATCH", "", null), TargetRef.reject(), List.of(), null, List.of());
        var document = new ProfileDocument(1, "{}", List.of(), List.of(rule, fallback), List.of(), List.of());
        var result = new ReferenceReconciler().reconcile(document, Set.of(node)).document();
        assertThat(result.rules().getFirst().issues()).anySatisfy(issue -> { assertThat(issue.code()).isEqualTo("REMOVED_NODE_TARGET"); assertThat(issue.severity().name()).isEqualTo("WARNING"); });
        assertThat(new RuleValidator().validate(result.rules()).valid()).isTrue();
        assertThat(new ReferenceReconciler().reconcile(result, Set.of()).document().rules().getFirst().issues()).isEqualTo(result.rules().getFirst().issues());
        assertThat(result.rules().getLast().issues()).isEmpty();
    }
}

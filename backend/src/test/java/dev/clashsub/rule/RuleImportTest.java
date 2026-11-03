package dev.clashsub.rule;

import dev.clashsub.node.NodeCatalog;
import dev.clashsub.profile.DraftSnapshot;
import dev.clashsub.profile.ProfileDocument;
import dev.clashsub.profile.TargetRef;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class RuleImportTest {
    private final RuleImportService service = new RuleImportService(new RuleParser());
    private DraftSnapshot draft() {
        var fallback = new RuleDefinition(UUID.randomUUID(), new RuleExpression("MATCH", "", null), TargetRef.reject(), List.of(), "MATCH,REJECT", List.of());
        return new DraftSnapshot(UUID.randomUUID(), 5, new ProfileDocument(1, "mode: rule\n", List.of(), List.of(fallback), List.of(), List.of()));
    }
    @Test void insertsBeforeMatchWithoutMutatingOriginalDraft() {
        var original = draft(); var result = service.preview(original, "DOMAIN,a.invalid,DIRECT\nDOMAIN,b.invalid,REJECT", MatchPolicy.KEEP);
        assertThat(result.applicable()).isTrue(); assertThat(result.rules()).hasSize(3);
        assertThat(result.rules().getLast()).isEqualTo(original.document().rules().getFirst());
        assertThat(original.document().rules()).hasSize(1);
        assertThat(result.expectedVersion()).isEqualTo("5");
    }
    @Test void incomingMatchRequiresExplicitReplacementAndMustBeUniqueAndLast() {
        assertThat(service.preview(draft(), "MATCH,DIRECT", MatchPolicy.KEEP).applicable()).isFalse();
        var replaced = service.preview(draft(), "DOMAIN,x.invalid,REJECT\nMATCH,DIRECT", MatchPolicy.REPLACE);
        assertThat(replaced.applicable()).isTrue(); assertThat(replaced.rules().getLast().target()).isEqualTo(TargetRef.direct());
        assertThat(service.preview(draft(), "MATCH,DIRECT\nDOMAIN,x.invalid,REJECT", MatchPolicy.REPLACE).applicable()).isFalse();
        assertThat(service.preview(draft(), "MATCH,DIRECT\nMATCH,REJECT", MatchPolicy.REPLACE).applicable()).isFalse();
    }
    @Test void anyInvalidLineKeepsEntireExistingRuleList() {
        var original = draft(); var result = service.preview(original, "DOMAIN,a.invalid,DIRECT\nUNKNOWN,b,REJECT", MatchPolicy.KEEP);
        assertThat(result.applicable()).isFalse(); assertThat(result.rules()).isEqualTo(original.document().rules());
        assertThat(result.importedRules()).hasSize(2);
    }
    @Test void imports2124ArtificialRulesInExactOrderAndNeverPartiallyAppliesBadLine() throws Exception {
        String text = java.nio.file.Files.readString(java.nio.file.Path.of("../tests/fixtures/rules/large-2124.txt"));
        var original = draft(); var result = service.preview(original, text, MatchPolicy.REPLACE);
        assertThat(result.applicable()).isTrue(); assertThat(result.rules()).hasSize(2124);
        for (int i = 0; i < 2123; i++) { assertThat(result.rules().get(i).expression().value()).isEqualTo("fixture-" + i + ".example.invalid"); }
        assertThat(result.rules().getLast().expression().type()).isEqualTo("MATCH");
        var failed = service.preview(original, text.replace("fixture-100.example.invalid,DIRECT", "fixture-100.example.invalid,missing-target"), MatchPolicy.REPLACE);
        assertThat(failed.applicable()).isFalse(); assertThat(failed.rules()).isEqualTo(original.document().rules());
    }
}

package dev.clashsub.importer;

import dev.clashsub.common.*;
import dev.clashsub.profile.*;
import dev.clashsub.sync.RefreshTestSupport;
import dev.clashsub.sync.RetentionService;
import dev.clashsub.support.ApiTestClient;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import static org.assertj.core.api.Assertions.*;

class ConfigImportIT extends RefreshTestSupport {
    @Autowired ConfigImportService imports;
    @Autowired RetentionService retention;
    @Autowired dev.clashsub.rule.RuleTextService ruleText;
    @org.springframework.boot.test.web.server.LocalServerPort int port;
    @Test void ruleCommentsSurviveFullConfigImportSaveAndCompilation() {
        String raw = "mode: rule\nrules:\n  # === 局域网直连 ===\n  - DOMAIN-SUFFIX,local,DIRECT # 本地网络\n\n  # === 分流规则 ===\n  - MATCH,REJECT\n  # 文件末尾\n";
        var preview = imports.preview(owner, raw);
        assertThat(preview.report().errors()).isEmpty();
        var draft = imports.confirm(owner, preview.id(), Map.of());
        var stored = profiles.load(owner, draft.profileId());
        String text = ruleText.text(stored, nodes.catalog(owner), Map.of(), 1L).text();
        assertThat(text).isEqualTo("# === 局域网直连 ===\nDOMAIN-SUFFIX,local,DIRECT # 本地网络\n\n# === 分流规则 ===\nMATCH,REJECT\n# 文件末尾\n");
        var prepared = publisher.prepare(draft.profileId(), stored.document(), snapshots.read(owner));
        assertThat(prepared.valid()).isTrue();
        assertThat(prepared.compiled().yaml()).contains("# === 局域网直连 ===", "# === 分流规则 ===", "# 文件末尾");
    }
    @Test void fullSyntheticConfigPreservesTwentyTwoGroupsTwoThousandRulesProvidersAndChains() throws Exception {
        String raw = Files.readString(Path.of("../tests/fixtures/imports/complete-2124.yaml")); var preview = imports.preview(owner, raw);
        assertThat(preview.nodes()).hasSize(5); assertThat(preview.document().groups()).hasSize(22);
        assertThat(preview.document().rules()).hasSize(2124); assertThat(preview.document().providers()).hasSize(14);
        assertThat(preview.report().errors()).isEmpty();
        var draft = imports.confirm(owner, preview.id(), Map.of()); assertThat(draft.document().baseYaml()).contains("7890", "dns.example.invalid");
        assertThat(profiles.list(owner)).hasSize(1); assertThat(profileRepository.require(owner, draft.profileId()).currentReleaseId()).isNull();
        assertThat(draft.document().groups().get(1).members()).contains(TargetRef.group(draft.document().groups().getFirst().id()));
        assertThat(draft.document().rules().getFirst().target().kind()).isEqualTo(TargetRef.Kind.NODE);
        assertThat(draft.document().rules().get(1).expression().providerId()).isEqualTo(draft.document().providers().getFirst().id());
        var gateway = nodes.catalog(owner).nodes().values().stream().filter(n -> n.originalName().equals("Gateway")).findFirst().orElseThrow();
        var leaf = nodes.catalog(owner).nodes().values().stream().filter(n -> n.originalName().equals("Leaf")).findFirst().orElseThrow();
        assertThat(leaf.dialerRef()).isEqualTo(TargetRef.node(gateway.id())); assertThat(leaf.sourceId()).isNull();
        assertThat(imports.original(owner, draft.profileId()).yaml()).isEqualTo(raw);
        assertThat(jdbc.queryForObject("SELECT payload_cipher FROM import_batch WHERE profile_id=?", String.class, draft.profileId().toString())).doesNotContain("fixture-gateway", "https://");
        assertThat(publisher.prepare(draft.profileId(), draft.document(), snapshots.read(owner)).valid()).isTrue();
    }
    @Test void exactConnectionMatchSuggestsButDoesNotSilentlyBindAndAmbiguityStaysLocal() {
        var remoteSource = source("Remote", yaml("Remote")); var remote = node(remoteSource, "Remote");
        var preview = imports.preview(owner, yaml("Renamed on import") + "proxy-groups: [{name: Choice, type: select, proxies: ['Renamed on import']}]\nrules: ['MATCH,Choice']\n");
        assertThat(preview.nodes().getFirst().suggestedRemoteId()).isEqualTo(remote.id());
        var local = imports.confirm(owner, preview.id(), Map.of()); var localId = local.document().groups().getFirst().members().getFirst().id();
        assertThat(nodes.connection(owner, localId).sourceId()).isNull();
        var accepted = imports.preview(owner, yaml("LocalName") + "rules: ['MATCH,LocalName']\n");
        var bound = imports.confirm(owner, accepted.id(), Map.of(accepted.nodes().getFirst().id(), new BindingChoice(BindingChoice.Mode.REMOTE, remote.id())));
        assertThat(bound.document().rules().getFirst().target()).isEqualTo(TargetRef.node(remote.id()));
        var different = imports.preview(owner, yaml("Remote").replace("fixture-first", "fixture-different") + "rules: ['MATCH,Remote']\n");
        assertThat(different.nodes().getFirst().candidateRemoteIds()).isEmpty();
        source("Same parameters second source", yaml("Second"));
        var ambiguous = imports.preview(owner, yaml("LocalName") + "rules: ['MATCH,LocalName']\n");
        assertThat(ambiguous.nodes().getFirst().candidateRemoteIds()).hasSize(2); assertThat(ambiguous.nodes().getFirst().suggestedRemoteId()).isNull();
    }
    @Test void unsupportedDefinitionsRemainInDraftAndOriginalBatchAfterPreviewExpiry() {
        String raw = yaml("Local") + "proxy-providers: {dynamic: {type: http, url: 'https://dynamic.example.invalid/sub'}}\n"
                + "proxy-groups: [{name: Choice, type: fallback, proxies: [Local], use: [dynamic]}]\n"
                + "rule-providers: {File: {type: file, path: ./unavailable.list, behavior: domain}}\nrules: ['RULE-SET,File,Choice','MYSTERY,raw,Choice','MATCH,Choice']\n";
        var preview = imports.preview(owner, raw); assertThat(preview.report().errors()).isNotEmpty();
        var draft = imports.confirm(owner, preview.id(), Map.of()); assertThat(draft.document().groups().getFirst().type()).isEqualTo("fallback");
        assertThat(draft.document().groups().getFirst().options().path("use").isArray()).isTrue();
        assertThat(draft.document().rules().get(1).rawText()).isEqualTo("MYSTERY,raw,Choice");
        assertThat(draft.document().providers().getFirst().options().path("path").asString()).isEqualTo("./unavailable.list");
        assertThat(draft.document().importIssues()).isNotEmpty();
        jdbc.update("UPDATE import_preview SET expires_at=? WHERE id=?", java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(1)), preview.id().toString());
        retention.clean(java.time.Instant.now());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM import_preview WHERE id=?", Integer.class, preview.id().toString())).isZero();
        assertThat(imports.original(owner, draft.profileId()).yaml()).isEqualTo(raw);
        assertThat(publisher.prepare(draft.profileId(), draft.document(), snapshots.read(owner)).valid()).isFalse();
    }
    @Test void expiredStaleConsumedAndForeignPreviewsCannotCreateProfiles() {
        var raw = yaml("Local") + "rules: ['MATCH,REJECT']\n"; var stale = imports.preview(owner, raw); profiles.create(owner, "Changed");
        assertThatThrownBy(() -> imports.confirm(owner, stale.id(), Map.of())).isInstanceOf(ApiException.class).extracting("status").isEqualTo(409);
        var expired = imports.preview(owner, raw); jdbc.update("UPDATE import_preview SET expires_at=? WHERE id=?", java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(1)), expired.id().toString());
        assertThatThrownBy(() -> imports.confirm(owner, expired.id(), Map.of())).isInstanceOf(ApiException.class).extracting("status").isEqualTo(409);
        var active = imports.preview(owner, raw);
        assertThatThrownBy(() -> imports.confirm(UUID.randomUUID(), active.id(), Map.of())).isInstanceOf(ApiException.class).extracting("status").isEqualTo(404);
        imports.confirm(owner, active.id(), Map.of());
        assertThatThrownBy(() -> imports.confirm(owner, active.id(), Map.of())).isInstanceOf(ApiException.class).extracting("status").isEqualTo(409);
        assertThat(profiles.list(owner)).hasSize(2);
    }

    @Test void connectionSuggestionsIncludeTheEntireDialerChain() {
        var source = source("Chain", yaml("RemoteGateway", "RemoteLeaf").replace("name: 'RemoteLeaf',", "name: 'RemoteLeaf', dialer-proxy: RemoteGateway,"));
        var preview = imports.preview(owner, yaml("Gateway", "Leaf").replace("name: 'Leaf',", "name: 'Leaf', dialer-proxy: Gateway,") + "rules: ['MATCH,Leaf']\n");
        assertThat(preview.nodes().stream().filter(n -> n.originalName().equals("Leaf")).findFirst().orElseThrow().suggestedRemoteId()).isEqualTo(node(source, "RemoteLeaf").id());
    }

    @Test void importHttpValidatesBindingChoiceAndReturnsNoStoreWithStringVersion() throws Exception {
        UUID admin = UUID.fromString(jdbc.queryForObject("SELECT id FROM app_user WHERE username=?", String.class, ADMIN));
        var client = new ApiTestClient(port); client.login(ADMIN, PASSWORD); String raw = yaml("HTTP local") + "rules: ['MATCH,REJECT']\n";
        var preview = client.send("POST", "/api/v1/imports/preview", Map.of("yaml", raw)); assertThat(preview.statusCode()).isEqualTo(200);
        assertThat(preview.headers().firstValue("cache-control")).hasValueSatisfying(value -> assertThat(value).contains("no-store"));
        String id = client.data(preview).path("id").asString(), nodeId = client.data(preview).path("nodes").path(0).path("id").asString();
        assertThat(client.send("POST", "/api/v1/imports/" + id + "/confirm", Map.of("choices", Map.of(nodeId, Map.of("mode", "REMOTE")))).statusCode()).isEqualTo(400);
        var confirmed = client.send("POST", "/api/v1/imports/" + id + "/confirm", Map.of("choices", Map.of())); assertThat(confirmed.statusCode()).isEqualTo(200);
        assertThat(client.data(confirmed).path("version").isString()).isTrue();
        String profileId = client.data(confirmed).path("profileId").asString(); var original = client.get("/api/v1/profiles/" + profileId + "/import-batch");
        assertThat(original.statusCode()).isEqualTo(200); assertThat(client.data(original).path("yaml").asString()).isEqualTo(raw);
        assertThat(original.headers().firstValue("cache-control")).hasValueSatisfying(value -> assertThat(value).contains("no-store"));
        assertThat(profiles.load(admin, UUID.fromString(profileId)).version()).isEqualTo(1);
        var foreign = imports.confirm(owner, imports.preview(owner, raw).id(), Map.of());
        assertThat(client.get("/api/v1/profiles/" + foreign.profileId() + "/import-batch").statusCode()).isEqualTo(404);
    }
}

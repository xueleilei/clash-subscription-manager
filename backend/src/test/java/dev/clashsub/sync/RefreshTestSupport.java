package dev.clashsub.sync;

import dev.clashsub.common.*;
import dev.clashsub.compile.MihomoValidator;
import dev.clashsub.node.*;
import dev.clashsub.profile.*;
import dev.clashsub.release.*;
import dev.clashsub.rule.*;
import dev.clashsub.source.*;
import dev.clashsub.support.MySqlIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

public abstract class RefreshTestSupport extends MySqlIntegrationTest {
    @Autowired protected RefreshService refresh;
    @Autowired protected EmptyDecisionService decisions;
    @Autowired protected SourceService sources;
    @Autowired protected SourceRepository sourceRepository;
    @Autowired protected NodeRepository nodes;
    @Autowired protected ProfileService profiles;
    @Autowired protected ProfileRepository profileRepository;
    @Autowired protected OwnerSnapshotReader snapshots;
    @Autowired protected ReleaseService publisher;
    @Autowired protected ReleaseRepository releases;
    @Autowired protected SubscriptionTokenService tokens;
    @Autowired protected OwnerRevisionGuard revisions;
    @Autowired protected JsonMapper json;
    @Autowired protected JdbcTemplate jdbc;
    @MockitoBean protected UpstreamFetcher fetcher;
    @MockitoSpyBean protected MihomoValidator core;
    @MockitoSpyBean protected ProfileMapper profileMapper;
    protected UUID owner;
    protected final Map<String, FetchResult> responses = new ConcurrentHashMap<>();
    @BeforeEach void setupRefresh() {
        owner = UUID.randomUUID(); jdbc.update("INSERT INTO app_user(id,username,password_hash) VALUES(?,?,?)", owner.toString(), "refresh-" + owner, "fixture-hash");
        responses.clear();
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            SourceRequest request = call.getArgument(0); return responses.getOrDefault(request.url(), FetchResult.failure("UPSTREAM_IO"));
        }).when(fetcher).fetch(any());
    }
    protected UUID source(String name, String body) {
        var view = sources.save(owner, new SourceCommand(null, null, name, true, null, null, "https://fixture.example.invalid/" + UUID.randomUUID(), Map.of(), "", ""));
        respond(view.id(), body); assertThat(run(view.id()).status()).isEqualTo("SUCCESS"); return view.id();
    }
    protected void respond(UUID source, String body) { responses.put(sources.credentials(owner, source).url(), new FetchResult(body.getBytes(StandardCharsets.UTF_8), 200, "application/yaml", Instant.now(), null)); }
    protected RefreshOutcome run(UUID source) { return refresh.refresh(owner, source, UUID.randomUUID()); }
    protected String yaml(String... names) {
        var text = new StringBuilder("proxies:\n");
        for (String name : names) { text.append("- {name: '").append(name).append("', type: ss, server: fixture.example.invalid, port: 443, cipher: aes-128-gcm, password: fixture-first}\n"); }
        return text.toString();
    }
    protected NodeSpec node(UUID source, String name) { return nodes.catalog(owner).nodes().values().stream().filter(n -> source.equals(n.sourceId()) && name.equals(n.originalName())).findFirst().orElseThrow(); }
    protected UUID profile(String name, List<UUID> members, UUID directRule) {
        var draft = profiles.create(owner, name); var group = new GroupDefinition(UUID.randomUUID(), name, "select", members.stream().map(TargetRef::node).toList(), json.createObjectNode());
        var rules = new ArrayList<RuleDefinition>();
        if (directRule != null) { rules.add(new RuleDefinition(UUID.randomUUID(), new RuleExpression("IP-CIDR", "192.0.2.0/24", null), TargetRef.node(directRule), List.of("no-resolve"), "", List.of())); }
        rules.add(new RuleDefinition(UUID.randomUUID(), new RuleExpression("MATCH", "", null), TargetRef.group(group.id()), List.of(), "", List.of()));
        publisher.publish(owner, draft.profileId(), 1, new ProfileDocument(1, "mode: rule\n", List.of(group), rules, List.of(), List.of())); return draft.profileId();
    }
    protected String artifact(UUID profile) { return tokens.readPublished(tokens.link(owner, profile).token()).yaml(); }
    protected ProfileDocument published(UUID profile) { return snapshots.read(owner).profiles().get(profile).published().definition(); }
    protected void assertUnchanged(OwnerSnapshot before, UUID source, UUID... profileIds) {
        var after = snapshots.read(owner); assertThat(after.nodes()).isEqualTo(before.nodes()); assertThat(after.sources().get(source)).isEqualTo(before.sources().get(source));
        for (var id : profileIds) { assertThat(after.profiles().get(id)).isEqualTo(before.profiles().get(id)); }
    }
}

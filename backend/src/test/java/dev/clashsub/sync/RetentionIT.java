package dev.clashsub.sync;

import dev.clashsub.source.StoredSourceSnapshot;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

class RetentionIT extends RefreshTestSupport {
    @Autowired RetentionService retention;
    @Autowired TransactionTemplate transaction;
    @Test void retentionKeepsCurrentAndHundredHistoricalReleasesAndAllTheirSourceVersions() {
        var source = source("History", yaml("Old")); var node = node(source, "Old"); var profile = profile("Choice", List.of(node.id()), null);
        var current = profileRepository.require(owner, profile).currentReleaseId(); var beforeYaml = artifact(profile);
        var snapshot = snapshots.read(owner); var prepared = publisher.prepare(profile, snapshot.profiles().get(profile).published().definition(), snapshot);
        transaction.executeWithoutResult(tx -> {
            revisions.compareAndIncrement(owner, revisions.current(owner));
            for (int i = 0; i < 103; i++) { releases.create(owner, profile, prepared, snapshot.nodes(), "RETENTION_FIXTURE"); }
            sourceRepository.saveSnapshot(owner, source, 0, new StoredSourceSnapshot(new byte[0], List.of()), Instant.now().minus(Duration.ofDays(40)));
        });
        var old = java.sql.Timestamp.from(Instant.now().minus(Duration.ofDays(40)));
        jdbc.update("UPDATE profile_release SET create_time=? WHERE owner_id=?", old, owner.toString());
        jdbc.update("UPDATE source_snapshot SET create_time=? WHERE owner_id=?", old, owner.toString());
        jdbc.update("UPDATE refresh_attempt SET create_time=? WHERE owner_id=?", old, owner.toString());
        var result = retention.clean(Instant.now()); assertThat(result.releasesDeleted()).isGreaterThanOrEqualTo(3);
        assertThat(releases.list(owner, profile)).hasSize(101); assertThat(profileRepository.require(owner, profile).currentReleaseId()).isEqualTo(current);
        assertThat(artifact(profile)).isEqualTo(beforeYaml);
        assertThat(sourceRepository.snapshot(owner, source, 1).nodes()).contains(node);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM source_snapshot WHERE owner_id=? AND snapshot_version=0", Integer.class, owner.toString())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_attempt WHERE owner_id=?", Integer.class, owner.toString())).isZero();
        assertThat(retention.clean(Instant.now()).releasesDeleted()).isZero();
    }
    @Test void pendingChoicesAndCurrentSnapshotsSurviveCleanupAndTombstonesKeepOnlyIdentity() {
        var source = source("Pending", yaml("Old")); var old = node(source, "Old").id();
        respond(source, yaml("New")); assertThat(run(source).status()).isEqualTo("SUCCESS");
        respond(source, "proxies: []\n"); var pending = run(source).decisionId();
        var past = java.sql.Timestamp.from(Instant.now().minus(Duration.ofDays(40)));
        jdbc.update("UPDATE pending_empty_decision SET create_time=? WHERE owner_id=?", past, owner.toString());
        jdbc.update("UPDATE source_snapshot SET create_time=? WHERE owner_id=?", past, owner.toString());
        jdbc.update("UPDATE node SET update_time=? WHERE owner_id=? AND is_active=0", past, owner.toString());
        retention.clean(Instant.now());
        assertThat(decisions.pending(owner)).extracting(EmptyDecisionView::id).contains(pending);
        assertThat(sourceRepository.snapshot(owner, source, 2).nodes()).hasSize(1);
        assertThat(nodes.sourceHistory(owner, source).stream().filter(n -> n.id().equals(old)).findFirst().orElseThrow().connection().isEmpty()).isTrue();
        decisions.decide(owner, pending, EmptyChoice.KEEP); assertThat(nodes.catalog(owner).nodes()).hasSize(1);
    }
}

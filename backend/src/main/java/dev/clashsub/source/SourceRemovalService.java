package dev.clashsub.source;

import dev.clashsub.common.*;
import dev.clashsub.importer.*;
import dev.clashsub.node.*;
import dev.clashsub.profile.OwnerSnapshotReader;
import dev.clashsub.sync.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SourceRemovalService {
    private final OwnerSnapshotReader snapshots;
    private final SourceRepository sources;
    private final PreviewRepository previews;
    private final RefreshPlanner planner;
    private final SourceRemovalCommitter committer;
    private final RefreshTaskRunner runner;
    public SourceRemovalService(OwnerSnapshotReader snapshots, SourceRepository sources, PreviewRepository previews, RefreshPlanner planner, SourceRemovalCommitter committer, RefreshTaskRunner runner) {
        this.snapshots = snapshots; this.sources = sources; this.previews = previews; this.planner = planner; this.committer = committer; this.runner = runner;
    }
    public RemovalPreview preview(UUID owner, UUID source) {
        var snapshot = snapshots.read(owner); if (!snapshot.sources().containsKey(source)) { throw ApiException.missing(); }
        var ids = new HashSet<UUID>(); snapshot.nodes().nodes().values().stream().filter(node -> source.equals(node.sourceId())).forEach(node -> ids.add(node.id()));
        var affected = new TreeSet<UUID>();
        for (var profile : snapshot.profiles().values()) {
            if (profile.published() != null && profile.published().sourceVersions().containsKey(source)) { affected.add(profile.profileId()); }
            var pending = new ArrayDeque<>(DocumentNodeReferences.direct(profile.draft().document())); var visited = new HashSet<UUID>();
            while (!pending.isEmpty()) {
                var id = pending.removeFirst(); if (!visited.add(id)) { continue; }
                if (ids.contains(id)) { affected.add(profile.profileId()); }
                var node = snapshot.nodes().get(id);
                if (node != null && node.dialerRef() != null && node.dialerRef().kind() == dev.clashsub.profile.TargetRef.Kind.NODE) { pending.add(node.dialerRef().id()); }
            }
        }
        var row = previews.create(owner, "REMOVAL", snapshot.dataRevision(), new RemovalSelection(source));
        return new RemovalPreview(UUID.fromString(row.id()), Long.toString(row.dataRevision()), row.expiresAt(), source, ids.size(), List.copyOf(affected));
    }
    @Transactional(propagation = Propagation.NEVER)
    public RemovalResult apply(UUID owner, UUID previewId, RemovalMode mode) {
        if (mode == null) { throw ApiException.invalid("请选择清理节点或转为本地保留"); }
        var stored = previews.require(owner, previewId, "REMOVAL", RemovalSelection.class); var before = snapshots.read(owner);
        if (before.dataRevision() != stored.metadata().dataRevision()) { throw ApiException.conflict(); }
        UUID sourceId = stored.payload().sourceId(); var source = sources.require(owner, sourceId); var catalog = new LinkedHashMap<>(before.nodes().nodes());
        var removed = new HashSet<UUID>(); var updated = new HashSet<UUID>(); var replacement = new ArrayList<NodeSpec>();
        for (var node : before.nodes().nodes().values()) {
            if (!sourceId.equals(node.sourceId())) { continue; }
            if (mode == RemovalMode.KEEP_LOCAL) {
                var local = new NodeSpec(node.id(), null, node.originalName(), node.protocol(), node.connection(), node.dialerRef());
                catalog.put(local.id(), local); replacement.add(local); updated.add(local.id());
            } else { catalog.remove(node.id()); removed.add(node.id()); }
        }
        var diff = new NodeDiff(replacement, Set.of(), updated, removed, new NodeCatalog(catalog)); var versions = new LinkedHashMap<>(before.sources()); versions.remove(sourceId);
        var candidate = new OwnerSnapshot(owner, before.dataRevision(), versions, diff.catalog(), before.profiles());
        var proposal = planner.plan(before, candidate, source, diff, UUID.randomUUID(), null, new byte[0], true);
        var applied = committer.commit(previewId, proposal, mode); applied.cancelledAttempts().forEach(runner::interrupt); return applied.result();
    }
}

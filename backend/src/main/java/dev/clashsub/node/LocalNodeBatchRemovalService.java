package dev.clashsub.node;

import dev.clashsub.common.ApiException;
import dev.clashsub.common.OwnerSnapshot;
import dev.clashsub.importer.DocumentNodeReferences;
import dev.clashsub.profile.OwnerSnapshotReader;
import dev.clashsub.profile.ProfileDocument;
import dev.clashsub.profile.ProfileState;
import dev.clashsub.profile.TargetRef;
import dev.clashsub.release.ReleaseRepository;
import dev.clashsub.sync.ReferenceReconciler;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LocalNodeBatchRemovalService {
    private final OwnerSnapshotReader snapshots;
    private final NodeRepository nodes;
    private final ReleaseRepository releases;
    private final ReferenceReconciler reconciler;
    private final LocalNodeCommitter committer;

    public LocalNodeBatchRemovalService(OwnerSnapshotReader snapshots, NodeRepository nodes, ReleaseRepository releases,
            ReferenceReconciler reconciler, LocalNodeCommitter committer) {
        this.snapshots = snapshots; this.nodes = nodes; this.releases = releases;
        this.reconciler = reconciler; this.committer = committer;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public LocalNodeBatchRemoval preview(UUID owner, List<UUID> selection) {
        var ids = LocalNodeSelection.validate(selection);
        var rows = nodes.requireLocalBatch(owner, ids);
        var before = snapshots.read(owner);
        var affectedNodes = affectedNodes(before.nodes(), ids);
        var affectedProfiles = before.profiles().values().stream()
                .filter(profile -> DocumentNodeReferences.direct(profile.draft().document()).stream().anyMatch(affectedNodes::contains))
                .map(ProfileState::profileId).sorted().toList();
        var publishedProfiles = before.profiles().values().stream().filter(profile -> profile.published() != null
                && releases.require(owner, profile.profileId(), profile.published().id()).nodes().nodes().keySet().stream().anyMatch(ids::contains))
                .map(ProfileState::profileId).sorted().toList();
        var items = ids.stream().sorted().map(id -> new LocalNodeBatchRemoval.Item(id, rows.get(id).originalName(),
                Long.toString(rows.get(id).snapshotVersion()))).toList();
        var dependents = affectedNodes.stream().filter(id -> !ids.contains(id)).sorted()
                .map(id -> new LocalNodeBatchRemoval.NamedNode(id, before.nodes().get(id).originalName())).toList();
        return new LocalNodeBatchRemoval(items, Long.toString(before.dataRevision()), affectedProfiles, publishedProfiles, dependents);
    }

    @Transactional(propagation = Propagation.NEVER)
    public LocalNodeBatchResult delete(UUID owner, LocalNodeBatchDeleteCommand command) {
        if (command.nodes() == null || command.nodes().stream().anyMatch(item -> item == null || item.expectedVersion() == null || item.expectedVersion() < 0)
                || command.ownerRevision() == null || command.ownerRevision() < 0) {
            throw ApiException.invalid("请重新检查删除影响，提供完整的节点版本和数据版本");
        }
        var ids = LocalNodeSelection.validate(command.nodes().stream().map(LocalNodeBatchDeleteCommand.Item::id).toList());
        var before = snapshots.read(owner);
        if (command.ownerRevision() != before.dataRevision()) { throw ApiException.conflict(); }
        var rows = nodes.requireLocalBatch(owner, ids);
        var versions = new LinkedHashMap<UUID, Long>();
        for (var item : command.nodes()) {
            if (item.expectedVersion() != rows.get(item.id()).snapshotVersion()) { throw ApiException.conflict(); }
            versions.put(item.id(), item.expectedVersion());
        }
        if (affectedNodes(before.nodes(), ids).stream().anyMatch(id -> !ids.contains(id))) {
            throw new ApiException(409, "A0003", "所选节点仍被未选中的节点用作链式代理，请先解除依赖或一并选择这些手动节点");
        }
        var drafts = new LinkedHashMap<UUID, ProfileDocument>();
        for (var profile : before.profiles().values()) {
            var original = profile.draft().document();
            var updated = reconciler.reconcile(original, ids).document();
            if (!updated.equals(original)) { drafts.put(profile.profileId(), updated); }
        }
        return committer.deleteBatch(before, versions, drafts);
    }

    /** Traverse reverse dialer edges once, including indirect dependencies and selected chain members. */
    private Set<UUID> affectedNodes(NodeCatalog catalog, Set<UUID> selected) {
        var reverse = new HashMap<UUID, List<UUID>>();
        for (var node : catalog.nodes().values()) {
            var dialer = node.dialerRef();
            if (dialer != null && dialer.kind() == TargetRef.Kind.NODE) {
                reverse.computeIfAbsent(dialer.id(), ignored -> new ArrayList<>()).add(node.id());
            }
        }
        var affected = new HashSet<>(selected);
        var queue = new ArrayDeque<>(selected);
        while (!queue.isEmpty()) {
            for (var id : reverse.getOrDefault(queue.removeFirst(), List.of())) {
                if (affected.add(id)) { queue.addLast(id); }
            }
        }
        return affected;
    }
}

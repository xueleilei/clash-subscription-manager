package dev.clashsub.node;

import dev.clashsub.common.OwnerRevisionGuard;
import dev.clashsub.common.OwnerSnapshot;
import dev.clashsub.profile.ProfileDocument;
import dev.clashsub.profile.ProfileRepository;
import java.util.Map;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class LocalNodeCommitter {
    private final OwnerRevisionGuard revisions;
    private final NodeRepository nodes;
    private final ProfileRepository profiles;
    private final LocalNodeGroupService groups;
    public LocalNodeCommitter(OwnerRevisionGuard revisions, NodeRepository nodes, ProfileRepository profiles, LocalNodeGroupService groups) {
        this.revisions = revisions; this.nodes = nodes; this.profiles = profiles; this.groups = groups;
    }
    @Transactional public LocalNodeResult save(OwnerSnapshot before, NodeSpec node, Long expected,
            Map<UUID, ProfileDocument> drafts, UUID groupId, String regionOverride) {
        revisions.compareAndIncrement(before.ownerId(), before.dataRevision());
        if (groupId != null) { groups.require(before.ownerId(), groupId); }
        if (expected == null) { nodes.insertLocal(before.ownerId(), node, groupId, regionOverride); }
        else { nodes.updateLocal(before.ownerId(), node, expected, regionOverride); }
        applyDrafts(before, drafts);
        return new LocalNodeResult(node.id(), Long.toString(expected == null ? 0 : expected + 1), drafts.keySet().stream().sorted().toList());
    }
    @Transactional public LocalNodeResult saveRegion(UUID owner, NodeSpec node, long expected, String regionOverride) {
        revisions.lock(owner);
        nodes.updateLocalRegion(owner, node, expected, regionOverride);
        return new LocalNodeResult(node.id(), Long.toString(expected + 1), List.of());
    }
    @Transactional public LocalNodeResult delete(OwnerSnapshot before, UUID id, long expected, Map<UUID, ProfileDocument> drafts) {
        revisions.compareAndIncrement(before.ownerId(), before.dataRevision());
        nodes.deleteLocal(before.ownerId(), id, expected); applyDrafts(before, drafts);
        return new LocalNodeResult(id, Long.toString(expected + 1), drafts.keySet().stream().sorted().toList());
    }
    @Transactional public LocalNodeBatchResult saveBatch(OwnerSnapshot before, List<NodeSpec> batch, UUID groupId, String regionOverride) {
        revisions.compareAndIncrement(before.ownerId(), before.dataRevision());
        if (groupId != null) { groups.require(before.ownerId(), groupId); }
        for (var node : batch) { nodes.insertLocal(before.ownerId(), node, groupId, regionOverride); }
        return new LocalNodeBatchResult(batch.stream().map(NodeSpec::id).toList(), List.of());
    }
    @Transactional public LocalNodeBatchResult deleteBatch(OwnerSnapshot before, Map<UUID, Long> versions, Map<UUID, ProfileDocument> drafts) {
        revisions.compareAndIncrement(before.ownerId(), before.dataRevision());
        for (var entry : new TreeMap<>(versions).entrySet()) {
            nodes.deleteLocal(before.ownerId(), entry.getKey(), entry.getValue());
        }
        applyDrafts(before, drafts);
        return new LocalNodeBatchResult(versions.keySet().stream().sorted().toList(), drafts.keySet().stream().sorted().toList());
    }
    private void applyDrafts(OwnerSnapshot before, Map<UUID, ProfileDocument> drafts) {
        for (var entry : new TreeMap<>(drafts).entrySet()) {
            var previous = before.profiles().get(entry.getKey());
            profiles.touch(before.ownerId(), entry.getKey(), previous.profileVersion());
            profiles.updateDraft(before.ownerId(), entry.getKey(), previous.draft().version(), entry.getValue());
        }
    }
}

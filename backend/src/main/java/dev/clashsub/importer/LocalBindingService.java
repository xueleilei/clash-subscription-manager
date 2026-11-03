package dev.clashsub.importer;

import dev.clashsub.common.*;
import dev.clashsub.node.*;
import dev.clashsub.profile.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LocalBindingService {
    private final OwnerSnapshotReader snapshots;
    private final OwnerRevisionGuard revisions;
    private final PreviewRepository previews;
    private final ProfileRepository profiles;
    private final NodeRepository nodes;
    public LocalBindingService(OwnerSnapshotReader snapshots, OwnerRevisionGuard revisions, PreviewRepository previews, ProfileRepository profiles, NodeRepository nodes) {
        this.snapshots = snapshots; this.revisions = revisions; this.previews = previews; this.profiles = profiles; this.nodes = nodes;
    }
    public BindingPreview preview(UUID owner, UUID localId, UUID remoteId) {
        var snapshot = snapshots.read(owner); require(snapshot, localId, remoteId);
        var plan = DraftBindingPlan.build(snapshot, localId, remoteId); var row = previews.create(owner, "BINDING", snapshot.dataRevision(), new BindingSelection(localId, remoteId));
        var local = snapshot.nodes().get(localId); var remote = snapshot.nodes().get(remoteId); var changed = new TreeSet<String>();
        if (!local.protocol().equals(remote.protocol())) { changed.add("type"); }
        var fields = new HashSet<String>(local.connection().propertyNames()); fields.addAll(remote.connection().propertyNames());
        for (var key : fields) { if (!local.connection().path(key).equals(remote.connection().path(key))) { changed.add(key); } }
        if (!Objects.equals(local.dialerRef(), remote.dialerRef())) { changed.add("dialer-proxy"); }
        return new BindingPreview(UUID.fromString(row.id()), Long.toString(row.dataRevision()), row.expiresAt(), localId, remoteId, plan.drafts().keySet().stream().sorted().toList(), List.copyOf(changed));
    }
    @Transactional public BindingResult apply(UUID owner, UUID id) {
        var stored = previews.require(owner, id, "BINDING", BindingSelection.class); var snapshot = snapshots.read(owner);
        if (snapshot.dataRevision() != stored.metadata().dataRevision()) { throw ApiException.conflict(); }
        var choice = stored.payload(); require(snapshot, choice.localNodeId(), choice.remoteNodeId());
        revisions.compareAndIncrement(owner, snapshot.dataRevision()); var plan = DraftBindingPlan.build(snapshot, choice.localNodeId(), choice.remoteNodeId());
        plan.copies().forEach(node -> nodes.insertLocal(owner, node));
        for (var entry : plan.drafts().entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            var previous = snapshot.profiles().get(entry.getKey()); profiles.touch(owner, entry.getKey(), previous.profileVersion());
            profiles.updateDraft(owner, entry.getKey(), previous.draft().version(), entry.getValue());
        }
        previews.consume(owner, id); return new BindingResult(choice.localNodeId(), choice.remoteNodeId(), plan.drafts().keySet().stream().sorted().toList(), plan.copies().size());
    }
    private void require(OwnerSnapshot snapshot, UUID local, UUID remote) {
        var from = snapshot.nodes().get(local); var to = snapshot.nodes().get(remote);
        if (from == null || to == null || from.sourceId() != null || to.sourceId() == null) { throw ApiException.missing(); }
    }
}

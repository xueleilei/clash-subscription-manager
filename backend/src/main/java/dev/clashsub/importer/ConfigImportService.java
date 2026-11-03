package dev.clashsub.importer;

import dev.clashsub.common.*;
import dev.clashsub.node.*;
import dev.clashsub.profile.*;
import dev.clashsub.release.SubscriptionTokenService;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConfigImportService {
    private final FullConfigDecoder decoder;
    private final ConnectionMatcher matcher;
    private final PreviewRepository previews;
    private final OwnerSnapshotReader snapshots;
    private final OwnerRevisionGuard revisions;
    private final NodeRepository nodes;
    private final ProfileRepository profiles;
    private final SubscriptionTokenService tokens;
    public ConfigImportService(FullConfigDecoder decoder, ConnectionMatcher matcher, PreviewRepository previews, OwnerSnapshotReader snapshots,
            OwnerRevisionGuard revisions, NodeRepository nodes, ProfileRepository profiles, SubscriptionTokenService tokens) {
        this.decoder = decoder; this.matcher = matcher; this.previews = previews; this.snapshots = snapshots; this.revisions = revisions;
        this.nodes = nodes; this.profiles = profiles; this.tokens = tokens;
    }
    public ImportPreview preview(UUID owner, String yaml) {
        var snapshot = snapshots.read(owner); var decoded = decoder.decode(yaml); var suggestions = matcher.suggest(decoded.nodes(), snapshot.nodes());
        var payload = new ImportPayload(yaml, decoded.document(), decoded.nodes(), suggestions, decoded.report()); var row = previews.create(owner, "IMPORT", snapshot.dataRevision(), payload);
        return new ImportPreview(UUID.fromString(row.id()), Long.toString(row.dataRevision()), row.expiresAt(), decoded.document(), suggestions, decoded.report());
    }
    @Transactional public DraftSnapshot confirm(UUID owner, UUID id, Map<UUID, BindingChoice> submitted) {
        var stored = previews.require(owner, id, "IMPORT", ImportPayload.class); var payload = stored.payload();
        Map<UUID, BindingChoice> choices = submitted == null ? Map.of() : submitted;
        if (!payload.nodes().nodes().keySet().containsAll(choices.keySet())) { throw ApiException.invalid("关联选择包含不在本次导入中的节点"); }
        revisions.compareAndIncrement(owner, stored.metadata().dataRevision()); var available = nodes.catalog(owner); var replacements = new LinkedHashMap<UUID, UUID>();
        for (var entry : choices.entrySet()) {
            var choice = entry.getValue(); if (choice == null || choice.mode() == null) { throw ApiException.invalid("请选择保留本地或关联上游"); }
            if (choice.mode() == BindingChoice.Mode.REMOTE) {
                if (choice.remoteNodeId() == null) { throw ApiException.invalid("关联上游需要选择节点"); }
                var remote = available.get(choice.remoteNodeId()); if (remote == null || remote.sourceId() == null) { throw ApiException.missing(); } replacements.put(entry.getKey(), remote.id());
            } else if (choice.remoteNodeId() != null) { throw ApiException.invalid("保留本地不能附带上游 ID"); }
        }
        for (var node : payload.nodes().nodes().values()) {
            if (!replacements.containsKey(node.id())) {
                nodes.insertLocal(owner, new NodeSpec(node.id(), null, node.originalName(), node.protocol(), node.connection(), DocumentNodeReferences.replace(node.dialerRef(), replacements)));
            }
        }
        var document = DocumentNodeReferences.replace(payload.document(), replacements); UUID profile = UUID.randomUUID();
        var draft = profiles.insert(owner, profile, "导入方案 " + profile.toString().substring(0, 8), document); tokens.initialize(owner, profile);
        previews.saveOriginal(owner, profile, payload.yaml()); previews.consume(owner, id); return draft;
    }
    @Transactional(readOnly = true) public OriginalImport original(UUID owner, UUID profile) { return previews.original(owner, profile); }
}

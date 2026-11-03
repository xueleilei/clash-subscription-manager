package dev.clashsub.release;

import dev.clashsub.common.ApiException;
import dev.clashsub.common.ApiValidationException;
import dev.clashsub.common.DefinitionHasher;
import dev.clashsub.common.EncryptedDocuments;
import dev.clashsub.node.NodeCatalog;
import dev.clashsub.node.NodeSpec;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ReleaseRepository {
    private final ReleaseMapper mapper;
    private final EncryptedDocuments documents;
    private final DefinitionHasher hashes;
    public ReleaseRepository(ReleaseMapper mapper, EncryptedDocuments documents, DefinitionHasher hashes) { this.mapper = mapper; this.documents = documents; this.hashes = hashes; }
    public List<ReleaseView> list(UUID owner, UUID profile) { return mapper.list(owner.toString(), profile.toString()).stream().map(this::view).toList(); }
    public StoredRelease require(UUID owner, UUID profile, UUID id) {
        var row = mapper.find(owner.toString(), profile.toString(), id.toString());
        if (row == null) { throw ApiException.missing(); }
        return documents.read(row.payloadCipher(), context(owner, id), StoredRelease.class);
    }
    public ReleaseView metadata(UUID owner, UUID profile, UUID id) {
        var row = mapper.metadata(owner.toString(), profile.toString(), id.toString());
        if (row == null) { throw ApiException.missing(); } return view(row);
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public ReleaseView create(UUID owner, UUID profile, PreparedRelease prepared, String reason) {
        if (!prepared.valid()) { throw new ApiValidationException(prepared.validation()); }
        UUID id = UUID.randomUUID(); long version = mapper.latestVersion(owner.toString(), profile.toString()) + 1; Instant now = Instant.now();
        var nodes = new LinkedHashMap<UUID, NodeSpec>();
        for (var nodeId : prepared.compiled().usedNodeIds()) {
            var node = prepared.nodes().get(nodeId); if (node == null) { throw ApiException.conflict(); } nodes.put(nodeId, node);
        }
        var snapshot = new ReleaseSnapshot(id, profile, version, prepared.definition(), prepared.compiled().sourceVersions(), prepared.compiled().yaml(), now, reason);
        var payload = new StoredRelease(snapshot, new NodeCatalog(nodes), prepared.validation().warnings());
        var row = new ReleaseDO(id.toString(), owner.toString(), profile.toString(), version, hashes.hash(prepared.definition()), prepared.compiled().digest(),
                nodes.size(), reason, documents.write(payload, context(owner, id)), now);
        mapper.insert(row); return view(row);
    }
    private String context(UUID owner, UUID id) { return "profile-release:" + owner + ":" + id; }
    private ReleaseView view(ReleaseDO row) { return new ReleaseView(UUID.fromString(row.id()), UUID.fromString(row.profileId()), Long.toString(row.version()), row.definitionHash(), row.yamlDigest(), row.nodeCount(), row.reason(), row.createTime()); }
}

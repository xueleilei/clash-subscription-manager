package dev.clashsub.node;

import dev.clashsub.common.ApiException;
import dev.clashsub.common.EncryptedDocuments;
import dev.clashsub.common.Page;
import dev.clashsub.parse.RegionHint;
import dev.clashsub.source.SourceRepository;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class NodeRepository {
    private final NodeMapper mapper;
    private final EncryptedDocuments documents;
    private final SourceRepository sources;
    public NodeRepository(NodeMapper mapper, EncryptedDocuments documents, SourceRepository sources) {
        this.mapper = mapper; this.documents = documents; this.sources = sources;
    }
    public NodeSpec connection(UUID owner, UUID id) {
        var row = mapper.find(owner.toString(), id.toString());
        if (row == null) { throw ApiException.missing(); }
        return decode(row);
    }
    public NodeCatalog catalog(UUID owner) {
        var values = new LinkedHashMap<UUID, NodeSpec>();
        for (var row : mapper.all(owner.toString())) { var node = decode(row); values.put(node.id(), node); }
        return new NodeCatalog(values);
    }
    public Page<NodeSummary> list(UUID owner, NodeQuery query) {
        if (query.page() < 1 || query.page() > 100_000 || query.size() < 1 || query.size() > 200) { throw ApiException.invalid("分页参数不正确"); }
        String source = query.sourceId() == null ? null : query.sourceId().toString();
        String protocol = empty(query.protocol()), region = empty(query.region()), name = empty(query.name());
        if (name != null) { name = "%" + name.toLowerCase(Locale.ROOT).replace("=", "==").replace("%", "=%").replace("_", "=_") + "%"; }
        long count = mapper.count(owner.toString(), source, protocol, region, name);
        if (count == 0) { return new Page<>(List.of(), 0, query.page(), query.size()); }
        var rows = mapper.page(owner.toString(), source, protocol, region, name, query.size(), (long) (query.page() - 1) * query.size());
        return new Page<>(rows.stream().map(row -> new NodeSummary(UUID.fromString(row.id()), row.sourceId() == null ? null : UUID.fromString(row.sourceId()),
                row.originalName(), row.protocol(), row.region(), Long.toString(row.snapshotVersion()))).toList(), count, query.page(), query.size());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void replaceSource(UUID owner, UUID source, long snapshot, List<NodeSpec> nodes) {
        sources.require(owner, source);
        var existing = mapper.source(owner.toString(), source.toString());
        var byId = new HashMap<String, NodeDO>(); var byHash = new HashMap<String, NodeDO>();
        existing.forEach(row -> { byId.put(row.id(), row); byHash.put(row.nameHash(), row); });
        var ids = new HashSet<UUID>(); var hashes = new HashSet<String>();
        for (var node : nodes) {
            if (!source.equals(node.sourceId()) || !ids.add(node.id())) { throw ApiException.invalid("节点来源或 ID 不一致"); }
            var key = NodeIdentity.key(source, node.originalName());
            if (!hashes.add(key.nameHash())) { throw ApiException.invalid("同一来源存在重复节点名称"); }
            var sameName = byHash.get(key.nameHash());
            if (sameName != null && (!sameName.originalName().equals(node.originalName()) || !sameName.id().equals(node.id().toString()))) { throw ApiException.conflict(); }
            var previous = byId.get(node.id().toString());
            if (previous != null && !previous.originalName().equals(node.originalName())) { throw ApiException.conflict(); }
            var row = new NodeDO(node.id().toString(), owner.toString(), source.toString(), key.nameHash(), node.originalName(),
                    node.protocol(), RegionHint.fromName(node.originalName()), snapshot, documents.write(node, context(owner.toString(), node.id().toString())));
            if (previous == null) { mapper.insert(row); }
            else if (!decode(previous).equals(node)) { if (mapper.update(row) != 1) { throw ApiException.conflict(); } }
        }
        for (var previous : existing) {
            if (!ids.contains(UUID.fromString(previous.id()))) { mapper.delete(owner.toString(), source.toString(), previous.id()); }
        }
        mapper.markSnapshot(owner.toString(), source.toString(), snapshot);
    }

    private NodeSpec decode(NodeDO row) { return documents.read(row.connectionCipher(), context(row.ownerId(), row.id()), NodeSpec.class); }
    private String context(String owner, String node) { return "node:" + owner + ":" + node; }
    private String empty(String value) { return value == null || value.isEmpty() ? null : value; }
}

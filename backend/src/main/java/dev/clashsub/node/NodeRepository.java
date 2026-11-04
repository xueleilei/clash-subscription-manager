package dev.clashsub.node;

import dev.clashsub.common.ApiException;
import dev.clashsub.common.EncryptedDocuments;
import dev.clashsub.common.Page;
import dev.clashsub.parse.RegionCatalog;
import dev.clashsub.source.SourceRepository;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class NodeRepository {
    private final NodeMapper mapper;
    private final EncryptedDocuments documents;
    private final SourceRepository sources;
    private final RegionCatalog regions;
    public NodeRepository(NodeMapper mapper, EncryptedDocuments documents, SourceRepository sources, RegionCatalog regions) {
        this.mapper = mapper; this.documents = documents; this.sources = sources; this.regions = regions;
    }
    public NodeSpec connection(UUID owner, UUID id) {
        var row = mapper.find(owner.toString(), id.toString());
        if (row == null) { throw ApiException.missing(); }
        return decode(row);
    }
    public NodeDO requireLocal(UUID owner, UUID id) {
        var row = mapper.find(owner.toString(), id.toString());
        if (row == null || row.sourceId() != null) { throw ApiException.missing(); }
        return row;
    }
    public Map<UUID, NodeDO> requireLocalBatch(UUID owner, Set<UUID> ids) {
        if (ids.isEmpty()) { throw ApiException.invalid("请选择手动节点"); }
        var rows = mapper.findLocalBatch(owner.toString(), ids.stream().map(UUID::toString).toList());
        if (rows.size() != ids.size()) { throw ApiException.missing(); }
        var result = new LinkedHashMap<UUID, NodeDO>();
        rows.forEach(row -> result.put(UUID.fromString(row.id()), row));
        return Map.copyOf(result);
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public void updateLocal(UUID owner, NodeSpec node, long expected) {
        if (node.sourceId() != null) { throw ApiException.invalid("本地节点不能带上游来源"); }
        var key = NodeIdentity.key(node.id(), node.originalName());
        var row = new NodeDO(node.id().toString(), owner.toString(), null, key.nameHash(), node.originalName(), node.protocol(),
                regions.fromName(node.originalName()), expected + 1, documents.write(node, context(owner.toString(), node.id().toString())));
        if (mapper.updateLocal(row, expected) != 1) { throw ApiException.conflict(); }
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public void deleteLocal(UUID owner, UUID id, long expected) {
        if (mapper.deleteLocal(owner.toString(), id.toString(), expected) != 1) { throw ApiException.conflict(); }
    }
    public NodeCatalog catalog(UUID owner) {
        var values = new LinkedHashMap<UUID, NodeSpec>();
        for (var row : mapper.all(owner.toString())) { var node = decode(row); values.put(node.id(), node); }
        return new NodeCatalog(values);
    }
    public Map<UUID, Long> countsBySource(UUID owner) {
        var counts = new HashMap<UUID, Long>();
        for (var row : mapper.countsBySource(owner.toString())) { counts.put(UUID.fromString(row.sourceId()), row.nodeCount()); }
        return Map.copyOf(counts);
    }
    public List<NodeSpec> sourceHistory(UUID owner, UUID source) {
        sources.require(owner, source);
        return mapper.source(owner.toString(), source.toString()).stream().map(this::decode).toList();
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public int cleanTombstoneSecrets(UUID owner, java.time.Instant cutoff) {
        int count = 0;
        for (var row : mapper.oldTombstones(owner.toString(), cutoff)) {
            var node = decode(row); if (node.connection().isEmpty() && node.dialerRef() == null) { continue; }
            var identity = new NodeSpec(node.id(), node.sourceId(), node.originalName(), node.protocol(), tools.jackson.databind.node.JsonNodeFactory.instance.objectNode(), null);
            count += mapper.wipeTombstone(owner.toString(), row.id(), documents.write(identity, context(owner.toString(), row.id())));
        }
        return count;
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public void insertLocal(UUID owner, NodeSpec node) {
        if (node.sourceId() != null) { throw ApiException.invalid("本地节点不能带上游来源"); }
        var key = NodeIdentity.key(node.id(), node.originalName());
        mapper.insert(new NodeDO(node.id().toString(), owner.toString(), null, key.nameHash(), node.originalName(), node.protocol(),
                regions.fromName(node.originalName()), 0, documents.write(node, context(owner.toString(), node.id().toString()))));
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public void detachSource(UUID owner, UUID source) {
        for (var node : catalog(owner).nodes().values()) {
            if (!source.equals(node.sourceId())) { continue; }
            var local = new NodeSpec(node.id(), null, node.originalName(), node.protocol(), node.connection(), node.dialerRef());
            if (mapper.detach(owner.toString(), source.toString(), node.id().toString(), documents.write(local, context(owner.toString(), node.id().toString()))) != 1) { throw ApiException.conflict(); }
        }
    }
    public Page<NodeSummary> list(UUID owner, NodeQuery query) {
        if (query.page() < 1 || query.page() > 100_000 || query.size() < 1 || query.size() > 200) { throw ApiException.invalid("分页参数不正确"); }
        if (query.localOnly() && query.sourceId() != null) { throw ApiException.invalid("本地节点筛选不能同时指定上游来源"); }
        String source = query.sourceId() == null ? null : query.sourceId().toString();
        String protocol = empty(query.protocol()), region = empty(query.region()), name = empty(query.name());
        if (name != null) { name = "%" + name.toLowerCase(Locale.ROOT).replace("=", "==").replace("%", "=%").replace("_", "=_") + "%"; }
        long count = mapper.count(owner.toString(), source, protocol, region, name, query.localOnly());
        if (count == 0) { return new Page<>(List.of(), 0, query.page(), query.size()); }
        var rows = mapper.page(owner.toString(), source, protocol, region, name, query.size(), (long) (query.page() - 1) * query.size(), query.localOnly());
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
                    node.protocol(), regions.fromName(node.originalName()), snapshot, documents.write(node, context(owner.toString(), node.id().toString())));
            if (previous == null) { mapper.insert(row); }
            else if (mapper.update(row) != 1) { throw ApiException.conflict(); }
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

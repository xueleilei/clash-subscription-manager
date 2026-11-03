package dev.clashsub.node;

import dev.clashsub.common.*;
import dev.clashsub.importer.DocumentNodeReferences;
import dev.clashsub.parse.ProtocolFieldValidator;
import dev.clashsub.profile.*;
import dev.clashsub.release.ReleaseRepository;
import dev.clashsub.release.ReleaseService;
import dev.clashsub.rule.RuleDefinition;
import dev.clashsub.rule.RuleExpression;
import dev.clashsub.sync.ReferenceReconciler;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.JsonNodeFactory;

@Service
public class LocalNodeService {
    private final OwnerSnapshotReader snapshots;
    private final NodeRepository nodes;
    private final LocalNodeInputParser parser;
    private final ProtocolFieldValidator fields;
    private final ReleaseService publisher;
    private final ReleaseRepository releases;
    private final LocalNodeCommitter committer;
    private final ReferenceReconciler reconciler;
    public LocalNodeService(OwnerSnapshotReader snapshots, NodeRepository nodes, LocalNodeInputParser parser,
            ProtocolFieldValidator fields, ReleaseService publisher, ReleaseRepository releases,
            LocalNodeCommitter committer, ReferenceReconciler reconciler) {
        this.snapshots = snapshots; this.nodes = nodes; this.parser = parser; this.fields = fields;
        this.publisher = publisher; this.releases = releases; this.committer = committer; this.reconciler = reconciler;
    }
    public LocalNodeCommand parse(UUID owner, String text) {
        var parsed = parser.parse(text); TargetRef dialer = null;
        String name = parsed.originalDialerName();
        if (name != null) {
            if (name.equals("DIRECT")) { dialer = TargetRef.direct(); }
            else if (name.equals("REJECT")) { dialer = TargetRef.reject(); }
            else {
                var matches = nodes.catalog(owner).nodes().values().stream().filter(node -> node.originalName().equals(name)).toList();
                if (matches.size() != 1) { throw invalid("dialerRef", "链式代理名称未唯一匹配，请在表单中手动选择目标"); }
                dialer = TargetRef.node(matches.getFirst().id());
            }
        }
        return new LocalNodeCommand(null, parsed.originalName(), parsed.protocol(), parsed.connection(), dialer);
    }
    @Transactional(propagation = Propagation.NEVER)
    public LocalNodeBatchPreview previewBatch(UUID owner, String text) {
        var before = snapshots.read(owner);
        var batch = prepareBatch(before, text);
        return new LocalNodeBatchPreview(Long.toString(before.dataRevision()), batch.stream()
                .map(node -> new LocalNodeCommand(null, node.originalName(), node.protocol(), node.connection(), node.dialerRef())).toList());
    }
    @Transactional(propagation = Propagation.NEVER)
    public LocalNodeBatchResult saveBatch(UUID owner, String text, Long ownerRevision) {
        var before = snapshots.read(owner);
        if (ownerRevision == null || ownerRevision != before.dataRevision()) { throw ApiException.conflict(); }
        return committer.saveBatch(before, prepareBatch(before, text));
    }
    private List<NodeSpec> prepareBatch(OwnerSnapshot before, String text) {
        var parsed = parser.parseAll(text);
        var ids = new LinkedHashMap<String, UUID>();
        for (var node : parsed) { ids.put(node.originalName(), UUID.randomUUID()); }
        var batch = new ArrayList<NodeSpec>();
        for (int index = 0; index < parsed.size(); index++) {
            var node = parsed.get(index); String name = node.originalDialerName(); TargetRef dialer = null;
            if (name != null) {
                if (name.equals("DIRECT")) { dialer = TargetRef.direct(); }
                else if (name.equals("REJECT")) { dialer = TargetRef.reject(); }
                else if (ids.containsKey(name)) { dialer = TargetRef.node(ids.get(name)); }
                else {
                    var matches = before.nodes().nodes().values().stream().filter(existing -> existing.originalName().equals(name)).toList();
                    if (matches.size() != 1) { throw invalid("nodes[" + index + "].dialerRef", "链式代理名称未唯一匹配，请修正节点名称或链式代理引用"); }
                    dialer = TargetRef.node(matches.getFirst().id());
                }
            }
            batch.add(new NodeSpec(ids.get(node.originalName()), null, node.originalName(), node.protocol(), node.connection(), dialer));
        }
        var catalog = new LinkedHashMap<>(before.nodes().nodes()); batch.forEach(node -> catalog.put(node.id(), node));
        var candidate = new OwnerSnapshot(before.ownerId(), before.dataRevision(), before.sources(), new NodeCatalog(catalog), before.profiles());
        validateNodes(batch.stream().map(NodeSpec::id).toList(), candidate);
        return List.copyOf(batch);
    }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public LocalNodeView view(UUID owner, UUID id) {
        var row = nodes.requireLocal(owner, id); var snapshot = snapshots.read(owner);
        return new LocalNodeView(snapshot.nodes().get(id), Long.toString(row.snapshotVersion()), affected(snapshot, id), published(snapshot, id));
    }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public LocalNodeRemoval removal(UUID owner, UUID id) {
        var row = nodes.requireLocal(owner, id); var snapshot = snapshots.read(owner);
        return new LocalNodeRemoval(id, Long.toString(row.snapshotVersion()), Long.toString(snapshot.dataRevision()),
                affected(snapshot, id), published(snapshot, id), dependents(snapshot.nodes(), id));
    }
    @Transactional(propagation = Propagation.NEVER)
    public LocalNodeResult save(UUID owner, UUID id, LocalNodeCommand input) {
        if (id == null && input.expectedVersion() != null) { throw ApiException.invalid("新节点不能设置期望版本"); }
        if (id != null) { checkVersion(nodes.requireLocal(owner, id), input.expectedVersion()); }
        var before = snapshots.read(owner);
        UUID nodeId = id == null ? UUID.randomUUID() : id;
        if (input.connection() == null) { throw ApiException.invalid("请提供节点连接参数"); }
        var connection = input.connection();
        if (connection.toString().getBytes(StandardCharsets.UTF_8).length > LocalNodeInputParser.MAX_BYTES) { throw invalid("connection", "节点参数不能超过 256 KiB"); }
        for (String reserved : List.of("name", "type", "dialer-proxy")) {
            if (connection.has(reserved)) { throw invalid("connection." + reserved, "此字段请通过名称、协议或链式代理选项维护"); }
        }
        var value = connection.deepCopy().put("name", input.name()).put("type", input.protocol());
        var validation = fields.validate(value, "node");
        if (!validation.valid()) { throw new ApiValidationException(validation); }
        var dialer = input.dialerRef();
        if (dialer != null) {
            if (!Set.of(TargetRef.Kind.NODE, TargetRef.Kind.DIRECT, TargetRef.Kind.REJECT).contains(dialer.kind())) { throw invalid("dialerRef", "链式代理必须选择节点、DIRECT 或 REJECT"); }
            if (dialer.kind() == TargetRef.Kind.NODE && before.nodes().get(dialer.id()) == null && !nodeId.equals(dialer.id())) { throw ApiException.missing(); }
        }
        var node = new NodeSpec(nodeId, null, input.name(), input.protocol(), connection, dialer);
        var catalog = new LinkedHashMap<>(before.nodes().nodes()); catalog.put(nodeId, node);
        var candidate = new OwnerSnapshot(owner, before.dataRevision(), before.sources(), new NodeCatalog(catalog), before.profiles());
        validateNodes(List.of(nodeId), candidate);
        var drafts = new LinkedHashMap<UUID, ProfileDocument>();
        if (id != null) { affected(before, id).forEach(profile -> drafts.put(profile, before.profiles().get(profile).draft().document())); }
        return committer.save(before, node, input.expectedVersion(), drafts);
    }
    @Transactional(propagation = Propagation.NEVER)
    public LocalNodeResult delete(UUID owner, UUID id, Long expectedVersion, Long ownerRevision) {
        checkVersion(nodes.requireLocal(owner, id), expectedVersion); var before = snapshots.read(owner);
        if (ownerRevision == null || before.dataRevision() != ownerRevision) { throw ApiException.conflict(); }
        if (!dependents(before.nodes(), id).isEmpty()) { throw new ApiException(409, "A0003", "此节点仍被其他节点用作链式代理，请先解除依赖"); }
        var drafts = new LinkedHashMap<UUID, ProfileDocument>();
        for (var profile : before.profiles().values()) {
            var updated = reconciler.reconcile(profile.draft().document(), Set.of(id)).document();
            if (!updated.equals(profile.draft().document())) { drafts.put(profile.profileId(), updated); }
        }
        return committer.delete(before, id, expectedVersion, drafts);
    }
    private void validateNodes(List<UUID> ids, OwnerSnapshot snapshot) {
        UUID groupId = UUID.randomUUID();
        var group = new GroupDefinition(groupId, "手动节点校验", "select", ids.stream().map(TargetRef::node).toList(), JsonNodeFactory.instance.objectNode());
        var rule = new RuleDefinition(UUID.randomUUID(), new RuleExpression("MATCH", "", null), TargetRef.group(groupId), List.of(), "", List.of());
        var prepared = publisher.prepare(ids.getFirst(), new ProfileDocument(1, "mode: rule\n", List.of(group), List.of(rule), List.of(), List.of()), snapshot);
        if (!prepared.valid()) { throw new ApiValidationException(prepared.validation()); }
    }
    private List<UUID> affected(OwnerSnapshot snapshot, UUID id) {
        return snapshot.profiles().values().stream().filter(profile -> reaches(DocumentNodeReferences.direct(profile.draft().document()), id, snapshot.nodes()))
                .map(ProfileState::profileId).sorted().toList();
    }
    private List<UUID> published(OwnerSnapshot snapshot, UUID id) {
        return snapshot.profiles().values().stream().filter(profile -> profile.published() != null
                && releases.require(snapshot.ownerId(), profile.profileId(), profile.published().id()).nodes().get(id) != null)
                .map(ProfileState::profileId).sorted().toList();
    }
    private List<UUID> dependents(NodeCatalog catalog, UUID id) {
        return catalog.nodes().values().stream().filter(node -> !node.id().equals(id) && reaches(Set.of(node.id()), id, catalog)).map(NodeSpec::id).sorted().toList();
    }
    private boolean reaches(Set<UUID> roots, UUID target, NodeCatalog catalog) {
        var queue = new ArrayDeque<>(roots); var visited = new HashSet<UUID>();
        while (!queue.isEmpty()) {
            var id = queue.removeFirst(); if (id.equals(target)) { return true; } if (!visited.add(id)) { continue; }
            var node = catalog.get(id); if (node != null && node.dialerRef() != null && node.dialerRef().kind() == TargetRef.Kind.NODE) { queue.add(node.dialerRef().id()); }
        }
        return false;
    }
    private void checkVersion(NodeDO row, Long expected) { if (expected == null || expected != row.snapshotVersion()) { throw ApiException.conflict(); } }
    private ApiValidationException invalid(String path, String message) {
        return new ApiValidationException(ValidationReport.of(List.of(Diagnostic.error("INVALID_LOCAL_NODE", path, message))));
    }
}

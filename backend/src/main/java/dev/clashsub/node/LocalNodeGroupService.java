package dev.clashsub.node;

import dev.clashsub.common.ApiException;
import dev.clashsub.common.OwnerRevisionGuard;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LocalNodeGroupService {
    public record Directory(List<LocalNodeGroupMapper.Folder> groups, long ungroupedCount) { }
    public record Saved(UUID id, String name, String version) { }
    private final LocalNodeGroupMapper mapper;
    private final NodeRepository nodes;
    private final OwnerRevisionGuard revisions;

    public LocalNodeGroupService(LocalNodeGroupMapper mapper, NodeRepository nodes, OwnerRevisionGuard revisions) {
        this.mapper = mapper; this.nodes = nodes; this.revisions = revisions;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Directory list(UUID owner) {
        return new Directory(mapper.folders(owner.toString()), mapper.ungroupedCount(owner.toString()));
    }

    @Transactional
    public Saved create(UUID owner, String name) {
        String clean = name(name);
        revisions.lock(owner);
        UUID id = UUID.randomUUID();
        try { mapper.insert(owner.toString(), id.toString(), clean); }
        catch (DuplicateKeyException duplicate) { throw ApiException.invalid("已经有同名的来源分组"); }
        return new Saved(id, clean, "0");
    }

    @Transactional
    public Saved rename(UUID owner, UUID id, Long expected, String name) {
        String clean = name(name);
        revisions.lock(owner);
        checkVersion(owner, id, expected);
        try { mapper.rename(owner.toString(), id.toString(), clean); }
        catch (DuplicateKeyException duplicate) { throw ApiException.invalid("已经有同名的来源分组"); }
        return new Saved(id, clean, Long.toString(expected + 1));
    }

    @Transactional
    public void delete(UUID owner, UUID id, Long expected) {
        revisions.lock(owner);
        checkVersion(owner, id, expected);
        mapper.releaseMembers(owner.toString(), id.toString());
        mapper.delete(owner.toString(), id.toString());
    }

    @Transactional
    public void move(UUID owner, UUID destination, List<UUID> selection) {
        var ids = LocalNodeSelection.validate(selection);
        revisions.lock(owner);
        if (destination != null) { require(owner, destination); }
        nodes.requireLocalBatch(owner, ids);
        mapper.move(owner.toString(), destination == null ? null : destination.toString(), ids.stream().map(UUID::toString).toList());
    }

    /** Call after locking the owner (or incrementing their revision in the node creation transaction). */
    @Transactional(propagation = Propagation.MANDATORY)
    public long require(UUID owner, UUID id) {
        Long value = mapper.version(owner.toString(), id.toString());
        if (value == null) { throw new ApiException(404, "A0004", "来源分组不存在，请重新选择"); }
        return value;
    }
    private void checkVersion(UUID owner, UUID id, Long expected) {
        if (expected == null || expected < 0) { throw ApiException.invalid("请提供来源分组版本"); }
        if (require(owner, id) != expected) { throw ApiException.conflict(); }
    }
    private String name(String name) {
        String clean = name == null ? "" : name.strip();
        if (clean.isEmpty() || clean.codePointCount(0, clean.length()) > 64 || clean.codePoints().anyMatch(Character::isISOControl)) {
            throw ApiException.invalid("分组名称需为 1 至 64 个字符，且不能包含控制字符");
        }
        if (clean.equals("未分组") || clean.equals("全部分组")) { throw ApiException.invalid("此名称已用于系统分组，请换一个名称"); }
        return clean;
    }
}

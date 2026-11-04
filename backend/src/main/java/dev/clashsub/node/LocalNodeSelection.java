package dev.clashsub.node;

import dev.clashsub.common.ApiException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

final class LocalNodeSelection {
    private LocalNodeSelection() { }
    static Set<UUID> validate(List<UUID> selection) {
        if (selection == null || selection.isEmpty() || selection.size() > 500) {
            throw ApiException.invalid("每次请选择 1 至 500 个手动节点");
        }
        var ids = new HashSet<UUID>();
        for (var id : selection) {
            if (id == null || !ids.add(id)) { throw ApiException.invalid("节点 ID 不能为空或重复"); }
        }
        return Set.copyOf(ids);
    }
}

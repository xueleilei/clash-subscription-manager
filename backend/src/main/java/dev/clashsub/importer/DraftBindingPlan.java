package dev.clashsub.importer;
import dev.clashsub.common.OwnerSnapshot;
import dev.clashsub.node.NodeSpec;
import dev.clashsub.profile.*;
import java.util.*;
public record DraftBindingPlan(Map<UUID, ProfileDocument> drafts, List<NodeSpec> copies) {
    public DraftBindingPlan { drafts = Map.copyOf(drafts); copies = List.copyOf(copies); }
    public static DraftBindingPlan build(OwnerSnapshot snapshot, UUID local, UUID remote) {
        var reachable = new HashSet<UUID>(); var queue = new ArrayDeque<UUID>();
        snapshot.profiles().values().forEach(profile -> queue.addAll(DocumentNodeReferences.direct(profile.draft().document())));
        while (!queue.isEmpty()) {
            UUID id = queue.removeFirst(); if (!reachable.add(id)) { continue; } var node = snapshot.nodes().get(id);
            if (node != null && node.dialerRef() != null && node.dialerRef().kind() == TargetRef.Kind.NODE) { queue.add(node.dialerRef().id()); }
        }
        var ids = new LinkedHashMap<UUID, UUID>(); ids.put(local, remote); boolean changed;
        do {
            changed = false;
            for (var node : snapshot.nodes().nodes().values()) {
                if (node.sourceId() == null && reachable.contains(node.id()) && !ids.containsKey(node.id()) && node.dialerRef() != null
                        && node.dialerRef().kind() == TargetRef.Kind.NODE && ids.containsKey(node.dialerRef().id())) {
                    ids.put(node.id(), UUID.randomUUID()); changed = true;
                }
            }
        } while (changed);
        var copies = new ArrayList<NodeSpec>();
        ids.forEach((oldId, newId) -> {
            if (oldId.equals(local)) { return; } var old = snapshot.nodes().get(oldId);
            copies.add(new NodeSpec(newId, null, old.originalName(), old.protocol(), old.connection(), DocumentNodeReferences.replace(old.dialerRef(), ids)));
        });
        var drafts = new LinkedHashMap<UUID, ProfileDocument>();
        snapshot.profiles().values().forEach(profile -> {
            var updated = DocumentNodeReferences.replace(profile.draft().document(), ids);
            if (!updated.equals(profile.draft().document())) { drafts.put(profile.profileId(), updated); }
        });
        return new DraftBindingPlan(drafts, copies);
    }
}

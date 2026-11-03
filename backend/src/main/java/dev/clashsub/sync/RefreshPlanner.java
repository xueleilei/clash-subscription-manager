package dev.clashsub.sync;

import dev.clashsub.common.*;
import dev.clashsub.profile.*;
import dev.clashsub.release.*;
import dev.clashsub.source.SourceDO;
import java.util.*;
import org.springframework.stereotype.Component;

/** Shared refresh/removal reconciliation. Call outside a database transaction. */
@Component
public class RefreshPlanner {
    private final ReleaseService publisher;
    private final ReferenceReconciler reconciler;
    public RefreshPlanner(ReleaseService publisher, ReferenceReconciler reconciler) { this.publisher = publisher; this.reconciler = reconciler; }
    public RefreshProposal plan(OwnerSnapshot before, OwnerSnapshot candidate, SourceDO source, NodeDiff diff, UUID attempt, UUID decision, byte[] body, boolean forcePublished) {
        UUID sourceId = UUID.fromString(source.id());
        var drafts = new LinkedHashMap<UUID, ProfileDocument>(); var releases = new LinkedHashMap<UUID, PreparedRelease>(); var changes = new LinkedHashMap<UUID, ChangeSummary.ProfileChange>();
        for (var profile : before.profiles().values().stream().sorted(Comparator.comparing(value -> value.profileId().toString())).toList()) {
            var draft = reconciler.reconcile(profile.draft().document(), diff.removed());
            if (!draft.document().equals(profile.draft().document())) { drafts.put(profile.profileId(), draft.document()); }
            var rejected = new LinkedHashSet<>(draft.rejectedRuleIds()); var empty = new LinkedHashSet<UUID>(); int removed = draft.removedMembers();
            if (draft.removedMembers() > 0 || !draft.rejectedRuleIds().isEmpty()) { empty.addAll(draft.emptyGroupIds()); }
            var old = profile.published();
            if (old != null && old.sourceVersions().containsKey(sourceId)) {
                var published = reconciler.reconcile(old.definition(), diff.removed());
                var prepared = publisher.prepare(profile.profileId(), published.document(), candidate);
                if (!prepared.valid()) { throw new ApiValidationException(prepared.validation()); }
                if (forcePublished || !prepared.compiled().yaml().equals(old.yaml()) || !published.document().equals(old.definition())) { releases.put(profile.profileId(), prepared); }
                removed += published.removedMembers(); rejected.addAll(published.rejectedRuleIds());
                if (published.removedMembers() > 0 || !published.rejectedRuleIds().isEmpty()) { empty.addAll(published.emptyGroupIds()); }
            }
            if (removed > 0 || !rejected.isEmpty() || releases.containsKey(profile.profileId())) {
                changes.put(profile.profileId(), new ChangeSummary.ProfileChange(removed, List.copyOf(rejected), List.copyOf(empty)));
            }
        }
        var summary = new ChangeSummary(diff.added().size(), diff.updated().size(), diff.removed().size(), changes);
        return new RefreshProposal(before, source, attempt, decision, body, diff, drafts, releases, summary);
    }
}

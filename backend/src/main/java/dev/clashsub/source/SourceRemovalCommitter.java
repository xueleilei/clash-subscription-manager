package dev.clashsub.source;
import dev.clashsub.common.*;
import dev.clashsub.importer.PreviewRepository;
import dev.clashsub.node.NodeRepository;
import dev.clashsub.sync.*;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
@Component
public class SourceRemovalCommitter {
    public record Applied(RemovalResult result, List<UUID> cancelledAttempts) { }
    private final OwnerRevisionGuard revisions;
    private final SourceRepository sources;
    private final NodeRepository nodes;
    private final RefreshCommitter committer;
    private final RefreshStateStore state;
    private final PreviewRepository previews;
    public SourceRemovalCommitter(OwnerRevisionGuard revisions, SourceRepository sources, NodeRepository nodes, RefreshCommitter committer, RefreshStateStore state, PreviewRepository previews) {
        this.revisions = revisions; this.sources = sources; this.nodes = nodes; this.committer = committer; this.state = state; this.previews = previews;
    }
    @Transactional public Applied commit(UUID preview, RefreshProposal proposal, RemovalMode mode) {
        var before = proposal.before(); UUID owner = before.ownerId(), source = UUID.fromString(proposal.source().id());
        revisions.compareAndIncrement(owner, before.dataRevision()); sources.lock(owner, source); committer.verifyInputs(before);
        var cancelled = state.cancelSource(owner, source);
        int count = (int) before.nodes().nodes().values().stream().filter(node -> source.equals(node.sourceId())).count();
        if (mode == RemovalMode.KEEP_LOCAL) { nodes.detachSource(owner, source); }
        else { nodes.replaceSource(owner, source, proposal.source().snapshotVersion() + 1, List.of()); }
        committer.applyProfiles(proposal, mode == RemovalMode.KEEP_LOCAL ? "SOURCE_TO_LOCAL" : "SOURCE_REMOVED");
        sources.remove(owner, source, proposal.source().configVersion(), proposal.source().snapshotVersion()); previews.consume(owner, preview);
        var affected = new TreeSet<UUID>(); affected.addAll(proposal.drafts().keySet()); affected.addAll(proposal.releases().keySet());
        return new Applied(new RemovalResult(source, mode, count, List.copyOf(affected)), cancelled);
    }
}

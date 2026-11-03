package dev.clashsub.source;
import java.util.List;
import java.util.UUID;
public record RemovalResult(UUID sourceId, RemovalMode mode, int nodeCount, List<UUID> affectedProfiles) {
    public RemovalResult { affectedProfiles = List.copyOf(affectedProfiles); }
}

package dev.clashsub.node;
import java.util.List;
import java.util.UUID;
public record LocalNodeResult(UUID id, String version, List<UUID> affectedProfiles) { }

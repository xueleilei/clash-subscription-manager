package dev.clashsub.sync;
import java.time.Instant;
import java.util.UUID;
public record EmptyDecisionView(UUID id, UUID sourceId, String configVersion, String snapshotVersion, String state, Instant createdAt) { }

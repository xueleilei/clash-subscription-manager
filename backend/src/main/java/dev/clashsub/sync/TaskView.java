package dev.clashsub.sync;
import java.time.Instant;
import java.util.UUID;
public record TaskView(UUID id, UUID sourceId, String status, Trigger trigger, Instant createdAt, Instant updatedAt, RefreshOutcome outcome) { }

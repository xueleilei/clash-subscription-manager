package dev.clashsub.sync;
import java.time.Instant;
public record JobDO(String id, String ownerId, String sourceId, String status, String triggerKind, Instant leaseUntil) { }

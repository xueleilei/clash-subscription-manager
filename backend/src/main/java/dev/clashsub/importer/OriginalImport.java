package dev.clashsub.importer;
import java.time.Instant;
import java.util.UUID;
public record OriginalImport(UUID id, UUID profileId, String yaml, Instant createdAt) {
    @Override public String toString() { return "OriginalImport[id=" + id + "]"; }
}

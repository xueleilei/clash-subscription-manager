package dev.clashsub.source;

import java.util.UUID;

public record SourceState(UUID id, long configVersion, long snapshotVersion) { }

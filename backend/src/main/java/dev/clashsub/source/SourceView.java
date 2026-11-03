package dev.clashsub.source;

import dev.clashsub.parse.InputFormat;
import java.time.Instant;
import java.util.UUID;

public record SourceView(UUID id, String name, String configVersion, String snapshotVersion, boolean enabled,
        int refreshIntervalSeconds, InputFormat inputFormat, String hostHint, String syncStatus,
        Instant lastAttemptTime, Instant lastSuccessTime, Instant nextRefreshTime, String lastErrorCode) { }

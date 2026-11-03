package dev.clashsub.node;

import java.util.UUID;

public record NodeQuery(UUID sourceId, String protocol, String region, String name, int page, int size, boolean localOnly) {
    public NodeQuery(UUID sourceId, String protocol, String region, String name, int page, int size) {
        this(sourceId, protocol, region, name, page, size, false);
    }
}

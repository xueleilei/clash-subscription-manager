package dev.clashsub.node;

import java.util.UUID;

public record NodeQuery(UUID sourceId, String protocol, String region, String name, int page, int size) { }

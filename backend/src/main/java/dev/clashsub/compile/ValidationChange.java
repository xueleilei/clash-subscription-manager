package dev.clashsub.compile;

/** Field path and reason only: values may contain credentials. */
public record ValidationChange(String path, String reason) { }

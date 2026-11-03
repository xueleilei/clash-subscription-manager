package dev.clashsub.release;

public record PublishedYaml(String yaml, String digest, String filename) {
    @Override public String toString() { return "PublishedYaml[digest=" + digest + "]"; }
}

package dev.clashsub.rule;

public record RuleTextDocument(String expectedVersion, String text, int ruleCount, String ownerRevision) {
    @Override public String toString() { return "RuleTextDocument[ruleCount=" + ruleCount + "]"; }
}

package dev.clashsub.rule;

import java.util.UUID;

/** value keeps the complete nested match expression; providerId resolves RULE-SET names. */
public record RuleExpression(String type, String value, UUID providerId) { }

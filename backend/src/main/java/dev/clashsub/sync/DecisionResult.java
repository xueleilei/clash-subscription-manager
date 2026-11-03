package dev.clashsub.sync;
import java.util.UUID;
public record DecisionResult(UUID decisionId, String state, RefreshOutcome outcome) { }

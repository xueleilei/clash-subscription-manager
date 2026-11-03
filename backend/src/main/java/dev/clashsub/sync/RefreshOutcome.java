package dev.clashsub.sync;
import dev.clashsub.common.ValidationReport;
import java.util.UUID;
public record RefreshOutcome(UUID attemptId, UUID sourceId, String status, UUID decisionId, ChangeSummary changes, ValidationReport report) { }

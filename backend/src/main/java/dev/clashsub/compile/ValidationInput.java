package dev.clashsub.compile;

import dev.clashsub.common.ValidationReport;
import java.util.List;

public record ValidationInput(String yaml, List<String> resources, List<ValidationChange> changes, ValidationReport report) {
    public ValidationInput { resources = List.copyOf(resources); changes = List.copyOf(changes); }
    @Override public String toString() { return "ValidationInput[valid=" + report.valid() + "]"; }
}

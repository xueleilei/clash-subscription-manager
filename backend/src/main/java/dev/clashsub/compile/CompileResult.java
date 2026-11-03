package dev.clashsub.compile;

import dev.clashsub.common.ValidationReport;

public record CompileResult(CompiledProfile compiled, ValidationReport report) {
    public boolean valid() { return compiled != null && report.valid(); }
}

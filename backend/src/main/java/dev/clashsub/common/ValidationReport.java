package dev.clashsub.common;

import java.util.List;
import java.util.stream.Stream;

public record ValidationReport(List<Diagnostic> errors, List<Diagnostic> warnings) {
    public ValidationReport { errors = List.copyOf(errors); warnings = List.copyOf(warnings); }
    public boolean valid() { return errors.isEmpty(); }
    public static ValidationReport ok() { return new ValidationReport(List.of(), List.of()); }
    public static ValidationReport of(List<Diagnostic> diagnostics) {
        return new ValidationReport(diagnostics.stream().filter(d -> d.severity() == Diagnostic.Severity.ERROR).toList(),
                diagnostics.stream().filter(d -> d.severity() == Diagnostic.Severity.WARNING).toList());
    }
    public ValidationReport merge(ValidationReport other) {
        return new ValidationReport(Stream.concat(errors.stream(), other.errors.stream()).toList(),
                Stream.concat(warnings.stream(), other.warnings.stream()).toList());
    }
}

package dev.clashsub.common;

public class ApiValidationException extends ApiException {
    private final ValidationReport report;
    public ApiValidationException(ValidationReport report) { super(422, "A0005", "配置校验未通过，请查看字段诊断"); this.report = report; }
    public ValidationReport report() { return report; }
}

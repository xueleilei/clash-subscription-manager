package dev.clashsub.rule;

import dev.clashsub.common.Diagnostic;
import dev.clashsub.common.ValidationReport;
import dev.clashsub.source.FetchPolicyException;
import dev.clashsub.source.SourceRequestValidator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class ProviderValidator {
    public ValidationReport validate(List<ProviderDefinition> providers) {
        var issues = new ArrayList<Diagnostic>(); var names = new HashSet<String>();
        for (int i = 0; i < providers.size(); i++) {
            var provider = providers.get(i); String path = "providers[" + i + "]";
            if (provider.name() == null || provider.name().isBlank() || provider.name().length() > 256 || provider.name().contains(",")
                    || provider.name().chars().anyMatch(c -> c < 32 || c == 127) || !names.add(provider.name())) {
                issues.add(Diagnostic.error("INVALID_PROVIDER_NAME", path + ".name", "规则集名称为空、重复或含不支持的字符"));
            }
            if (!"http".equals(provider.type())) { issues.add(Diagnostic.error("UNSUPPORTED_PROVIDER_TYPE", path + ".type", "第一版仅支持 HTTP 远程规则集")); }
            try { SourceRequestValidator.url(provider.url()); }
            catch (FetchPolicyException exception) { issues.add(Diagnostic.error("INVALID_PROVIDER_URL", path + ".url", "规则集地址必须是有效的 HTTP / HTTPS URL")); }
            if (!Set.of("domain", "ipcidr", "classical").contains(provider.behavior() == null ? "" : provider.behavior())
                    || !Set.of("yaml", "text", "mrs").contains(provider.format() == null ? "" : provider.format())
                    || "mrs".equals(provider.format()) && "classical".equals(provider.behavior())) {
                issues.add(Diagnostic.error("INVALID_PROVIDER_FORMAT", path + ".format", "规则集行为和格式组合不受支持；mrs 仅支持 domain / ipcidr"));
            }
            if (provider.interval() < 1 || provider.interval() > Integer.MAX_VALUE) { issues.add(Diagnostic.error("INVALID_PROVIDER_INTERVAL", path + ".interval", "更新周期必须是正整数秒")); }
            for (var entry : provider.options().properties()) {
                String field = entry.getKey(); var value = entry.getValue(); String fieldPath = path + ".options." + field;
                if (field.equals("proxy")) {
                    if (!value.isString() || !Set.of("DIRECT", "REJECT").contains(value.asString(""))) {
                        issues.add(Diagnostic.error("UNSUPPORTED_PROVIDER_REFERENCE", fieldPath, "规则集下载代理暂只支持 DIRECT / REJECT，命名代理引用需要先迁移"));
                    }
                } else if (field.equals("size-limit")) {
                    if (!value.isIntegralNumber() || value.asLong(-1) < 0) { issues.add(invalid(fieldPath)); }
                } else if (field.equals("header")) {
                    if (!value.isObject() || value.size() > 50) { issues.add(invalid(fieldPath)); continue; }
                    var headerNames = new HashSet<String>();
                    for (var header : value.properties()) {
                        boolean valid = header.getKey().matches("[!#$%&'*+.^_`|~0-9a-zA-Z-]+")
                                && headerNames.add(header.getKey().toLowerCase(java.util.Locale.ROOT)) && header.getValue().isArray() && header.getValue().size() <= 20;
                        for (var text : header.getValue()) {
                            valid &= text.isString() && text.asString("").length() <= 8192 && text.asString("").chars().noneMatch(c -> c < 32 || c == 127);
                        }
                        if (!valid) { issues.add(invalid(fieldPath)); }
                    }
                } else { issues.add(Diagnostic.error("UNSUPPORTED_PROVIDER_OPTION", fieldPath, "规则集下载参数不受支持，缓存路径由管理器生成")); }
            }
        }
        return ValidationReport.of(issues);
    }
    private Diagnostic invalid(String path) { return Diagnostic.error("INVALID_PROVIDER_OPTION", path, "规则集下载参数类型或范围不正确"); }
}

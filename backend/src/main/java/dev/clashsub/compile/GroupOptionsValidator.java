package dev.clashsub.compile;

import dev.clashsub.common.Diagnostic;
import dev.clashsub.common.ValidationReport;
import dev.clashsub.profile.GroupDefinition;
import dev.clashsub.source.FetchPolicyException;
import dev.clashsub.source.SourceRequestValidator;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class GroupOptionsValidator {
    ValidationReport validate(List<GroupDefinition> groups) {
        var issues = new ArrayList<Diagnostic>();
        for (int i = 0; i < groups.size(); i++) {
            var group = groups.get(i);
            for (var option : group.options().properties()) {
                String name = option.getKey(), path = "groups[" + i + "].options." + name; var value = option.getValue();
                if (Set.of("lazy", "disable-udp", "hidden").contains(name)) {
                    if (!value.isBoolean()) { issues.add(invalid(path)); }
                } else if (Set.of("interval", "timeout", "max-failed-times", "tolerance").contains(name)) {
                    if (!value.isIntegralNumber() || value.asLong(-1) < 0 || value.asLong(Long.MAX_VALUE) > Integer.MAX_VALUE
                            || name.equals("tolerance") && !"url-test".equals(group.type())) { issues.add(invalid(path)); }
                } else if (name.equals("url")) {
                    try { if (!value.isString()) { throw new FetchPolicyException("INVALID"); } SourceRequestValidator.url(value.asString()); }
                    catch (FetchPolicyException exception) { issues.add(invalid(path)); }
                } else if (name.equals("expected-status")) {
                    if (!(value.isString() || value.isIntegralNumber()) || !value.asString("").matches("\\*|[0-9]{3}(?:-[0-9]{3})?(?:/[0-9]{3}(?:-[0-9]{3})?)*")) { issues.add(invalid(path)); }
                } else if (name.equals("icon")) {
                    if (!value.isString()) { issues.add(invalid(path)); }
                } else if (name.equals("empty-fallback")) {
                    if (!value.isString() || !value.asString("").equals("REJECT")) { issues.add(Diagnostic.error("MANAGED_EMPTY_FALLBACK", path, "空组回退由管理器固定为 REJECT")); }
                } else if (name.equals("default-selected")) {
                    if (!"select".equals(group.type()) || !value.isString() || !Set.of("DIRECT", "REJECT").contains(value.asString(""))) {
                        issues.add(Diagnostic.error("UNSUPPORTED_GROUP_REFERENCE", path, "命名默认成员引用尚不能安全改写，请通过成员顺序设置默认选择"));
                    }
                } else { issues.add(Diagnostic.error("UNSUPPORTED_GROUP_OPTION", path, "此代理组参数不受支持，成员由管理器维护")); }
            }
        }
        return ValidationReport.of(issues);
    }
    private Diagnostic invalid(String path) { return Diagnostic.error("INVALID_GROUP_OPTION", path, "代理组参数类型或取值不正确"); }
}

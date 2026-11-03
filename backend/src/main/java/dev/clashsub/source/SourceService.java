package dev.clashsub.source;

import dev.clashsub.common.ApiException;
import dev.clashsub.common.OwnerRevisionGuard;
import dev.clashsub.parse.InputFormat;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SourceService {
    private final SourceRepository repository;
    private final OwnerRevisionGuard revisions;
    private final UpstreamFetcher fetcher;
    public SourceService(SourceRepository repository, OwnerRevisionGuard revisions, UpstreamFetcher fetcher) {
        this.repository = repository; this.revisions = revisions; this.fetcher = fetcher;
    }

    public List<SourceView> list(UUID owner) { return repository.list(owner).stream().map(this::view).toList(); }
    public SourceRequest credentials(UUID owner, UUID id) { return repository.request(repository.require(owner, id)); }
    public List<String> proxyNames() { return fetcher.proxyNames(); }

    @Transactional
    public SourceView save(UUID owner, SourceCommand command) {
        var previous = command.id() == null ? null : repository.require(owner, command.id());
        if (previous != null && (command.expectedVersion() == null || command.expectedVersion() != previous.configVersion())) { throw ApiException.conflict(); }
        if (previous == null && command.expectedVersion() != null) { throw ApiException.invalid("新来源不能设置期望版本"); }
        UUID id = previous == null ? UUID.randomUUID() : UUID.fromString(previous.id());
        var old = previous == null ? new SourceRequest("", Map.of(), "", "") : repository.request(previous);
        String name = command.name() == null && previous != null ? previous.name() : command.name();
        if (name == null || name.isBlank() || name.length() > 128) { throw ApiException.invalid("来源名称必须为 1–128 个字符"); }
        int interval = command.refreshIntervalSeconds() == null ? (previous == null ? 21_600 : previous.refreshIntervalSeconds()) : command.refreshIntervalSeconds();
        if (interval < 60 || interval > 31 * 86_400) { throw ApiException.invalid("刷新周期必须为 60 秒到 31 天"); }
        var request = new SourceRequest(command.url() == null ? old.url() : command.url(), command.headers() == null ? old.headers() : command.headers(),
                command.userAgent() == null ? old.userAgent() : command.userAgent(), command.outboundProxy() == null ? old.outboundProxy() : command.outboundProxy());
        try { SourceRequestValidator.validate(request); }
        catch (FetchPolicyException exception) { throw ApiException.invalid("订阅地址或请求头格式不正确"); }
        if (!request.outboundProxy().isEmpty() && !fetcher.proxyNames().contains(request.outboundProxy())) { throw ApiException.invalid("未配置此可信出站代理"); }
        if (placeholder(request.url()) || placeholder(request.userAgent()) || request.headers().values().stream().anyMatch(this::placeholder)) {
            throw ApiException.invalid("不能把脱敏占位符保存为凭据；未修改的秘密字段请省略");
        }
        InputFormat format = command.inputFormat() == null ? (previous == null ? InputFormat.AUTO : InputFormat.valueOf(previous.inputFormat())) : command.inputFormat();
        var row = new SourceDO(id.toString(), owner.toString(), name, previous == null ? 1 : previous.configVersion(),
                previous == null ? 0 : previous.snapshotVersion(), command.enabled() == null ? previous == null || previous.enabled() : command.enabled(),
                interval, format.name(), repository.encrypt(owner, id, request), "PENDING", null, null, Instant.now(), null, null, null);
        revisions.compareAndIncrement(owner, revisions.current(owner));
        if (previous == null) { repository.insert(row); } else { repository.update(row, previous.configVersion()); }
        return view(repository.require(owner, id));
    }

    private SourceView view(SourceDO row) {
        String host = SourceRequestValidator.url(repository.request(row).url()).getHost();
        return new SourceView(UUID.fromString(row.id()), row.name(), Long.toString(row.configVersion()), Long.toString(row.snapshotVersion()),
                row.enabled(), row.refreshIntervalSeconds(), InputFormat.valueOf(row.inputFormat()), host, row.syncStatus(),
                row.lastAttemptTime(), row.lastSuccessTime(), row.nextRefreshTime(), row.lastErrorCode());
    }
    private boolean placeholder(String value) { return value.matches("[*•●]{3,}") || value.contains("••••") || value.equalsIgnoreCase("[redacted]"); }
}

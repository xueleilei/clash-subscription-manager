package dev.clashsub.compile;

import dev.clashsub.common.Diagnostic;
import dev.clashsub.common.ValidationReport;
import dev.clashsub.parse.SafeYaml;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class MihomoValidator {
    private static final int OUTPUT_LIMIT = 64 * 1024;
    private static final Semaphore SLOTS = new Semaphore(2, true);
    private final Path executable;
    private final Duration timeout;
    private final ValidationCopyFactory copies;

    @Autowired
    public MihomoValidator(@Value("${clash.mihomo.executable:/opt/mihomo/mihomo}") String executable,
            @Value("${clash.mihomo.resources:/opt/mihomo/resources}") String resources, SafeYaml yaml) {
        this(Path.of(executable), Path.of(resources), Duration.ofSeconds(10), yaml);
    }

    public MihomoValidator(Path executable, Path resources, Duration timeout, SafeYaml yaml) {
        this.executable = executable.toAbsolutePath();
        this.timeout = timeout;
        this.copies = new ValidationCopyFactory(yaml, resources);
    }

    public ValidationReport validate(String outputYaml) {
        boolean acquired = false;
        Path work = null;
        Process process = null;
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            acquired = SLOTS.tryAcquire(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!acquired) { return error("CORE_BUSY", "内核检查繁忙，请重试"); }
            work = Files.createTempDirectory("clash-validation-");
            var input = copies.create(outputYaml, work);
            if (!input.report().valid()) { return input.report(); }
            var builder = new ProcessBuilder(executable.toString(), "-t", "-d", work.toString(), "-f", "-")
                    .directory(work.toFile()).redirectErrorStream(true);
            builder.environment().clear();
            builder.environment().put("PATH", "/usr/bin:/bin");
            builder.environment().put("HOME", work.toString());
            builder.environment().put("TMPDIR", work.toString());
            builder.environment().put("LANG", "C.UTF-8");
            process = builder.start();
            final Process running = process;
            var exceeded = new AtomicBoolean();
            var reader = executor.submit(() -> {
                try (var output = running.getInputStream()) {
                    int count = 0;
                    byte[] buffer = new byte[4096];
                    for (int read; (read = output.read(buffer)) != -1;) {
                        count += read;
                        if (count > OUTPUT_LIMIT) { exceeded.set(true); terminate(running); return; }
                    }
                } catch (IOException ignored) { /* Process termination closes its pipes. */ }
            });
            var writer = executor.submit(() -> {
                try (var stdin = running.getOutputStream()) { stdin.write(input.yaml().getBytes(StandardCharsets.UTF_8)); }
                catch (IOException ignored) { /* Core exit code is the authoritative outcome. */ }
            });
            if (!running.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                terminate(running);
                return error(exceeded.get() ? "CORE_OUTPUT_LIMIT" : "CORE_TIMEOUT",
                        exceeded.get() ? "内核检查输出超过限制" : "内核检查超时");
            }
            reader.get(1, TimeUnit.SECONDS);
            writer.get(1, TimeUnit.SECONDS);
            if (exceeded.get()) { return error("CORE_OUTPUT_LIMIT", "内核检查输出超过限制"); }
            if (running.exitValue() != 0) { return error("CORE_REJECTED", "内核拒绝配置，请检查节点参数、规则和代理组引用"); }
            return new ValidationReport(List.of(), input.changes().stream()
                    .map(change -> Diagnostic.warning("VALIDATION_COPY", change.path(), change.reason())).toList());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return error("CORE_INTERRUPTED", "内核检查已中断");
        } catch (Exception exception) { return error("CORE_UNAVAILABLE", "内核检查不可用，请检查安装和资源"); }
        finally {
            if (process != null) { terminate(process); }
            executor.shutdownNow();
            if (acquired) { SLOTS.release(); }
            if (work != null) {
                try (var entries = Files.walk(work)) {
                    for (var entry : entries.sorted(Comparator.reverseOrder()).toList()) { Files.deleteIfExists(entry); }
                } catch (IOException ignored) { /* Private random directory; no secret configuration file was written. */ }
            }
        }
    }

    private static void terminate(Process process) {
        process.descendants().forEach(child -> { if (child.isAlive()) { child.destroyForcibly(); } });
        if (process.isAlive()) { process.destroyForcibly(); }
    }
    private ValidationReport error(String code, String message) {
        return new ValidationReport(List.of(Diagnostic.error(code, "$", message)), List.of());
    }
}

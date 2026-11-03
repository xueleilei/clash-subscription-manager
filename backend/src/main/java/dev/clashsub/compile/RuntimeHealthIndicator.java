package dev.clashsub.compile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("runtime")
public class RuntimeHealthIndicator implements HealthIndicator {
    private final MihomoValidator core;
    private final Path executable;
    private final Path resources;
    private boolean verified;
    public RuntimeHealthIndicator(MihomoValidator core,
            @Value("${clash.mihomo.executable:/opt/mihomo/mihomo}") String executable,
            @Value("${clash.mihomo.resources:/opt/mihomo/resources}") String resources) {
        this.core = core; this.executable = Path.of(executable); this.resources = Path.of(resources);
    }
    @Override public synchronized Health health() {
        if (!Files.isRegularFile(executable) || !Files.isExecutable(executable)
                || List.of("geoip.dat", "geosite.dat", "country.mmdb", "GeoLite2-ASN.mmdb").stream().anyMatch(name -> !Files.isReadable(resources.resolve(name)))) {
            return Health.down().build();
        }
        if (!verified) { verified = core.validate("proxies: []\nproxy-groups: []\nrules: ['MATCH,REJECT']\n").valid(); }
        return (verified ? Health.up() : Health.down()).build();
    }
}

package dev.clashsub.parse;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class RegionConfiguration {
    private static final int MAX_BYTES = 256 * 1024;
    private static final Path LOCAL_FILE = Path.of("config/node-regions.yaml");

    @Bean public RegionCatalog nodeRegions(@Value("${clash.node-regions-file:}") String configured) throws IOException {
        Path file = configured.isBlank() ? LOCAL_FILE : Path.of(configured);
        try (InputStream input = !configured.isBlank() || Files.exists(file) ? Files.newInputStream(file)
                : RegionConfiguration.class.getResourceAsStream("/node-regions.yaml")) {
            if (input == null) { throw new IOException("找不到默认节点地区配置"); }
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) { throw new IOException("节点地区配置文件不能超过 256 KiB"); }
            return RegionCatalog.fromYaml(new String(bytes, StandardCharsets.UTF_8));
        }
    }
}

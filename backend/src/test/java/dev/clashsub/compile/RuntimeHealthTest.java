package dev.clashsub.compile;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class RuntimeHealthTest {
    @Test void missingCoreOrResourcesNeverReportReady() {
        var core = mock(MihomoValidator.class);
        var health = new RuntimeHealthIndicator(core, "/missing-core", "/missing-resources");
        assertThat(health.health().getStatus().getCode()).isEqualTo("DOWN");
        verifyNoInteractions(core);
    }
}

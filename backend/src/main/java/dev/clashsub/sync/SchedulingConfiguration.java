package dev.clashsub.sync;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class SchedulingConfiguration {
    @Bean public Clock applicationClock() { return Clock.systemUTC(); }
}

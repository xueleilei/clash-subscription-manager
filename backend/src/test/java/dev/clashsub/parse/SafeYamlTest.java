package dev.clashsub.parse;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SafeYamlTest {
    private final SafeYaml yaml = new SafeYaml();

    @Test void rejectsDuplicateKeysTagsAndOversizedInput() {
        assertThatThrownBy(() -> yaml.load("a: 1\na: 2\n")).isInstanceOf(YamlInputException.class);
        assertThatThrownBy(() -> yaml.load("a: !!java.lang.ProcessBuilder []")).isInstanceOf(YamlInputException.class);
        assertThatThrownBy(() -> yaml.load("[".repeat(55) + "0" + "]".repeat(55))).isInstanceOf(YamlInputException.class);
        assertThatThrownBy(() -> yaml.load("a: " + "x".repeat(10 * 1024 * 1024))).isInstanceOf(YamlInputException.class);
    }

    @Test void jsonCompatibleTreeRoundTripsWithoutChangingStrings() {
        var parsed = yaml.load("mixed-port: 7890\nproxies: [{name: '香港 ', password: '00123'}]\n");
        assertThat(yaml.load(yaml.dump(parsed))).isEqualTo(parsed);
    }
}

package dev.clashsub.parse;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegionCatalogTest {
    @Test void identifiesMacauAndMoreRegionsWithoutGuessingFromProse() throws Exception {
        var regions = new RegionConfiguration().nodeRegions("");
        for (String name : java.util.List.of("🇲🇴 澳门家宽 01丨5x MO", "澳門 02", "Macao 03", "mo-04", "中国澳门", "MO01")) {
            assertThat(regions.fromName(name)).as(name).isEqualTo("MO");
        }
        assertThat(regions.fromName("巴黎 01")).isEqualTo("FR");
        assertThat(regions.fromName("Sydney 02")).isEqualTo("AU");
        assertThat(regions.fromName("🇧🇷 01")).isEqualTo("BR");
        assertThat(regions.fromName("印度尼西亚 01")).isEqualTo("ID");
        assertThat(regions.fromName("flag wins: 巴黎 🇲🇴")).isEqualTo("MO");
        assertThat(regions.fromName("this is a node in my network")).isEqualTo("UNKNOWN");
        assertThat(regions.fromName("model-parisian")).isEqualTo("UNKNOWN");
        assertThat(regions.fromName("node hk relay")).isEqualTo("HK");
    }
    @Test void externalFileAloneControlsOptionsOrderLabelsAndLiteralMatching(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("regions.yaml");
        Files.writeString(file, """
                groups:
                  - name: 临时分组
                    regions:
                      - {code: XZ, name: 自定义地区, aliases: ['edge.+zone']}
                      - {code: XY, name: 替代地区}
                """);
        var catalog = new RegionConfiguration().nodeRegions(file.toString());
        assertThat(catalog.groups().getFirst().regions()).extracting(RegionCatalog.Option::code).containsExactly("XZ", "XY");
        assertThat(catalog.fromName("EDGE.+ZONE 1")).isEqualTo("XZ");
        assertThat(catalog.fromName("edge-any-zone")).isEqualTo("UNKNOWN");
        assertThat(catalog.fromName("🇽🇿 1")).isEqualTo("XZ");
        assertThat(catalog.fromName("澳门")).isEqualTo("UNKNOWN");
        assertThatThrownBy(() -> new RegionConfiguration().nodeRegions(directory.resolve("missing.yaml").toString())).isInstanceOf(java.io.IOException.class);
    }
    @Test void rejectsDuplicateCodesAmbiguousAliasesAndTypos() {
        String prefix = "groups: [{name: 一组, regions: [{code: AA, name: 甲, aliases: [Gateway]}, ";
        for (String row : java.util.List.of("{code: AA, name: 乙}", "{code: BB, name: 乙, aliases: [gateway]}",
                "{code: BB, name: 乙, alias: [错误键]}", "{code: NO, name: 未加引号的代码}")) {
            assertThatThrownBy(() -> RegionCatalog.fromYaml(prefix + row + "]}]")).isInstanceOf(IllegalArgumentException.class);
        }
    }
}

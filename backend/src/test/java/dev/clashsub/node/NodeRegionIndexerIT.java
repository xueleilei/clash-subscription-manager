package dev.clashsub.node;

import dev.clashsub.parse.RegionConfiguration;
import dev.clashsub.support.MySqlIntegrationTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import static org.assertj.core.api.Assertions.assertThat;

class NodeRegionIndexerIT extends MySqlIntegrationTest {
    @Autowired DataSource dataSource;

    @Test void configChangeReclassifiesExistingNodesAcrossPagesWithoutModifyingOtherColumns(@TempDir Path directory) throws Exception {
        var file = directory.resolve("regions.yaml");
        try (var connection = dataSource.getConnection()) {
            var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            String definition = jdbc.queryForObject("SHOW CREATE TABLE node", (rs, index) -> rs.getString(2));
            jdbc.execute(java.util.Objects.requireNonNull(definition).replaceFirst("CREATE TABLE", "CREATE TEMPORARY TABLE"));
            try {
                var rows = new ArrayList<Object[]>();
                for (int n = 0; n < 503; n++) {
                    rows.add(new Object[]{new UUID(0, n + 1).toString(), new UUID(1, n % 2).toString(),
                            n % 2 == 0 ? null : new UUID(2, n).toString(), n == 501 ? "澳门节点" : "栀子线路", n == 502 ? 0 : 1});
                }
                jdbc.batchUpdate("INSERT INTO node(id,owner_id,source_id,original_name,is_active,name_hash,protocol,region,snapshot_version,connection_cipher) VALUES(?,?,?,?,?,'fixture','ss','HK',7,'fixture-cipher')", rows);
                String unchanged = "SELECT id,owner_id,source_id,name_hash,original_name,protocol,is_active,snapshot_version,connection_cipher,create_time,update_time FROM node ORDER BY id";
                var before = jdbc.queryForList(unchanged);
                for (String code : java.util.List.of("XZ", "XY")) {
                    Files.writeString(file, "groups: [{name: 一组, regions: [{code: " + code + ", name: 自定义地区, aliases: [栀子]}]}]");
                    new NodeRegionIndexer(jdbc, new RegionConfiguration().nodeRegions(file.toString())).run(null);
                    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM node WHERE region=?", Long.class, code)).isEqualTo(501);
                    assertThat(jdbc.queryForObject("SELECT region FROM node WHERE original_name='澳门节点'", String.class)).isEqualTo("UNKNOWN");
                    assertThat(jdbc.queryForObject("SELECT region FROM node WHERE is_active=0", String.class)).isEqualTo("HK");
                    assertThat(jdbc.queryForList(unchanged)).isEqualTo(before);
                }
            } finally { jdbc.execute("DROP TEMPORARY TABLE node"); }
        }
    }
}

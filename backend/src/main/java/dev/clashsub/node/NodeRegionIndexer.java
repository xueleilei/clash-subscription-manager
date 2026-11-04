package dev.clashsub.node;

import dev.clashsub.parse.RegionCatalog;
import java.util.ArrayList;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Reclassifies existing active names on startup without reading credentials or changing node versions. */
@Component
public class NodeRegionIndexer implements ApplicationRunner {
    private record Row(String id, String owner, String name, String region) { }
    private static final int PAGE_SIZE = 500;
    private final JdbcTemplate jdbc;
    private final RegionCatalog regions;
    public NodeRegionIndexer(JdbcTemplate jdbc, RegionCatalog regions) { this.jdbc = jdbc; this.regions = regions; }

    @Override public void run(ApplicationArguments arguments) {
        String after = "";
        while (true) {
            var rows = jdbc.query("SELECT id,owner_id,original_name,region FROM node WHERE id>? AND is_active=1 ORDER BY id LIMIT ?",
                    (rs, index) -> new Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)), after, PAGE_SIZE);
            var changes = new ArrayList<Object[]>();
            for (var row : rows) {
                String region = regions.fromName(row.name());
                if (!region.equals(row.region())) { changes.add(new Object[]{region, row.id(), row.owner(), row.name(), row.region()}); }
            }
            if (!changes.isEmpty()) {
                jdbc.batchUpdate("UPDATE node SET region=?,update_time=update_time WHERE id=? AND owner_id=? AND original_name=? AND region=? AND is_active=1", changes);
            }
            if (rows.size() < PAGE_SIZE) { return; }
            after = rows.getLast().id();
        }
    }
}

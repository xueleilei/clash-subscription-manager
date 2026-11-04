package dev.clashsub.node;

import dev.clashsub.auth.CurrentUser;
import dev.clashsub.common.ApiResult;
import dev.clashsub.common.Page;
import dev.clashsub.parse.RegionCatalog;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/nodes")
public class NodeController {
    private final NodeRepository nodes;
    private final CurrentUser user;
    private final RegionCatalog regions;
    public NodeController(NodeRepository nodes, CurrentUser user, RegionCatalog regions) { this.nodes = nodes; this.user = user; this.regions = regions; }
    @GetMapping("/regions") public ApiResult<List<RegionCatalog.Group>> regions() { user.requireId(); return ApiResult.success(regions.groups()); }
    @GetMapping public ApiResult<Page<NodeSummary>> list(@RequestParam(required = false) UUID sourceId,
            @RequestParam(required = false) String protocol, @RequestParam(required = false) String region,
            @RequestParam(required = false) String name, @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "50") int size,
            @RequestParam(defaultValue = "false") boolean localOnly) {
        return ApiResult.success(nodes.list(user.requireId(), new NodeQuery(sourceId, protocol, region, name, page, size, localOnly)));
    }
    @GetMapping("/{id}/connection") public ResponseEntity<ApiResult<NodeSpec>> connection(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResult.success(nodes.connection(user.requireId(), id)));
    }
}

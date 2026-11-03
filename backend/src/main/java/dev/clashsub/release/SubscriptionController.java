package dev.clashsub.release;

import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class SubscriptionController {
    private final SubscriptionTokenService tokens;
    public SubscriptionController(SubscriptionTokenService tokens) { this.tokens = tokens; }
    @GetMapping("/sub/{token}")
    public ResponseEntity<String> download(@PathVariable String token) {
        var output = tokens.readPublished(token);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType("application/yaml;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(output.filename()).build().toString()).body(output.yaml());
    }
}

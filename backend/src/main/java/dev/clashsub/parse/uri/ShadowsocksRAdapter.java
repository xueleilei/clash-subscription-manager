package dev.clashsub.parse.uri;

import dev.clashsub.parse.Base64SubscriptionDecoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;

public class ShadowsocksRAdapter implements UriNodeAdapter {
    @Override public boolean supports(String scheme) { return scheme.equals("ssr"); }
    @Override public ParsedNodeResult parse(String uri, int line) { return UriSupport.parse(line, () -> {
        String decoded = Base64SubscriptionDecoder.decode(uri.substring(uri.indexOf("://") + 3));
        int queryAt = decoded.indexOf("/?");
        String body = queryAt < 0 ? decoded : decoded.substring(0, queryAt);
        var query = UriSupport.query(queryAt < 0 ? null : decoded.substring(queryAt + 2));
        UriSupport.allowed(query, Set.of("remarks", "group", "obfsparam", "protoparam"));
        var pieces = new ArrayList<String>();
        for (int index = 0; index < 5; index++) {
            int colon = body.lastIndexOf(':');
            if (colon < 0) { throw new IllegalArgumentException(); }
            pieces.add(body.substring(colon + 1)); body = body.substring(0, colon);
        }
        Collections.reverse(pieces);
        String host = body.contains(":") && !body.startsWith("[") ? "[" + body + "]" : body;
        var parts = UriSupport.parts("ssr://" + host + ":" + pieces.get(0));
        var node = UriSupport.base(parts, "ssr", -1).put("protocol", pieces.get(1)).put("cipher", pieces.get(2))
                .put("obfs", pieces.get(3)).put("password", Base64SubscriptionDecoder.decode(pieces.get(4)));
        if (query.containsKey("remarks") && !query.get("remarks").isEmpty()) { node.put("name", Base64SubscriptionDecoder.decode(query.get("remarks"))); }
        if (query.containsKey("obfsparam")) { node.put("obfs-param", optional(query.get("obfsparam"))); }
        if (query.containsKey("protoparam")) { node.put("protocol-param", optional(query.get("protoparam"))); }
        if (query.containsKey("group")) { optional(query.get("group")); }
        return node;
    }); }
    private String optional(String value) { return value.isEmpty() ? "" : Base64SubscriptionDecoder.decode(value); }
}

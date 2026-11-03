package dev.clashsub.parse.uri;

import dev.clashsub.parse.Base64SubscriptionDecoder;
import java.util.HashMap;
import java.util.Set;

public class ShadowsocksAdapter implements UriNodeAdapter {
    @Override public boolean supports(String scheme) { return scheme.equals("ss"); }
    @Override public ParsedNodeResult parse(String uri, int line) { return UriSupport.parse(line, () -> {
        String rest = uri.substring(uri.indexOf("://") + 3);
        int fragment = rest.indexOf('#');
        String body = fragment < 0 ? rest : rest.substring(0, fragment), label = fragment < 0 ? "" : rest.substring(fragment);
        boolean legacy = !body.contains("@");
        if (legacy) {
            uriCheckLegacy(body);
            String decoded = Base64SubscriptionDecoder.decode(body);
            int at = decoded.lastIndexOf('@');
            if (at < 1) { throw new IllegalArgumentException(); }
            rest = java.net.URLEncoder.encode(decoded.substring(0, at), java.nio.charset.StandardCharsets.UTF_8)
                    .replace("+", "%20") + "@" + decoded.substring(at + 1) + label;
        }
        var parts = UriSupport.parts("ss://" + rest);
        String auth = parts.auth();
        boolean encoded = !auth.contains(":");
        if (encoded) { auth = Base64SubscriptionDecoder.decode(auth); }
        int colon = auth.indexOf(':');
        if (colon < 1) { throw new IllegalArgumentException(); }
        String cipher = auth.substring(0, colon);
        if ((encoded || legacy) && cipher.startsWith("2022-")) { throw new IllegalArgumentException(); }
        var node = UriSupport.base(parts, "ss", -1).put("cipher", cipher).put("password", auth.substring(colon + 1));
        UriSupport.allowed(parts.query(), Set.of("plugin"));
        if (parts.query().containsKey("plugin")) {
            var args = UriSupport.splitPlugin(parts.query().get("plugin"));
            String plugin = args.getFirst();
            plugin = Set.of("obfs-local", "simple-obfs").contains(plugin) ? "obfs" : plugin;
            if (!Set.of("obfs", "v2ray-plugin").contains(plugin)) { throw new IllegalArgumentException(); }
            node.put("plugin", plugin);
            var options = node.putObject("plugin-opts");
            var seen = new HashMap<String, String>();
            if (plugin.equals("v2ray-plugin")) { options.put("mode", "websocket"); }
            for (String arg : args.subList(1, args.size())) {
                var pieces = arg.split("=", 2);
                String key = pieces[0], value = pieces.length > 1 ? pieces[1] : "true";
                if (seen.putIfAbsent(key, value) != null) { throw new IllegalArgumentException(); }
                if (plugin.equals("obfs")) {
                    if (key.equals("obfs")) { options.put("mode", value); }
                    else if (key.equals("obfs-host")) { options.put("host", value); }
                    else { throw new IllegalArgumentException(); }
                } else if (Set.of("mode", "host", "path").contains(key)) { options.put(key, value); }
                else if (Set.of("tls", "mux", "skip-cert-verify").contains(key)) { options.put(key, UriSupport.bool(value)); }
                else { throw new IllegalArgumentException(); }
            }
        }
        return node;
    }); }
    private void uriCheckLegacy(String body) { if (body.contains("?") || body.contains("/@")) { throw new IllegalArgumentException(); } }
}

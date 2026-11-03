package dev.clashsub.parse.uri;

public class VlessAdapter implements UriNodeAdapter {
    @Override public boolean supports(String scheme) { return scheme.equals("vless"); }
    @Override public ParsedNodeResult parse(String uri, int line) { return UriSupport.parse(line, () -> {
        var parts = UriSupport.parts(uri);
        var node = UriSupport.base(parts, "vless", -1).put("uuid", parts.auth());
        UriSupport.transport(node, parts.query(), false);
        return node;
    }); }
}

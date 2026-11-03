package dev.clashsub.parse.uri;

public class TrojanAdapter implements UriNodeAdapter {
    @Override public boolean supports(String scheme) { return scheme.equals("trojan"); }
    @Override public ParsedNodeResult parse(String uri, int line) { return UriSupport.parse(line, () -> {
        var parts = UriSupport.parts(uri);
        var node = UriSupport.base(parts, "trojan", -1).put("password", parts.auth());
        UriSupport.transport(node, parts.query(), true);
        return node;
    }); }
}

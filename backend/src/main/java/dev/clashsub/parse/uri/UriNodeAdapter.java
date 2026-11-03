package dev.clashsub.parse.uri;

public interface UriNodeAdapter {
    boolean supports(String scheme);
    ParsedNodeResult parse(String uri, int line);
}

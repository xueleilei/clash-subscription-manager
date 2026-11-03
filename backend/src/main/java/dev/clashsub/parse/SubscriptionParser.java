package dev.clashsub.parse;

import dev.clashsub.common.Diagnostic;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;

@Component
public class SubscriptionParser {
    private final ClashYamlParser clash;
    private final UriSubscriptionParser uris = new UriSubscriptionParser();
    public SubscriptionParser(ClashYamlParser clash) { this.clash = clash; }
    public ParseResult parse(byte[] body, InputFormat format) {
        if (body == null || body.length == 0 || body.length > SafeYaml.MAX_BYTES) {
            return ParseResult.failure(Diagnostic.error("INVALID_SUBSCRIPTION", "$", "订阅为空或超过 10 MiB"));
        }
        final String text;
        try { text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString(); }
        catch (CharacterCodingException exception) { return ParseResult.failure(Diagnostic.error("INVALID_ENCODING", "$", "订阅必须是有效 UTF-8")); }
        String clean = text.startsWith("\uFEFF") ? text.substring(1) : text;
        if (format == InputFormat.CLASH_YAML) { return clash.parse(clean); }
        if (format == InputFormat.URI_LIST) { return uris.parse(clean); }
        if (format == InputFormat.AUTO) {
            if (looksLikeYaml(clean)) { return clash.parse(clean); }
            if (looksLikeUris(clean)) { return uris.parse(clean); }
        }
        try {
            String decoded = Base64SubscriptionDecoder.decode(clean);
            if (looksLikeYaml(decoded)) { return clash.parse(decoded); }
            if (looksLikeUris(decoded)) { return uris.parse(decoded); }
        } catch (IllegalArgumentException ignored) { /* Report a safe format error below. */ }
        return ParseResult.failure(Diagnostic.error("UNSUPPORTED_FORMAT", "$", "未识别的订阅格式或 Base64 编码"));
    }

    private boolean looksLikeUris(String text) { return java.util.regex.Pattern.compile("(?m)^[ \\t]*[a-zA-Z][a-zA-Z0-9+.-]*://").matcher(text).find(); }
    private boolean looksLikeYaml(String text) {
        String leading = text.stripLeading();
        return leading.startsWith("{") || leading.startsWith("---")
                || java.util.regex.Pattern.compile("(?m)^[ \\t]*[^\\s:#]+:(?:[ \\t]|$)").matcher(text).find();
    }
}

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
    public SubscriptionParser(ClashYamlParser clash) { this.clash = clash; }
    public ParseResult parse(byte[] body, InputFormat format) {
        if (body == null || body.length == 0 || body.length > SafeYaml.MAX_BYTES) {
            return ParseResult.failure(Diagnostic.error("INVALID_SUBSCRIPTION", "$", "订阅为空或超过 10 MiB"));
        }
        final String text;
        try { text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString(); }
        catch (CharacterCodingException exception) { return ParseResult.failure(Diagnostic.error("INVALID_ENCODING", "$", "订阅必须是有效 UTF-8")); }
        if (format == InputFormat.AUTO || format == InputFormat.CLASH_YAML) {
            return clash.parse(text.startsWith("\uFEFF") ? text.substring(1) : text);
        }
        return ParseResult.failure(Diagnostic.error("UNSUPPORTED_FORMAT", "$", "未识别的订阅格式"));
    }
}

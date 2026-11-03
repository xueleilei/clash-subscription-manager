package dev.clashsub.importer;
import java.util.UUID;
public record BindingChoice(Mode mode, UUID remoteNodeId) { public enum Mode { LOCAL, REMOTE } }

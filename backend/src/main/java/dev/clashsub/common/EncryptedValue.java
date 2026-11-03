package dev.clashsub.common;

public record EncryptedValue(int version, String keyId, String nonce, String ciphertext) {
    @Override public String toString() { return "EncryptedValue[version=" + version + ", keyId=" + keyId + "]"; }
}

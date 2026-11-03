package dev.clashsub.common;

/** Intentionally omits provider exceptions and secret-bearing input. */
public class SecretStorageException extends RuntimeException {
    public SecretStorageException() { super("Secret storage unavailable: check persistent encryption key and data integrity"); }
}

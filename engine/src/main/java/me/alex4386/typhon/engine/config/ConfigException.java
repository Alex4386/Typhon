package me.alex4386.typhon.engine.config;

/** An invalid world or volcano definition; the message names the file and the key path. */
public class ConfigException extends RuntimeException {
    public ConfigException(String message) {
        super(message);
    }

    public ConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}

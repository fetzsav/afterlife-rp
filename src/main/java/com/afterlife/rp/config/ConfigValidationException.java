package com.afterlife.rp.config;

import java.util.List;

/** Thrown when a configuration section fails startup validation (master plan §11). */
public class ConfigValidationException extends RuntimeException {

    private final List<String> errors;

    public ConfigValidationException(List<String> errors) {
        super("Configuration invalid: " + String.join("; ", errors));
        this.errors = List.copyOf(errors);
    }

    public List<String> errors() {
        return errors;
    }
}

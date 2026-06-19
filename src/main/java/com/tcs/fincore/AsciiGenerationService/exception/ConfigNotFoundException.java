package com.tcs.fincore.AsciiGenerationService.exception;

public class ConfigNotFoundException extends RuntimeException {
    public ConfigNotFoundException(Long configId) {
        super("ASCII config not found for id: " + configId);
    }
}
package com.tcs.fincore.AsciiGenerationService.exception;

public class PathValidationException extends RuntimeException {
    public PathValidationException(String message) {
        super(message);
    }

    public PathValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
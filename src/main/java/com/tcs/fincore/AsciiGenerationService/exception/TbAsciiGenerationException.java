package com.tcs.fincore.AsciiGenerationService.exception;

public class TbAsciiGenerationException extends RuntimeException {
    public TbAsciiGenerationException(String message) {
        super(message);
    }

    public TbAsciiGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}

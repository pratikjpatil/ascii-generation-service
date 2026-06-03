package com.tcs.fincore.AsciiGenerationService.exception;

public class InvalidReportRequestException extends RuntimeException {
    public InvalidReportRequestException(String message) {
        super(message);
    }

    public InvalidReportRequestException(String message, Throwable cause) {
        super(message, cause);
    }
}

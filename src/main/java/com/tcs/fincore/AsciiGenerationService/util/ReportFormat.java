package com.tcs.fincore.AsciiGenerationService.util;

public enum ReportFormat {
    PDF(".pdf"),
    EXCEL(".xlsx"),
    PSV(".psv");
    private final String extension;
    ReportFormat(String extension) {
        this.extension = extension;
    }
    public String ext() {
        return extension;
    }
}
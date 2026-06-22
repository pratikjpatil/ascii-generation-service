package com.tcs.fincore.AsciiGenerationService.dto;

public enum TbAsciiGenerationArtifact {
    TB_ASCII,
    TB_ASCII_REPORT;

    public static TbAsciiGenerationArtifact from(String value) {
        return TbAsciiGenerationArtifact.valueOf(value == null ? "" : value.trim().toUpperCase());
    }
}

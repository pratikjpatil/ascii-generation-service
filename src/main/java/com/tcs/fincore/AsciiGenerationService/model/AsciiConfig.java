package com.tcs.fincore.AsciiGenerationService.model;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

@Entity
@Table(name = "ASCII_CONFIG")
@Data
public class AsciiConfig {

    @Id
    @Column(name = "ID")
    private Long id;

    @Column(name = "REPORT_ID")
    private String reportId;

    @Column(name = "FILE_TYPE")
    private String fileType;

    @Column(name = "OUTPUT_FILE_NAME")
    private String outputFileName;

    @Column(name = "OUTPUT_FIRST_LINE")
    private String outputFirstLine;

    @Column(name = "OUTPUT_END_LINE")
    private String outputEndLine;

    @Column(name = "INPUT_HEAD_COL")
    private Integer inputHeadCol;

    @Column(name = "INPUT_HEAD_REGEX")
    private String inputHeadRegex;

    @Column(name = "OUTPUT_HEAD_COL_PAD")
    private String outputHeadColPad;

    @Column(name = "AMOUNT_COL_SEQ")
    private String amountColSeq;

    @Column(name = "OUTPUT_AMT_COL_LOGIC")
    private String outputAmtColLogic;

    @Column(name = "OUTPUT_AMT_DECIMAL")
    private String outputAmtDecimal;

    @Column(name = "OUTPUT_AMT_COL_PAD")
    private String outputAmtColPad;

    @Column(name = "OUTPUT_AMT_SIGN")
    private String outputAmtSign;

    @Column(name = "OUTPUT_PER_LINE_HEAD")
    private Integer outputPerLineHead;

    @Column(name = "OUTPUT_INCLUDE_CONDITION")
    private String outputIncludeCondition;

    @Column(name = "OUTPUT_LAYOUT")
    private String outputLayout;
}
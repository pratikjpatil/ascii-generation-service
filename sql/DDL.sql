-- =============================================================================
-- TABLE 1: RB_PARSED_TEMPLATE
-- Purpose: Staging table that holds the parsed and flattened rows from them Report Builder JSON template for a given run. Populated by
--          parse procedure via FORALL bulk insert, and consumed by
--          the TB generation procedure.
-- =============================================================================

CREATE TABLE "RB_PARSED_TEMPLATE"
(
    "RUN_ID"          VARCHAR2(50)   NOT NULL,
    "HEAD_CODE"       VARCHAR2(100),
    "HEAD_DESC"       VARCHAR2(4000),
    "VAR_NAME"        VARCHAR2(100),
    "LOGIC_TYPE"      VARCHAR2(50),
    "ROW_FILTER"      VARCHAR2(20),
    "IS_REVERSE"      CHAR(1),
    "CGL"             VARCHAR2(50),
    "CURRENCY_FILTER" VARCHAR2(10)   DEFAULT NULL
);

-- Primary index: used in GL_BALANCE joins inside the generation procedure
CREATE INDEX "IDX_META_STAGE_RUN_CGL"
    ON "RB_PARSED_TEMPLATE" ("RUN_ID", "CGL");

-- Secondary index: supports HEAD_CODE/VAR_NAME grouped aggregation
CREATE INDEX "IDX_META_STAGE_RUN_HEAD"
    ON "RB_PARSED_TEMPLATE" ("RUN_ID", "HEAD_CODE", "VAR_NAME");

ALTER TABLE "RB_PARSED_TEMPLATE" MODIFY ("RUN_ID" NOT NULL ENABLE);

-- Table-level comment
COMMENT ON TABLE "RB_PARSED_TEMPLATE" IS
    'Staging table holding rows parsed from the active Report Builder JSON template. Populated by Parse template procedure';

-- Column-level comments
COMMENT ON COLUMN "RB_PARSED_TEMPLATE"."RUN_ID" IS
    'All rows for one report run share the same RUN_ID ';

COMMENT ON COLUMN "RB_PARSED_TEMPLATE"."HEAD_CODE" IS
    'Report line-item code extracted from the HEAD/FIELD column of a DATA row in the JSON template. Uniquely identifies a report heading';

COMMENT ON COLUMN "RB_PARSED_TEMPLATE"."HEAD_DESC" IS
    'Readable description of the report line item, extracted from the DESC column of the same DATA row. ';

COMMENT ON COLUMN "RB_PARSED_TEMPLATE"."VAR_NAME" IS
    'Name of the formula variable from the JSON template (e.g., Swing_Neg, SP, DN, Reverse). Used to determine LOGIC_TYPE and IS_REVERSE via pattern matching in the parser. Also used as the PARTITION BY key in SWING_CALC to compute GROUP_TOTAL for swing-sign logic.';

COMMENT ON COLUMN "RB_PARSED_TEMPLATE"."LOGIC_TYPE" IS
    'Controls which rows survive the final filter in the generation CTE. Valid values: NORMAL - Always included; CP - Credit-positive/negative variable, always included; DN - Debit-negative/positive variable, always included; SN - Swing-negative: row included only when GROUP_TOTAL < 0; SP     - Swing-positive: row included only when GROUP_TOTAL > 0.';

COMMENT ON COLUMN "RB_PARSED_TEMPLATE"."ROW_FILTER" IS
    'Sign guard applied at the branch-level aggregation stage (HAVING clause in RAW_AGG CTE). Derived from the INR_BALANCE or BALANCE filter operator in the JSON variable filters block. Valid values: POS - Include only branches where SUM(INR_BALANCE) > 0; NEG - Include only branches where SUM(INR_BALANCE) < 0; NULL - No sign restriction, all non-zero balances are included.';

COMMENT ON COLUMN "RB_PARSED_TEMPLATE"."IS_REVERSE" IS
    'Flag indicating whether the display amount sign should be flipped. Y = multiply CGL_BAL by -1 before output (i.e., show the inverse); N = display the raw aggregated balance. Derived from VAR_NAME containing the substring "Reverse" or the alias "R".';

COMMENT ON COLUMN "RB_PARSED_TEMPLATE"."CGL" IS
   'Flattened from the CGL filter array inside the JSON variable. ';

COMMENT ON COLUMN "RB_PARSED_TEMPLATE"."CURRENCY_FILTER" IS
    'Extracted from the CURRENCY filter block in the JSON variable definition. NULL means no currency restriction is applied. ';




-- =============================================================================
-- TABLE 2: ASCII_CONFIG
-- Purpose: Master configuration table for the ASCII report generation service.
-- =============================================================================

CREATE TABLE "ASCII_CONFIG"
(
    "ID"                      NUMBER           NOT NULL,
    "REPORT_ID"               VARCHAR2(50),
    "FILE_TYPE"               VARCHAR2(10),
    "OUTPUT_FILE_NAME"        VARCHAR2(100),
    "OUTPUT_FIRST_LINE"       VARCHAR2(100),
    "OUTPUT_END_LINE"         VARCHAR2(100),
    "INPUT_HEAD_COL"          NUMBER,
    "INPUT_HEAD_REGEX"        VARCHAR2(200),
    "OUTPUT_HEAD_COL_PAD"     VARCHAR2(50),
    "AMOUNT_COL_SEQ"          VARCHAR2(50),
    "OUTPUT_AMT_COL_LOGIC"    VARCHAR2(4000),
    "OUTPUT_AMT_DECIMAL"      VARCHAR2(4000),
    "OUTPUT_AMT_COL_PAD"      VARCHAR2(4000),
    "OUTPUT_AMT_SIGN"         VARCHAR2(4000),
    "OUTPUT_PER_LINE_HEAD"    NUMBER,
    "CREATED_DATE"            TIMESTAMP(6)     DEFAULT SYSDATE,
    "OUTPUT_INCLUDE_CONDITION" VARCHAR2(4000)
);

CREATE UNIQUE INDEX "SYS_C0010374" ON "ASCII_CONFIG" ("ID");

ALTER TABLE "ASCII_CONFIG" ADD PRIMARY KEY ("ID") USING INDEX ENABLE;

-- Table-level comment
COMMENT ON TABLE "ASCII_CONFIG" IS
    'Configuration table for the normal ASCII generation. Each row configures one report type: how PSV input files from HDFS are parsed, how amounts are transformed, and how the fixed-width ASCII output file is formatted and named.';

-- Column-level comments
COMMENT ON COLUMN "ASCII_CONFIG"."ID" IS
    'Primary key. Unique numeric identifier for the ASCII configuration record. ';

COMMENT ON COLUMN "ASCII_CONFIG"."REPORT_ID" IS
    'Logical report identifier';

COMMENT ON COLUMN "ASCII_CONFIG"."FILE_TYPE" IS
    'Extension appended to the output ASCII file name. If null or empty, no extension is appended. ';

COMMENT ON COLUMN "ASCII_CONFIG"."OUTPUT_FILE_NAME" IS
    'Base name segment of the output ASCII file. Combined with the branch code and report date to form the final file name';

COMMENT ON COLUMN "ASCII_CONFIG"."OUTPUT_FIRST_LINE" IS
    'Static prefix string written as the first line (header record) of every output ASCII file. The branch code and report date extracted from the PSV input header are appended to this value, followed by the character "F".';

COMMENT ON COLUMN "ASCII_CONFIG"."OUTPUT_END_LINE" IS
    'Static string written as the last line of every output ASCII file. ';

COMMENT ON COLUMN "ASCII_CONFIG"."INPUT_HEAD_COL" IS
    'One-based column index of the HEAD/account-code field within the pipe-delimited (PSV) input row. ';

COMMENT ON COLUMN "ASCII_CONFIG"."INPUT_HEAD_REGEX" IS
    'Regular expression pattern applied to the HEAD column value (at position INPUT_HEAD_COL) to determine whether a PSV input row is a valid data record. Rows that do not match are silently skipped.';

COMMENT ON COLUMN "ASCII_CONFIG"."OUTPUT_HEAD_COL_PAD" IS
    'Expression that controls formatting of the HEAD column in the output ASCII line. Typically specifies total width, alignment and left-pad character. Example: "5L0" to left-pad with zeroes to 5 characters.';

COMMENT ON COLUMN "ASCII_CONFIG"."AMOUNT_COL_SEQ" IS
    'Expression defining which pipe-delimited column(s) contain amount values and how to select them. Supported patterns: "<n>" - Single amount column at 1-based index n; "<n1> and <n2>"   - Two amount columns both included as separate output segments; "<n1> else <n2>"  - Use column n1 if non-zero, otherwise fall back to column n2.';

COMMENT ON COLUMN "ASCII_CONFIG"."OUTPUT_AMT_COL_LOGIC" IS
    'Expression applied after amount extraction to perform mathematical transformations such as sign inversion, scaling, or conditional overrides per head code or column index. Null means the raw extracted amount is used without transformation.';

COMMENT ON COLUMN "ASCII_CONFIG"."OUTPUT_AMT_DECIMAL" IS
    'Expression controlling decimal precision of the output amount after math logic is applied. Example: "["^.*"][5][remove_decimal]" to remove decimal places from 5th psv col. Null means no decimal adjustment.';

COMMENT ON COLUMN "ASCII_CONFIG"."OUTPUT_AMT_COL_PAD" IS
    'Expression that formats the final amount into a fixed-width string for the ASCII output column. Example: "["^.*"][4][19,0]" for 19-character amount field padded with 0 and without decimal point and for input psv col 4.';

COMMENT ON COLUMN "ASCII_CONFIG"."OUTPUT_AMT_SIGN" IS
    'Applied after padding. ["^.*"][3][NO_SIGN] means for 3rd col dont show sign. Null means no sign character is added.';

COMMENT ON COLUMN "ASCII_CONFIG"."OUTPUT_PER_LINE_HEAD" IS
    'Number of HEAD (account code) segments to place on a single output line. ';

COMMENT ON COLUMN "ASCII_CONFIG"."CREATED_DATE" IS
    'Timestamp of Record Creation';

COMMENT ON COLUMN "ASCII_CONFIG"."OUTPUT_INCLUDE_CONDITION" IS
    'Expression that gates whether a transformed amount value is included in the ASCII output line. Rows where no amount passes this condition are suppressed entirely. Eg. ["^.*"][3][!=0]';


-- =============================================================================
-- TABLE 3: ASCII_CONFIG_TB
-- Purpose: Configuration for Trial Balance (TB) report.
--          Stores JSON template parsing hints used by parse procedure
-- =============================================================================

CREATE TABLE "ASCII_CONFIG_TB"
(
    "REPORT_ID"       VARCHAR2(50),
    "HEAD_PATTERN"    VARCHAR2(100),
    "DESC_PATTERN"    VARCHAR2(100),
    "SKIP_PATTERN"    VARCHAR2(100),
    "ASCII_HEADER_ID" VARCHAR2(20),
    "ASCII_FOOTER_ID" VARCHAR2(20),
    "FILE_IDENTIFIER" VARCHAR2(20),
    "ID"              NUMBER         NOT NULL
);

CREATE UNIQUE INDEX "SYS_C0012907" ON "ASCII_CONFIG_TB" ("ID");

ALTER TABLE "ASCII_CONFIG_TB" ADD PRIMARY KEY ("ID") USING INDEX ENABLE;

-- Table-level comment
COMMENT ON TABLE "ASCII_CONFIG_TB" IS
    'configuration for Trial Balance ASCII and report and for template parser procedure.';

-- Column-level comments
COMMENT ON COLUMN "ASCII_CONFIG_TB"."ID" IS
    'Primary key. Unique numeric identifier for the TB-specific ASCII configuration record.';

COMMENT ON COLUMN "ASCII_CONFIG_TB"."REPORT_ID" IS
    'Logical report identifier the active template in RB_REPORT_TEMPLATE';

COMMENT ON COLUMN "ASCII_CONFIG_TB"."HEAD_PATTERN" IS
    'Oracle regular expression (case-insensitive) matched against each column name in the JSON template to identify the HEAD/account-code column index (v_idx_head). Default fallback value if no row exists: ''CODE|HEAD|FIELD''. Example: ''CODE|HEAD'' matches column names containing "CODE" or "HEAD".';

COMMENT ON COLUMN "ASCII_CONFIG_TB"."DESC_PATTERN" IS
    'Oracle regular expression (case-insensitive) matched against each column name in the JSON template to identify the description column index (v_idx_desc). Default fallback value if no row exists: ''DESC''. Example: ''DESC|DESCRIPTION'' matches columns named "DESC" or "DESCRIPTION".';

COMMENT ON COLUMN "ASCII_CONFIG_TB"."SKIP_PATTERN" IS
    'Oracle regular expression (case-insensitive) used to mark template columns that should be excluded entirely from FORMULA cell processing. Null means no columns are skipped.';

COMMENT ON COLUMN "ASCII_CONFIG_TB"."ASCII_HEADER_ID" IS
    'Identifier of the ASCII header record definition to be prepended to the output file. Returned as OUT parameter p_header_id from parse procedure';

COMMENT ON COLUMN "ASCII_CONFIG_TB"."ASCII_FOOTER_ID" IS
    'Identifier of the ASCII footer record definition to be appended at the end of the output file. Returned as OUT parameter p_footer_id from parse procedure';

COMMENT ON COLUMN "ASCII_CONFIG_TB"."FILE_IDENTIFIER" IS
    'Short tag or code that uniquely identifies the output file and used in output file name.';


-- =============================================================================
-- TABLE 4: ASCII_MASTER
-- Purpose: Registry of all ASCII report definitions.
--          Each row names one report, its HDFS output location, and the
--          frequency at which it is generated.
-- =============================================================================

CREATE TABLE "ASCII_MASTER"
(
    "ID"         NUMBER         NOT NULL,
    "ASCII_NAME" VARCHAR2(50),
    "LOCATION"   VARCHAR2(1000),
    "FREQUENCY"  VARCHAR2(255)
);

ALTER TABLE "ASCII_MASTER" MODIFY ("ID" NOT NULL ENABLE);

-- Table-level comment
COMMENT ON TABLE "ASCII_MASTER" IS
    'Registry of all ASCII report types';

-- Column-level comments
COMMENT ON COLUMN "ASCII_MASTER"."ID" IS
    'Primary key. Unique numeric identifier for the ASCII report master record.';

COMMENT ON COLUMN "ASCII_MASTER"."ASCII_NAME" IS
    'Human-readable name or short code identifying the ASCII report.';

COMMENT ON COLUMN "ASCII_MASTER"."LOCATION" IS
    'HDFS path or base directory where the generated ASCII output files are written. ';

COMMENT ON COLUMN "ASCII_MASTER"."FREQUENCY" IS
    'Code indicating how often this report is generated. Valid single-character codes. D-Daily, W-Weekly, B-Bimonthly, M-Monthly, Q-Quarterly, H-Half Yearly, Y-Yearly';
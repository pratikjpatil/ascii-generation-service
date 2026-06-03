--  File created - Monday-June-02-2026
--  DDL for SP_GENERATE_TB_ASCII_STREAM_NEW1
set define off;

create or replace PROCEDURE SP_GENERATE_TB_ASCII_STREAM_NEW1 (
    p_run_id      IN  VARCHAR2,    -- From SP_PARSE_TB_TEMPLATE (ties to staging rows)
    p_branch_codes IN  TYPE_LIST,    -- Single branch or 'ALL'
    p_bal_date    IN  DATE,
    p_header_id   IN  VARCHAR2,    -- From SP_PARSE_TB_TEMPLATE OUT param
    p_footer_id   IN  VARCHAR2,    -- From SP_PARSE_TB_TEMPLATE OUT param
    p_cursor      OUT SYS_REFCURSOR
) AS
--    v_header_line VARCHAR2(200);
BEGIN
    -- Build fixed-format header line once
    -- Format: HEADER_ID + BRANCH_CODE + DDMMYYYY + 'F'
    OPEN p_cursor FOR

    WITH

    -- -- RAW_AGG ------------------------------------------------------------
    -- Joins GL_BALANCE against pre-parsed staging table.
    -- No JSON work here at all — that cost was paid once in SP_PARSE.
    -- HAVING clause applies ROW_FILTER (NEG/POS balance conditions).
    RAW_AGG AS (
        SELECT /*+ MATERIALIZE */
               t.HEAD_CODE,
               t.HEAD_DESC,
               t.VAR_NAME,
               t.LOGIC_TYPE,
               t.ROW_FILTER,
               t.IS_REVERSE,
               b.CGL,
               b.branch_code,
               NVL(m.DESCRIPTION, ' ') AS CGL_DESC,
               SUM(b.BALANCE)          AS CGL_BAL
        FROM   TB_BREAKUP_META_STAGE t
        JOIN   GL_BALANCE b
               ON  b.CGL          = t.CGL
               AND b.BALANCE_DATE = to_date(p_bal_date)
               AND (b.BRANCH_CODE MEMBER OF p_branch_codes)
        LEFT JOIN CGL_MASTER m
               ON m.CGL_NUMBER = b.CGL
        WHERE  t.run_id = p_run_id    -- Scoped to this parse run only
        GROUP BY
               t.HEAD_CODE, t.HEAD_DESC, t.VAR_NAME,
               t.LOGIC_TYPE, t.ROW_FILTER, t.IS_REVERSE,
               b.CGL, m.DESCRIPTION
               ,b.branch_code
        HAVING
            -- NULL filter = include all regardless of sign
               (t.ROW_FILTER IS NULL)
            -- NEG = only rows where net balance is negative
            OR (t.ROW_FILTER = 'NEG' AND SUM(b.BALANCE) < 0)
            -- POS = only rows where net balance is positive
            OR (t.ROW_FILTER = 'POS' AND SUM(b.BALANCE) > 0)
    ),

    -- -- Only If pass 'ALL' in branch_code  --------------------------------------------------------
    ALL_ROWS AS (
    SELECT /*+ MATERIALIZE */
               t.HEAD_CODE,
               t.HEAD_DESC,
               t.VAR_NAME,
               t.LOGIC_TYPE,
               t.ROW_FILTER,
               t.IS_REVERSE,
               b.CGL,
               '00000' branch_code,
               NVL(m.DESCRIPTION, ' ') AS CGL_DESC,
               SUM(b.BALANCE)          AS CGL_BAL
        FROM   TB_BREAKUP_META_STAGE t
        JOIN   GL_BALANCE b
               ON  b.CGL          = t.CGL
               AND b.BALANCE_DATE = to_date(p_bal_date)
        LEFT JOIN CGL_MASTER m
               ON m.CGL_NUMBER = b.CGL
        WHERE  t.run_id = p_run_id    -- Scoped to this parse run only
        GROUP BY
               t.HEAD_CODE, t.HEAD_DESC, t.VAR_NAME,
               t.LOGIC_TYPE, t.ROW_FILTER, t.IS_REVERSE,
               b.CGL, m.DESCRIPTION
        HAVING
            -- NULL filter = include all regardless of sign
               (t.ROW_FILTER IS NULL)
            -- NEG = only rows where net balance is negative
            OR (t.ROW_FILTER = 'NEG' AND SUM(b.BALANCE) < 0)
            -- POS = only rows where net balance is positive
            OR (t.ROW_FILTER = 'POS' AND SUM(b.BALANCE) > 0)
    ),

    -- -- SWING_CALC ---------------------------------------------------------
    -- Computes GROUP_TOTAL per (HEAD_CODE, VAR_NAME) using analytic SUM.
    -- Needed to evaluate SN/SP swing logic in the next CTE.
--    SWING_CALC AS (
--        SELECT /*+ MATERIALIZE */
--               r.*,
--               SUM(r.CGL_BAL) OVER (
--                   PARTITION BY r.HEAD_CODE, r.VAR_NAME, r.branch_code
--               ) AS GROUP_TOTAL
--        FROM RAW_AGG r
--    ),
SWING_CALC AS (
    SELECT /*+ MATERIALIZE */
           r.*,
           SUM(r.CGL_BAL) OVER (
               PARTITION BY r.HEAD_CODE, r.VAR_NAME, r.branch_code
           ) AS GROUP_TOTAL
    FROM (
        SELECT * FROM RAW_AGG
        UNION ALL
        SELECT a.*
        FROM ALL_ROWS a
        WHERE '00000' MEMBER OF p_branch_codes
    ) r
),


    -- -- FINAL_ROWS ---------------------------------------------------------
    -- Applies swing/credit/debit inclusion logic and formats each data line.
    -- IS_REVERSE flips the sign of the balance in the output amount.
    FINAL_ROWS AS (
        SELECT /*+ MATERIALIZE */
            -- Col 1-5  : HEAD_CODE left-padded with zeros to 5 chars
            LPAD(SUBSTR(HEAD_CODE, 1, 5),  5, '0')  ||
            -- Col 6-35 : HEAD_DESC right-padded with spaces to 30 chars
            RPAD(SUBSTR(HEAD_DESC, 1, 30), 30, ' ') ||
            -- Col 36-45: CGL right-padded to 10 chars
            RPAD(SUBSTR(CGL,       1, 10), 10, ' ') ||
            -- Col 46-75: CGL description right-padded to 30 chars
            RPAD(SUBSTR(CGL_DESC,  1, 30), 30, ' ') ||
            -- Col 76-95: Amount left-padded to 20 chars
            --            Sign prefix (+/-), 2 decimal places
            --            IS_REVERSE='Y' flips the sign
            LPAD(
                TO_CHAR(
                    CASE WHEN IS_REVERSE = 'Y'
                         THEN CGL_BAL * -1
                         ELSE CGL_BAL
                    END,
                    'FMS999999999999990.00'
                ),
                20, ' '
            ) AS LINE_TXT,
            HEAD_CODE,
            VAR_NAME,
            CGL,
            branch_code
        FROM SWING_CALC
        WHERE
            -- NORMAL / CN / DP: always include
               LOGIC_TYPE IN ('NORMAL', 'CN', 'DP')
            -- SN (Swing Neg): include only when group total is negative
            OR (LOGIC_TYPE = 'SN' AND GROUP_TOTAL < 0)
            -- SP (Swing Pos): include only when group total is positive
            OR (LOGIC_TYPE = 'SP' AND GROUP_TOTAL > 0)
    )

    -- -- FINAL SELECT -------------------------------------------------------
    -- ORD 1 = Header line (always one row)
    -- ORD 2 = Data body lines
    -- ORD 3 = Footer line (only if footer ID is configured)
    SELECT LINE
    FROM (
        SELECT 1  AS ORD,
               p_header_id || branch_code || TO_CHAR(p_bal_date, 'DDMMYYYY') || 'F' AS LINE,
               NULL       AS HC,
               NULL       AS VN,
               NULL       AS CG,
               branch_code
               FROM (SELECT COLUMN_VALUE as branch_code from table(p_branch_codes))

        UNION ALL

        SELECT 2, LINE_TXT, HEAD_CODE, VAR_NAME, CGL,branch_code
        FROM FINAL_ROWS

        UNION ALL

        SELECT 3, p_footer_id, NULL, NULL, NULL, branch_code
        FROM (SELECT COLUMN_VALUE as branch_code from table(p_branch_codes))
        WHERE p_footer_id IS NOT NULL

--        UNION ALL
--
--        SELECT 1  AS ORD,
--               p_header_id || 'ALL' || TO_CHAR(p_bal_date, 'DDMMYYYY') || 'F' AS LINE,
--               NULL       AS HC,
--               NULL       AS VN,
--               NULL       AS CG,
--               'ALL'
--               FROM ( SELECT 1 FROM DUAL where 'ALL' MEMBER OF p_branch_code)
--
--       UNION ALL
--
--       SELECT 3, p_footer_id, NULL, NULL, NULL, 'ALL'
--        FROM (SELECT 1 FROM DUAL where 'ALL' MEMBER OF p_branch_code)
--        WHERE p_footer_id IS NOT NULL

    )
    ORDER BY branch_code, ORD, HC, VN, CG;

END SP_GENERATE_TB_ASCII_STREAM_NEW1;
--  File created - Monday-June-02-2026
--  DDL for SP_GENERATE_TB_ASCII_STREAM_NEW1
set define off;

create or replace PROCEDURE SP_GENERATE_TB_ASCII_STREAM_NEW1 (
    p_run_id      IN  VARCHAR2,    -- From SP_PARSE_TB_TEMPLATE (ties to staging rows)
    p_branch_codes IN  TYPE_LIST,    -- Single branch or 'ALL'
    p_bal_date    IN  DATE,
    p_header_id   IN  VARCHAR2,    -- From SP_PARSE_TB_TEMPLATE OUT param
    p_footer_id   IN  VARCHAR2,    -- From SP_PARSE_TB_TEMPLATE OUT param
    p_cursor      OUT SYS_REFCURSOR
) AS
--    v_header_line VARCHAR2(200);
BEGIN
    -- Build fixed-format header line once
    -- Format: HEADER_ID + BRANCH_CODE + DDMMYYYY + 'F'
    OPEN p_cursor FOR

    WITH

    -- -- RAW_AGG ------------------------------------------------------------
    -- Joins GL_BALANCE against pre-parsed staging table.
    -- No JSON work here at all — that cost was paid once in SP_PARSE.
    -- HAVING clause applies ROW_FILTER (NEG/POS balance conditions).
    RAW_AGG AS (
        SELECT /*+ MATERIALIZE */
               t.HEAD_CODE,
               t.HEAD_DESC,
               t.VAR_NAME,
               t.LOGIC_TYPE,
               t.ROW_FILTER,
               t.IS_REVERSE,
               b.CGL,
               b.branch_code,
               NVL(m.DESCRIPTION, ' ') AS CGL_DESC,
               SUM(b.BALANCE)          AS CGL_BAL
        FROM   TB_BREAKUP_META_STAGE t
        JOIN   GL_BALANCE b
               ON  b.CGL          = t.CGL
               AND b.BALANCE_DATE = to_date(p_bal_date)
               AND (b.BRANCH_CODE MEMBER OF p_branch_codes)
        LEFT JOIN CGL_MASTER m
               ON m.CGL_NUMBER = b.CGL
        WHERE  t.run_id = p_run_id    -- Scoped to this parse run only
        GROUP BY
               t.HEAD_CODE, t.HEAD_DESC, t.VAR_NAME,
               t.LOGIC_TYPE, t.ROW_FILTER, t.IS_REVERSE,
               b.CGL, m.DESCRIPTION
               ,b.branch_code
        HAVING
            -- NULL filter = include all regardless of sign
               (t.ROW_FILTER IS NULL)
            -- NEG = only rows where net balance is negative
            OR (t.ROW_FILTER = 'NEG' AND SUM(b.BALANCE) < 0)
            -- POS = only rows where net balance is positive
            OR (t.ROW_FILTER = 'POS' AND SUM(b.BALANCE) > 0)
    ),

    -- -- Only If pass 'ALL' in branch_code  --------------------------------------------------------
    ALL_ROWS AS (
    SELECT /*+ MATERIALIZE */
               t.HEAD_CODE,
               t.HEAD_DESC,
               t.VAR_NAME,
               t.LOGIC_TYPE,
               t.ROW_FILTER,
               t.IS_REVERSE,
               b.CGL,
               '00000' branch_code,
               NVL(m.DESCRIPTION, ' ') AS CGL_DESC,
               SUM(b.BALANCE)          AS CGL_BAL
        FROM   TB_BREAKUP_META_STAGE t
        JOIN   GL_BALANCE b
               ON  b.CGL          = t.CGL
               AND b.BALANCE_DATE = to_date(p_bal_date)
        LEFT JOIN CGL_MASTER m
               ON m.CGL_NUMBER = b.CGL
        WHERE  t.run_id = p_run_id    -- Scoped to this parse run only
        GROUP BY
               t.HEAD_CODE, t.HEAD_DESC, t.VAR_NAME,
               t.LOGIC_TYPE, t.ROW_FILTER, t.IS_REVERSE,
               b.CGL, m.DESCRIPTION
        HAVING
            -- NULL filter = include all regardless of sign
               (t.ROW_FILTER IS NULL)
            -- NEG = only rows where net balance is negative
            OR (t.ROW_FILTER = 'NEG' AND SUM(b.BALANCE) < 0)
            -- POS = only rows where net balance is positive
            OR (t.ROW_FILTER = 'POS' AND SUM(b.BALANCE) > 0)
    ),

    -- -- SWING_CALC ---------------------------------------------------------
    -- Computes GROUP_TOTAL per (HEAD_CODE, VAR_NAME) using analytic SUM.
    -- Needed to evaluate SN/SP swing logic in the next CTE.
--    SWING_CALC AS (
--        SELECT /*+ MATERIALIZE */
--               r.*,
--               SUM(r.CGL_BAL) OVER (
--                   PARTITION BY r.HEAD_CODE, r.VAR_NAME, r.branch_code
--               ) AS GROUP_TOTAL
--        FROM RAW_AGG r
--    ),
SWING_CALC AS (
    SELECT /*+ MATERIALIZE */
           r.*,
           SUM(r.CGL_BAL) OVER (
               PARTITION BY r.HEAD_CODE, r.VAR_NAME, r.branch_code
           ) AS GROUP_TOTAL
    FROM (
        SELECT * FROM RAW_AGG
        UNION ALL
        SELECT a.*
        FROM ALL_ROWS a
        WHERE '00000' MEMBER OF p_branch_codes
    ) r
),


    -- -- FINAL_ROWS ---------------------------------------------------------
    -- Applies swing/credit/debit inclusion logic and formats each data line.
    -- IS_REVERSE flips the sign of the balance in the output amount.
    FINAL_ROWS AS (
        SELECT /*+ MATERIALIZE */
            -- Col 1-5  : HEAD_CODE left-padded with zeros to 5 chars
            LPAD(SUBSTR(HEAD_CODE, 1, 5),  5, '0')  ||
            -- Col 6-35 : HEAD_DESC right-padded with spaces to 30 chars
            RPAD(SUBSTR(HEAD_DESC, 1, 30), 30, ' ') ||
            -- Col 36-45: CGL right-padded to 10 chars
            RPAD(SUBSTR(CGL,       1, 10), 10, ' ') ||
            -- Col 46-75: CGL description right-padded to 30 chars
            RPAD(SUBSTR(CGL_DESC,  1, 30), 30, ' ') ||
            -- Col 76-95: Amount left-padded to 20 chars
            --            Sign prefix (+/-), 2 decimal places
            --            IS_REVERSE='Y' flips the sign
            LPAD(
                TO_CHAR(
                    CASE WHEN IS_REVERSE = 'Y'
                         THEN CGL_BAL * -1
                         ELSE CGL_BAL
                    END,
                    'FMS999999999999990.00'
                ),
                20, ' '
            ) AS LINE_TXT,
            HEAD_CODE,
            VAR_NAME,
            CGL,
            branch_code
        FROM SWING_CALC
        WHERE
            -- NORMAL / CN / DP: always include
               LOGIC_TYPE IN ('NORMAL', 'CN', 'DP')
            -- SN (Swing Neg): include only when group total is negative
            OR (LOGIC_TYPE = 'SN' AND GROUP_TOTAL < 0)
            -- SP (Swing Pos): include only when group total is positive
            OR (LOGIC_TYPE = 'SP' AND GROUP_TOTAL > 0)
    )

    -- -- FINAL SELECT -------------------------------------------------------
    -- ORD 1 = Header line (always one row)
    -- ORD 2 = Data body lines
    -- ORD 3 = Footer line (only if footer ID is configured)
    SELECT LINE
    FROM (
        SELECT 1  AS ORD,
               p_header_id || branch_code || TO_CHAR(p_bal_date, 'DDMMYYYY') || 'F' AS LINE,
               NULL       AS HC,
               NULL       AS VN,
               NULL       AS CG,
               branch_code
               FROM (SELECT COLUMN_VALUE as branch_code from table(p_branch_codes))

        UNION ALL

        SELECT 2, LINE_TXT, HEAD_CODE, VAR_NAME, CGL,branch_code
        FROM FINAL_ROWS

        UNION ALL

        SELECT 3, p_footer_id, NULL, NULL, NULL, branch_code
        FROM (SELECT COLUMN_VALUE as branch_code from table(p_branch_codes))
        WHERE p_footer_id IS NOT NULL

--        UNION ALL
--
--        SELECT 1  AS ORD,
--               p_header_id || 'ALL' || TO_CHAR(p_bal_date, 'DDMMYYYY') || 'F' AS LINE,
--               NULL       AS HC,
--               NULL       AS VN,
--               NULL       AS CG,
--               'ALL'
--               FROM ( SELECT 1 FROM DUAL where 'ALL' MEMBER OF p_branch_code)
--
--       UNION ALL
--
--       SELECT 3, p_footer_id, NULL, NULL, NULL, 'ALL'
--        FROM (SELECT 1 FROM DUAL where 'ALL' MEMBER OF p_branch_code)
--        WHERE p_footer_id IS NOT NULL

    )
    ORDER BY branch_code, ORD, HC, VN, CG;

END SP_GENERATE_TB_ASCII_STREAM_NEW1;
/
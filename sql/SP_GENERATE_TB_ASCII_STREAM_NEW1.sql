--  File created - Monday-June-19-2026
--  DDL for SP_GENERATE_TB_ASCII_STREAM_NEW7
create or replace PROCEDURE SP_GENERATE_TB_ASCII_STREAM_NEW7 (
    p_run_id       IN  VARCHAR2,
    p_branch_codes IN  TYPE_LIST,
    p_bal_date     IN  DATE,
    p_header_id    IN  VARCHAR2,
    p_footer_id    IN  VARCHAR2,
    p_cursor       OUT SYS_REFCURSOR
) AS
BEGIN

    OPEN p_cursor FOR

    WITH

    -- -----------------------------------------------------------------------
    -- RAW_AGG
    -- Evaluates specific branch balances.
    -- Sign guard (POS/NEG) correctly applies at the branch level.
    -- -----------------------------------------------------------------------
    RAW_AGG AS (
        SELECT /*+ MATERIALIZE */
               t.HEAD_CODE,
               t.HEAD_DESC,
               t.VAR_NAME,
               t.LOGIC_TYPE,
               t.ROW_FILTER,
               t.IS_REVERSE,
               b.CGL,
               b.BRANCH_CODE,
               NVL(m.DESCRIPTION, ' ') AS CGL_DESC,
               SUM(b.INR_BALANCE)      AS CGL_BAL
        FROM   TB_BREAKUP_META_STAGE t
        JOIN   GL_BALANCE b
               ON  b.CGL          = t.CGL
               AND TRUNC(b.BALANCE_DATE) = TRUNC(p_bal_date)
               AND (b.BRANCH_CODE MEMBER OF p_branch_codes)
        LEFT JOIN CGL_MASTER m
               ON  m.CGL_NUMBER = b.CGL
        WHERE  t.RUN_ID = p_run_id
          AND  b.BRANCH_CODE <> '00000' -- Ensure we don't accidentally pull pre-consolidated records
        GROUP BY
               t.HEAD_CODE, t.HEAD_DESC, t.VAR_NAME,
               t.LOGIC_TYPE, t.ROW_FILTER, t.IS_REVERSE,
               b.CGL, m.DESCRIPTION,
               b.BRANCH_CODE
        HAVING
               SUM(b.INR_BALANCE) <> 0
           AND (
                  (t.ROW_FILTER IS NULL)
               OR (t.ROW_FILTER = 'NEG' AND SUM(b.INR_BALANCE) < 0)
               OR (t.ROW_FILTER = 'POS' AND SUM(b.INR_BALANCE) > 0)
           )
    ),

    -- -----------------------------------------------------------------------
    -- ALL_ROWS
    -- Used only when '00000' (bank-level) is in p_branch_codes.
    -- FIX: Two-step aggregation. Applies the POS/NEG sign filter at the
    -- branch level FIRST, then rolls the surviving amounts into the 00000 total.
    -- -----------------------------------------------------------------------
    ALL_ROWS AS (
        SELECT /*+ MATERIALIZE */
               HEAD_CODE,
               HEAD_DESC,
               VAR_NAME,
               LOGIC_TYPE,
               ROW_FILTER,
               IS_REVERSE,
               CGL,
               '00000' AS BRANCH_CODE,
               CGL_DESC,
               SUM(BRANCH_CGL_BAL) AS CGL_BAL
        FROM (
            -- STEP 1: Branch-level aggregation and filtering
            SELECT t.HEAD_CODE, t.HEAD_DESC, t.VAR_NAME, t.LOGIC_TYPE, t.ROW_FILTER, t.IS_REVERSE,
                   b.CGL, b.BRANCH_CODE, NVL(m.DESCRIPTION, ' ') AS CGL_DESC,
                   SUM(b.INR_BALANCE) AS BRANCH_CGL_BAL
            FROM   TB_BREAKUP_META_STAGE t
            JOIN   GL_BALANCE b
                   ON  b.CGL          = t.CGL
                   AND TRUNC(b.BALANCE_DATE) = TRUNC(p_bal_date)
            LEFT JOIN CGL_MASTER m
                   ON  m.CGL_NUMBER = b.CGL
            WHERE  t.RUN_ID = p_run_id
              AND  b.BRANCH_CODE <> '00000'
            GROUP BY t.HEAD_CODE, t.HEAD_DESC, t.VAR_NAME, t.LOGIC_TYPE, t.ROW_FILTER, t.IS_REVERSE,
                     b.CGL, m.DESCRIPTION, b.BRANCH_CODE
            HAVING SUM(b.INR_BALANCE) <> 0
               AND (
                      (t.ROW_FILTER IS NULL)
                   OR (t.ROW_FILTER = 'NEG' AND SUM(b.INR_BALANCE) < 0)
                   OR (t.ROW_FILTER = 'POS' AND SUM(b.INR_BALANCE) > 0)
               )
        )
        -- STEP 2: Bank-level rollup
        GROUP BY HEAD_CODE, HEAD_DESC, VAR_NAME, LOGIC_TYPE, ROW_FILTER, IS_REVERSE, CGL, CGL_DESC
    ),

    -- -----------------------------------------------------------------------
    -- SWING_CALC
    -- Computes GROUP_TOTAL per (HEAD_CODE, VAR_NAME, BRANCH_CODE)
    -- -----------------------------------------------------------------------
    SWING_CALC AS (
        SELECT /*+ MATERIALIZE */
               r.*,
               SUM(r.CGL_BAL) OVER (
                   PARTITION BY r.HEAD_CODE, r.VAR_NAME, r.BRANCH_CODE
               ) AS GROUP_TOTAL
        FROM (
            SELECT * FROM RAW_AGG
            UNION ALL
            SELECT a.*
            FROM   ALL_ROWS a
            WHERE  '00000' MEMBER OF p_branch_codes
        ) r
    ),

    -- -----------------------------------------------------------------------
    -- FINAL_ROWS
    -- -----------------------------------------------------------------------
    FINAL_ROWS AS (
        SELECT /*+ MATERIALIZE */
            LPAD(SUBSTR(HEAD_CODE, 1,  5),  5, '0')  ||
            RPAD(SUBSTR(HEAD_DESC, 1, 30), 30, ' ')  ||
            RPAD(SUBSTR(CGL,       1, 10), 10, ' ')  ||
            RPAD(SUBSTR(CGL_DESC,  1, 30), 30, ' ')  ||
            LPAD(
                TO_CHAR(
                    CASE WHEN IS_REVERSE = 'Y'
                         THEN CGL_BAL
                         ELSE CGL_BAL * -1
                    END,
                    'FMS999999999999990.00'
                ),
                20, ' '
            ) AS LINE_TXT,
            HEAD_CODE,
            VAR_NAME,
            CGL,
            BRANCH_CODE
        FROM SWING_CALC
        WHERE
               LOGIC_TYPE IN ('NORMAL', 'CP', 'DN')
            OR (LOGIC_TYPE = 'SN' AND GROUP_TOTAL < 0)
            OR (LOGIC_TYPE = 'SP' AND GROUP_TOTAL > 0)
    )

    -- -----------------------------------------------------------------------
    -- FINAL SELECT
    -- -----------------------------------------------------------------------
    SELECT LINE
    FROM (

        -- Header
        SELECT 1 AS ORD,
               p_header_id || branch_code || TO_CHAR(p_bal_date, 'DDMMYYYY') || 'F' AS LINE,
               NULL AS HC, NULL AS VN, NULL AS CG, branch_code
        FROM  (SELECT COLUMN_VALUE AS branch_code FROM TABLE(p_branch_codes))

        UNION ALL

        -- Data body
        SELECT 2, LINE_TXT, HEAD_CODE, VAR_NAME, CGL, BRANCH_CODE
        FROM   FINAL_ROWS

        UNION ALL

        -- Footer (optional)
        SELECT 3, p_footer_id, NULL, NULL, NULL, branch_code
        FROM  (SELECT COLUMN_VALUE AS branch_code FROM TABLE(p_branch_codes))
        WHERE  p_footer_id IS NOT NULL

    )
    ORDER BY BRANCH_CODE, ORD, HC, VN, CG;

END SP_GENERATE_TB_ASCII_STREAM_NEW7;
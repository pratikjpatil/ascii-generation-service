--create or replace PROCEDURE SP_GENERATE_TB_ASCII_AND_REPORT (
--    p_run_id        IN  VARCHAR2,
--    p_branch_codes  IN  TYPE_LIST,
--    p_bal_date      IN  DATE,
--    p_header_id     IN  VARCHAR2,
--    p_footer_id     IN  VARCHAR2,
--    p_ascii_cursor  OUT SYS_REFCURSOR,
--    p_report_cursor OUT SYS_REFCURSOR
--) AS
--BEGIN
--
--    -- =========================================================================
--    -- CURSOR 1: ASCII STREAM (Simple ASCII)
--    -- =========================================================================
--    OPEN p_ascii_cursor FOR
--    WITH
--    RAW_AGG AS (
--        SELECT HEAD_CODE, HEAD_DESC, VAR_NAME, LOGIC_TYPE, ROW_FILTER, IS_REVERSE,
--               CGL, BRANCH_CODE, CGL_DESC,
--               SUM(CURRENCY_INR_BAL) AS INR_BAL
--        FROM (
--            SELECT t.HEAD_CODE, t.HEAD_DESC, t.VAR_NAME, t.LOGIC_TYPE, t.ROW_FILTER, t.IS_REVERSE,
--                   b.CGL, b.BRANCH_CODE, b.CURRENCY, NVL(m.DESCRIPTION, ' ') AS CGL_DESC,
--                   SUM(b.INR_BALANCE) AS CURRENCY_INR_BAL
--            FROM   RB_PARSED_TEMPLATE t
--            JOIN   GL_BALANCE b
--                   ON  b.CGL = t.CGL
--                   AND TRUNC(b.BALANCE_DATE) = TRUNC(p_bal_date)
--                   AND (b.BRANCH_CODE MEMBER OF p_branch_codes)
--                   AND (t.CURRENCY_FILTER IS NULL OR b.CURRENCY = t.CURRENCY_FILTER)
--            LEFT JOIN CGL_MASTER m ON m.CGL_NUMBER = b.CGL
--            WHERE  t.RUN_ID = p_run_id
--              AND  b.BRANCH_CODE <> '00000'
--            GROUP BY t.HEAD_CODE, t.HEAD_DESC, t.VAR_NAME, t.LOGIC_TYPE, t.ROW_FILTER, t.IS_REVERSE,
--                     b.CGL, b.BRANCH_CODE, b.CURRENCY, m.DESCRIPTION
--            HAVING SUM(b.INR_BALANCE) <> 0
--               AND (
--                      (t.ROW_FILTER IS NULL)
--                   OR (t.ROW_FILTER = 'NEG' AND SUM(b.INR_BALANCE) < 0)
--                   OR (t.ROW_FILTER = 'POS' AND SUM(b.INR_BALANCE) > 0)
--               )
--        )
--        GROUP BY HEAD_CODE, HEAD_DESC, VAR_NAME, LOGIC_TYPE, ROW_FILTER, IS_REVERSE,
--                 CGL, BRANCH_CODE, CGL_DESC
--    ),
--
--    ALL_ROWS AS (
--        SELECT HEAD_CODE, HEAD_DESC, VAR_NAME, LOGIC_TYPE, ROW_FILTER, IS_REVERSE,
--               CGL, '00000' AS BRANCH_CODE, CGL_DESC,
--               SUM(CURRENCY_INR_BAL) AS INR_BAL
--        FROM (
--            SELECT t.HEAD_CODE, t.HEAD_DESC, t.VAR_NAME, t.LOGIC_TYPE, t.ROW_FILTER, t.IS_REVERSE,
--                   b.CGL, b.BRANCH_CODE, b.CURRENCY, NVL(m.DESCRIPTION, ' ') AS CGL_DESC,
--                   SUM(b.INR_BALANCE) AS CURRENCY_INR_BAL
--            FROM   RB_PARSED_TEMPLATE t
--            JOIN   GL_BALANCE b
--                   ON  b.CGL = t.CGL
--                   AND TRUNC(b.BALANCE_DATE) = TRUNC(p_bal_date)
--                   AND (t.CURRENCY_FILTER IS NULL OR b.CURRENCY = t.CURRENCY_FILTER)
--            LEFT JOIN CGL_MASTER m ON m.CGL_NUMBER = b.CGL
--            WHERE  t.RUN_ID = p_run_id
--              AND  b.BRANCH_CODE <> '00000'
--            GROUP BY t.HEAD_CODE, t.HEAD_DESC, t.VAR_NAME, t.LOGIC_TYPE, t.ROW_FILTER, t.IS_REVERSE,
--                     b.CGL, b.BRANCH_CODE, b.CURRENCY, m.DESCRIPTION
--            HAVING SUM(b.INR_BALANCE) <> 0
--               AND (
--                      (t.ROW_FILTER IS NULL)
--                   OR (t.ROW_FILTER = 'NEG' AND SUM(b.INR_BALANCE) < 0)
--                   OR (t.ROW_FILTER = 'POS' AND SUM(b.INR_BALANCE) > 0)
--               )
--        )
--        GROUP BY HEAD_CODE, HEAD_DESC, VAR_NAME, LOGIC_TYPE, ROW_FILTER, IS_REVERSE,
--                 CGL, CGL_DESC
--    ),
--
--    SWING_CALC AS (
--        SELECT r.*,
--               SUM(r.INR_BAL) OVER (
--                   PARTITION BY r.HEAD_CODE, r.VAR_NAME, r.BRANCH_CODE
--               ) AS GROUP_TOTAL
--        FROM (
--            SELECT * FROM RAW_AGG
--            UNION ALL
--            SELECT a.* FROM ALL_ROWS a WHERE '00000' MEMBER OF p_branch_codes
--        ) r
--    ),
--
--    FINAL_ROWS AS (
--        SELECT
--            LPAD(SUBSTR(HEAD_CODE, 1,  5),  5, '0') ||
--            RPAD(SUBSTR(HEAD_DESC, 1, 30), 30, ' ') ||
--            RPAD(SUBSTR(CGL,       1, 10), 10, ' ') ||
--            RPAD(SUBSTR(CGL_DESC,  1, 30), 30, ' ') ||
--            -- WIDENED format mask to 'FM9999999999999990.00' (16 digits before decimal).
--            -- LPAD remains strictly 20 length. Negative sign + 16 digits + dot + 2 decimals = 20 chars maximum.
--            LPAD(
--                TO_CHAR(
--                    CASE WHEN IS_REVERSE = 'Y' THEN SUM(INR_BAL) ELSE SUM(INR_BAL) * -1 END,
--                    'FM9999999999999990.00'
--                ), 20, ' '
--            ) AS LINE_TXT,
--            HEAD_CODE, VAR_NAME, CGL, BRANCH_CODE
--        FROM SWING_CALC
--        WHERE LOGIC_TYPE IN ('NORMAL', 'CP', 'DN')
--           OR (LOGIC_TYPE = 'SN' AND GROUP_TOTAL < 0)
--           OR (LOGIC_TYPE = 'SP' AND GROUP_TOTAL > 0)
--        GROUP BY HEAD_CODE, HEAD_DESC, VAR_NAME, IS_REVERSE, CGL, CGL_DESC, BRANCH_CODE
--    )
--
--    SELECT LINE FROM (
--        SELECT 1 AS ORD,
--               p_header_id || branch_code || TO_CHAR(p_bal_date, 'DDMMYYYY') || 'F' AS LINE,
--               NULL AS HC, NULL AS VN, NULL AS CG, branch_code
--        FROM  (SELECT COLUMN_VALUE AS branch_code FROM TABLE(p_branch_codes))
--        UNION ALL
--        SELECT 2, LINE_TXT, HEAD_CODE, VAR_NAME, CGL, BRANCH_CODE
--        FROM FINAL_ROWS
--        UNION ALL
--        SELECT 3, p_footer_id, NULL, NULL, NULL, branch_code
--        FROM  (SELECT COLUMN_VALUE AS branch_code FROM TABLE(p_branch_codes))
--        WHERE p_footer_id IS NOT NULL
--    ) ORDER BY BRANCH_CODE, ORD, HC, VN, CG;
--
--
--    -- =========================================================================
--    -- CURSOR 2: JASPER REPORT (ASCII Report)
--    -- =========================================================================
--    OPEN p_report_cursor FOR
--    WITH
--    RAW_AGG AS (
--        SELECT HEAD_CODE, HEAD_DESC, VAR_NAME, LOGIC_TYPE, ROW_FILTER, IS_REVERSE,
--               CGL, BRANCH_CODE, CGL_DESC, CURRENCY, CURR_RATE,
--               SUM(CURRENCY_NATIVE_BAL) AS NATIVE_BAL,
--               SUM(CURRENCY_INR_BAL)    AS INR_BAL
--        FROM (
--            SELECT t.HEAD_CODE, t.HEAD_DESC, t.VAR_NAME, t.LOGIC_TYPE, t.ROW_FILTER, t.IS_REVERSE,
--                   b.CGL, b.BRANCH_CODE, b.CURRENCY, NVL(m.DESCRIPTION, ' ') AS CGL_DESC,
--                   MAX(cm.CURRENCY_RATE) AS CURR_RATE,
--                   SUM(b.BALANCE)       AS CURRENCY_NATIVE_BAL,
--                   SUM(b.INR_BALANCE)   AS CURRENCY_INR_BAL
--            FROM   RB_PARSED_TEMPLATE t
--            JOIN   GL_BALANCE b
--                   ON  b.CGL = t.CGL
--                   AND TRUNC(b.BALANCE_DATE) = TRUNC(p_bal_date)
--                   AND (b.BRANCH_CODE MEMBER OF p_branch_codes)
--                   AND (t.CURRENCY_FILTER IS NULL OR b.CURRENCY = t.CURRENCY_FILTER)
--            LEFT JOIN CGL_MASTER m  ON m.CGL_NUMBER    = b.CGL
--            LEFT JOIN CURRENCY_MASTER cm ON cm.CURRENCY_CODE = b.CURRENCY
--            WHERE  t.RUN_ID = p_run_id
--              AND  b.BRANCH_CODE <> '00000'
--            GROUP BY t.HEAD_CODE, t.HEAD_DESC, t.VAR_NAME, t.LOGIC_TYPE, t.ROW_FILTER, t.IS_REVERSE,
--                     b.CGL, b.BRANCH_CODE, b.CURRENCY, m.DESCRIPTION
--            HAVING SUM(b.INR_BALANCE) <> 0
--               AND (
--                      (t.ROW_FILTER IS NULL)
--                   OR (t.ROW_FILTER = 'NEG' AND SUM(b.INR_BALANCE) < 0)
--                   OR (t.ROW_FILTER = 'POS' AND SUM(b.INR_BALANCE) > 0)
--               )
--        )
--        GROUP BY HEAD_CODE, HEAD_DESC, VAR_NAME, LOGIC_TYPE, ROW_FILTER, IS_REVERSE,
--                 CGL, BRANCH_CODE, CGL_DESC, CURRENCY, CURR_RATE
--    ),
--
--    ALL_ROWS AS (
--        SELECT HEAD_CODE, HEAD_DESC, VAR_NAME, LOGIC_TYPE, ROW_FILTER, IS_REVERSE,
--               CGL, '00000' AS BRANCH_CODE, CGL_DESC, CURRENCY, CURR_RATE,
--               SUM(CURRENCY_NATIVE_BAL) AS NATIVE_BAL,
--               SUM(CURRENCY_INR_BAL)    AS INR_BAL
--        FROM (
--            SELECT t.HEAD_CODE, t.HEAD_DESC, t.VAR_NAME, t.LOGIC_TYPE, t.ROW_FILTER, t.IS_REVERSE,
--                   b.CGL, b.BRANCH_CODE, b.CURRENCY, NVL(m.DESCRIPTION, ' ') AS CGL_DESC,
--                   MAX(cm.CURRENCY_RATE) AS CURR_RATE,
--                   SUM(b.BALANCE)       AS CURRENCY_NATIVE_BAL,
--                   SUM(b.INR_BALANCE)   AS CURRENCY_INR_BAL
--            FROM   RB_PARSED_TEMPLATE t
--            JOIN   GL_BALANCE b
--                   ON  b.CGL = t.CGL
--                   AND TRUNC(b.BALANCE_DATE) = TRUNC(p_bal_date)
--                   AND (t.CURRENCY_FILTER IS NULL OR b.CURRENCY = t.CURRENCY_FILTER)
--            LEFT JOIN CGL_MASTER m  ON m.CGL_NUMBER    = b.CGL
--            LEFT JOIN CURRENCY_MASTER cm ON cm.CURRENCY_CODE = b.CURRENCY
--            WHERE  t.RUN_ID = p_run_id
--              AND  b.BRANCH_CODE <> '00000'
--            GROUP BY t.HEAD_CODE, t.HEAD_DESC, t.VAR_NAME, t.LOGIC_TYPE, t.ROW_FILTER, t.IS_REVERSE,
--                     b.CGL, b.BRANCH_CODE, b.CURRENCY, m.DESCRIPTION
--            HAVING SUM(b.INR_BALANCE) <> 0
--               AND (
--                      (t.ROW_FILTER IS NULL)
--                   OR (t.ROW_FILTER = 'NEG' AND SUM(b.INR_BALANCE) < 0)
--                   OR (t.ROW_FILTER = 'POS' AND SUM(b.INR_BALANCE) > 0)
--               )
--        )
--        GROUP BY HEAD_CODE, HEAD_DESC, VAR_NAME, LOGIC_TYPE, ROW_FILTER, IS_REVERSE,
--                 CGL, CGL_DESC, CURRENCY, CURR_RATE
--    ),
--
--    SWING_CALC AS (
--        SELECT r.*,
--               SUM(r.INR_BAL) OVER (
--                   PARTITION BY r.HEAD_CODE, r.VAR_NAME, r.BRANCH_CODE
--               ) AS GROUP_TOTAL
--        FROM (
--            SELECT * FROM RAW_AGG
--            UNION ALL
--            SELECT a.* FROM ALL_ROWS a WHERE '00000' MEMBER OF p_branch_codes
--        ) r
--    )
--
--    SELECT
--           LPAD(SUBSTR(HEAD_CODE, 1,  5),  5, '0') AS REPT_HEAD,
--           RPAD(SUBSTR(HEAD_DESC, 1, 30), 30, ' ') AS HEAD_DESC,
--           RPAD(SUBSTR(CGL,       1, 10), 10, ' ') AS CGL,
--           RPAD(SUBSTR(CGL_DESC,  1, 30), 30, ' ') AS CGL_DESCRIPTION,
--           RPAD(SUBSTR(CURRENCY,  1,  3),  3, ' ') AS CURRENCY,
--           -- CHANGED: Formatting broadened and inverted based on IS_REVERSE
--           TO_CHAR(
--               CASE WHEN IS_REVERSE = 'Y' THEN NATIVE_BAL * -1 ELSE NATIVE_BAL END,
--               'FM999,999,999,999,999,990.00'
--           )                                        AS BALANCE,
--    --       TO_CHAR(CURR_RATE, 'FM990.000000')       AS CURRENCY_RATE,
--           TO_CHAR(CURR_RATE, 'FM9999990.000000')   AS CURRENCY_RATE,
--           -- CHANGED: Formatting broadened and inverted based on IS_REVERSE
--           TO_CHAR(
--               CASE WHEN IS_REVERSE = 'Y' THEN INR_BAL * -1 ELSE INR_BAL END,
--               'FM999,999,999,999,999,990.00'
--           )                                        AS EQUI_INR_BALANCE,
--           BRANCH_CODE                              AS BRANCH_CODE
--    FROM SWING_CALC
--    WHERE LOGIC_TYPE IN ('NORMAL', 'CP', 'DN')
--       OR (LOGIC_TYPE = 'SN' AND GROUP_TOTAL < 0)
--       OR (LOGIC_TYPE = 'SP' AND GROUP_TOTAL > 0)
--    ORDER BY BRANCH_CODE, HEAD_CODE, VAR_NAME, CGL, CURRENCY;
--
--END SP_GENERATE_TB_ASCII_AND_REPORT;




CREATE OR REPLACE PROCEDURE SP_GENERATE_TB_ASCII_AND_REPORT (
    p_run_id        IN  VARCHAR2,
    p_report_id     IN  VARCHAR2,
    p_branch_codes  IN  TYPE_LIST,
    p_bal_date      IN  DATE,
    p_header_id     IN  VARCHAR2,
    p_footer_id     IN  VARCHAR2,
    p_ascii_cursor  OUT SYS_REFCURSOR,
    p_report_cursor OUT SYS_REFCURSOR
) AS
BEGIN

    -- =========================================================================
    -- CURSOR 1: ASCII STREAM (Simple ASCII)
    -- =========================================================================
    OPEN p_ascii_cursor FOR
    WITH
    RAW_AGG AS (
        SELECT HEAD_CODE, HEAD_DESC, VAR_NAME, LOGIC_TYPE, ROW_FILTER, IS_REVERSE,
               CGL, BRANCH_CODE, CGL_DESC,
               SUM(CURRENCY_INR_BAL) AS INR_BAL
        FROM (
            SELECT t.HEAD_CODE, t.HEAD_DESC, t.VAR_NAME, t.LOGIC_TYPE, t.ROW_FILTER, t.IS_REVERSE,
                   b.CGL, b.BRANCH_CODE, b.CURRENCY, NVL(m.DESCRIPTION, ' ') AS CGL_DESC,
                   SUM(b.INR_BALANCE) AS CURRENCY_INR_BAL
            FROM   RB_PARSED_TEMPLATE t
            JOIN   GL_BALANCE b
                   ON  b.CGL = t.CGL
                   AND TRUNC(b.BALANCE_DATE) = TRUNC(p_bal_date)
                   AND (b.BRANCH_CODE MEMBER OF p_branch_codes)
                   AND (t.CURRENCY_FILTER IS NULL OR b.CURRENCY = t.CURRENCY_FILTER)
            LEFT JOIN CGL_MASTER m ON m.CGL_NUMBER = b.CGL
            WHERE  t.RUN_ID = p_run_id
              AND  b.BRANCH_CODE <> '00000'
            GROUP BY t.HEAD_CODE, t.HEAD_DESC, t.VAR_NAME, t.LOGIC_TYPE, t.ROW_FILTER, t.IS_REVERSE,
                     b.CGL, b.BRANCH_CODE, b.CURRENCY, m.DESCRIPTION
            HAVING SUM(b.INR_BALANCE) <> 0
               AND (
                      (t.ROW_FILTER IS NULL)
                   OR (t.ROW_FILTER = 'NEG' AND SUM(b.INR_BALANCE) < 0)
                   OR (t.ROW_FILTER = 'POS' AND SUM(b.INR_BALANCE) > 0)
               )
        )
        GROUP BY HEAD_CODE, HEAD_DESC, VAR_NAME, LOGIC_TYPE, ROW_FILTER, IS_REVERSE,
                 CGL, BRANCH_CODE, CGL_DESC
    ),

    ALL_ROWS AS (
        SELECT HEAD_CODE, HEAD_DESC, VAR_NAME, LOGIC_TYPE, ROW_FILTER, IS_REVERSE,
               CGL, '00000' AS BRANCH_CODE, CGL_DESC,
               SUM(CURRENCY_INR_BAL) AS INR_BAL
        FROM (
            SELECT t.HEAD_CODE, t.HEAD_DESC, t.VAR_NAME, t.LOGIC_TYPE, t.ROW_FILTER, t.IS_REVERSE,
                   b.CGL, b.BRANCH_CODE, b.CURRENCY, NVL(m.DESCRIPTION, ' ') AS CGL_DESC,
                   SUM(b.INR_BALANCE) AS CURRENCY_INR_BAL
            FROM   RB_PARSED_TEMPLATE t
            JOIN   GL_BALANCE b
                   ON  b.CGL = t.CGL
                   AND TRUNC(b.BALANCE_DATE) = TRUNC(p_bal_date)
                   AND (t.CURRENCY_FILTER IS NULL OR b.CURRENCY = t.CURRENCY_FILTER)
            LEFT JOIN CGL_MASTER m ON m.CGL_NUMBER = b.CGL
            WHERE  t.RUN_ID = p_run_id
              AND  b.BRANCH_CODE <> '00000'
            GROUP BY t.HEAD_CODE, t.HEAD_DESC, t.VAR_NAME, t.LOGIC_TYPE, t.ROW_FILTER, t.IS_REVERSE,
                     b.CGL, b.BRANCH_CODE, b.CURRENCY, m.DESCRIPTION
            HAVING SUM(b.INR_BALANCE) <> 0
               AND (
                      (t.ROW_FILTER IS NULL)
                   OR (t.ROW_FILTER = 'NEG' AND SUM(b.INR_BALANCE) < 0)
                   OR (t.ROW_FILTER = 'POS' AND SUM(b.INR_BALANCE) > 0)
               )
        )
        GROUP BY HEAD_CODE, HEAD_DESC, VAR_NAME, LOGIC_TYPE, ROW_FILTER, IS_REVERSE,
                 CGL, CGL_DESC
    ),

    SWING_CALC AS (
        SELECT r.*,
               SUM(r.INR_BAL) OVER (
                   PARTITION BY r.HEAD_CODE, r.VAR_NAME, r.BRANCH_CODE
               ) AS GROUP_TOTAL
        FROM (
            SELECT * FROM RAW_AGG
            UNION ALL
            SELECT a.* FROM ALL_ROWS a WHERE '00000' MEMBER OF p_branch_codes
        ) r
    ),

    FINAL_ROWS AS (
        SELECT
            LPAD(SUBSTR(HEAD_CODE, 1,  5),  5, '0') ||
            RPAD(SUBSTR(HEAD_DESC, 1, 30), 30, ' ') ||
            RPAD(SUBSTR(CGL,       1, 10), 10, ' ') ||
            RPAD(SUBSTR(CGL_DESC,  1, 30), 30, ' ') ||
            LPAD(
                TO_CHAR(
                    CASE
                        WHEN IS_REVERSE = 'Y' THEN SUM(INR_BAL)
                        WHEN p_report_id = 'pnl_report' AND HEAD_CODE < '10000' THEN SUM(INR_BAL)
                        ELSE SUM(INR_BAL) * -1
                    END,
                    'FM9999999999999990.00'
                ), 20, ' '
            ) AS LINE_TXT,
            HEAD_CODE, VAR_NAME, CGL, BRANCH_CODE
        FROM SWING_CALC
        WHERE LOGIC_TYPE IN ('NORMAL', 'CP', 'DN')
           OR (LOGIC_TYPE = 'SN' AND GROUP_TOTAL < 0)
           OR (LOGIC_TYPE = 'SP' AND GROUP_TOTAL > 0)
        GROUP BY HEAD_CODE, HEAD_DESC, VAR_NAME, IS_REVERSE, CGL, CGL_DESC, BRANCH_CODE
    )

    SELECT LINE FROM (
        SELECT 1 AS ORD,
               p_header_id || branch_code || TO_CHAR(p_bal_date, 'DDMMYYYY') || 'F' AS LINE,
               NULL AS HC, NULL AS VN, NULL AS CG, branch_code
        FROM  (SELECT COLUMN_VALUE AS branch_code FROM TABLE(p_branch_codes))
        UNION ALL
        SELECT 2, LINE_TXT, HEAD_CODE, VAR_NAME, CGL, BRANCH_CODE
        FROM FINAL_ROWS
        UNION ALL
        SELECT 3, p_footer_id, NULL, NULL, NULL, branch_code
        FROM  (SELECT COLUMN_VALUE AS branch_code FROM TABLE(p_branch_codes))
        WHERE p_footer_id IS NOT NULL
    ) ORDER BY BRANCH_CODE, ORD, HC, VN, CG;


    -- =========================================================================
    -- CURSOR 2: JASPER REPORT (ASCII Report)
    -- =========================================================================
    OPEN p_report_cursor FOR
    WITH
    RAW_AGG AS (
        SELECT HEAD_CODE, HEAD_DESC, VAR_NAME, LOGIC_TYPE, ROW_FILTER, IS_REVERSE,
               CGL, BRANCH_CODE, CGL_DESC, CURRENCY, CURR_RATE,
               SUM(CURRENCY_NATIVE_BAL) AS NATIVE_BAL,
               SUM(CURRENCY_INR_BAL)    AS INR_BAL
        FROM (
            SELECT t.HEAD_CODE, t.HEAD_DESC, t.VAR_NAME, t.LOGIC_TYPE, t.ROW_FILTER, t.IS_REVERSE,
                   b.CGL, b.BRANCH_CODE, b.CURRENCY, NVL(m.DESCRIPTION, ' ') AS CGL_DESC,
                   MAX(cm.CURRENCY_RATE) AS CURR_RATE,
                   SUM(b.BALANCE)       AS CURRENCY_NATIVE_BAL,
                   SUM(b.INR_BALANCE)   AS CURRENCY_INR_BAL
            FROM   RB_PARSED_TEMPLATE t
            JOIN   GL_BALANCE b
                   ON  b.CGL = t.CGL
                   AND TRUNC(b.BALANCE_DATE) = TRUNC(p_bal_date)
                   AND (b.BRANCH_CODE MEMBER OF p_branch_codes)
                   AND (t.CURRENCY_FILTER IS NULL OR b.CURRENCY = t.CURRENCY_FILTER)
            LEFT JOIN CGL_MASTER m  ON m.CGL_NUMBER    = b.CGL
            LEFT JOIN CURRENCY_MASTER cm ON cm.CURRENCY_CODE = b.CURRENCY
            WHERE  t.RUN_ID = p_run_id
              AND  b.BRANCH_CODE <> '00000'
            GROUP BY t.HEAD_CODE, t.HEAD_DESC, t.VAR_NAME, t.LOGIC_TYPE, t.ROW_FILTER, t.IS_REVERSE,
                     b.CGL, b.BRANCH_CODE, b.CURRENCY, m.DESCRIPTION
            HAVING SUM(b.INR_BALANCE) <> 0
               AND (
                      (t.ROW_FILTER IS NULL)
                   OR (t.ROW_FILTER = 'NEG' AND SUM(b.INR_BALANCE) < 0)
                   OR (t.ROW_FILTER = 'POS' AND SUM(b.INR_BALANCE) > 0)
               )
        )
        GROUP BY HEAD_CODE, HEAD_DESC, VAR_NAME, LOGIC_TYPE, ROW_FILTER, IS_REVERSE,
                 CGL, BRANCH_CODE, CGL_DESC, CURRENCY, CURR_RATE
    ),

    ALL_ROWS AS (
        SELECT HEAD_CODE, HEAD_DESC, VAR_NAME, LOGIC_TYPE, ROW_FILTER, IS_REVERSE,
               CGL, '00000' AS BRANCH_CODE, CGL_DESC, CURRENCY, CURR_RATE,
               SUM(CURRENCY_NATIVE_BAL) AS NATIVE_BAL,
               SUM(CURRENCY_INR_BAL)    AS INR_BAL
        FROM (
            SELECT t.HEAD_CODE, t.HEAD_DESC, t.VAR_NAME, t.LOGIC_TYPE, t.ROW_FILTER, t.IS_REVERSE,
                   b.CGL, b.BRANCH_CODE, b.CURRENCY, NVL(m.DESCRIPTION, ' ') AS CGL_DESC,
                   MAX(cm.CURRENCY_RATE) AS CURR_RATE,
                   SUM(b.BALANCE)       AS CURRENCY_NATIVE_BAL,
                   SUM(b.INR_BALANCE)   AS CURRENCY_INR_BAL
            FROM   RB_PARSED_TEMPLATE t
            JOIN   GL_BALANCE b
                   ON  b.CGL = t.CGL
                   AND TRUNC(b.BALANCE_DATE) = TRUNC(p_bal_date)
                   AND (t.CURRENCY_FILTER IS NULL OR b.CURRENCY = t.CURRENCY_FILTER)
            LEFT JOIN CGL_MASTER m  ON m.CGL_NUMBER    = b.CGL
            LEFT JOIN CURRENCY_MASTER cm ON cm.CURRENCY_CODE = b.CURRENCY
            WHERE  t.RUN_ID = p_run_id
              AND  b.BRANCH_CODE <> '00000'
            GROUP BY t.HEAD_CODE, t.HEAD_DESC, t.VAR_NAME, t.LOGIC_TYPE, t.ROW_FILTER, t.IS_REVERSE,
                     b.CGL, b.BRANCH_CODE, b.CURRENCY, m.DESCRIPTION
            HAVING SUM(b.INR_BALANCE) <> 0
               AND (
                      (t.ROW_FILTER IS NULL)
                   OR (t.ROW_FILTER = 'NEG' AND SUM(b.INR_BALANCE) < 0)
                   OR (t.ROW_FILTER = 'POS' AND SUM(b.INR_BALANCE) > 0)
               )
        )
        GROUP BY HEAD_CODE, HEAD_DESC, VAR_NAME, LOGIC_TYPE, ROW_FILTER, IS_REVERSE,
                 CGL, CGL_DESC, CURRENCY, CURR_RATE
    ),

    SWING_CALC AS (
        SELECT r.*,
               SUM(r.INR_BAL) OVER (
                   PARTITION BY r.HEAD_CODE, r.VAR_NAME, r.BRANCH_CODE
               ) AS GROUP_TOTAL
        FROM (
            SELECT * FROM RAW_AGG
            UNION ALL
            SELECT a.* FROM ALL_ROWS a WHERE '00000' MEMBER OF p_branch_codes
        ) r
    )

    SELECT
           LPAD(SUBSTR(HEAD_CODE, 1,  5),  5, '0') AS REPT_HEAD,
           RPAD(SUBSTR(HEAD_DESC, 1, 30), 30, ' ') AS HEAD_DESC,
           RPAD(SUBSTR(CGL,       1, 10), 10, ' ') AS CGL,
           RPAD(SUBSTR(CGL_DESC,  1, 30), 30, ' ') AS CGL_DESCRIPTION,
           RPAD(SUBSTR(CURRENCY,  1,  3),  3, ' ') AS CURRENCY,
           TO_CHAR(
               CASE
                   WHEN IS_REVERSE = 'Y' THEN NATIVE_BAL * -1
                   WHEN p_report_id = 'pnl_report' AND HEAD_CODE < '10000' THEN NATIVE_BAL
                   ELSE NATIVE_BAL
               END,
               'FM999,999,999,999,999,990.00'
           )                                        AS BALANCE,
           TO_CHAR(CURR_RATE, 'FM9999990.000000')   AS CURRENCY_RATE,
           TO_CHAR(
               CASE
                   WHEN IS_REVERSE = 'Y' THEN INR_BAL * -1
                   WHEN p_report_id = 'pnl_report' AND HEAD_CODE < '10000' THEN INR_BAL
                   ELSE INR_BAL
               END,
               'FM999,999,999,999,999,990.00'
           )                                        AS EQUI_INR_BALANCE,
           BRANCH_CODE                              AS BRANCH_CODE
    FROM SWING_CALC
    WHERE LOGIC_TYPE IN ('NORMAL', 'CP', 'DN')
       OR (LOGIC_TYPE = 'SN' AND GROUP_TOTAL < 0)
       OR (LOGIC_TYPE = 'SP' AND GROUP_TOTAL > 0)
    ORDER BY BRANCH_CODE, HEAD_CODE, VAR_NAME, CGL, CURRENCY;

END SP_GENERATE_TB_ASCII_AND_REPORT;
/
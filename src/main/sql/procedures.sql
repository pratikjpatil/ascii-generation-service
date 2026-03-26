--// v1
--create or replace PROCEDURE SP_GENERATE_TB_ASCII_STREAM (
--    p_template_id IN VARCHAR2,
--    p_branch_code IN VARCHAR2,
--    p_bal_date    IN DATE,
--    p_cursor      OUT SYS_REFCURSOR
--) AS
--    -- JSON Handling Variables (Matches your working code)
--    l_clob          CLOB;
--    l_root          JSON_OBJECT_T;
--    l_template      JSON_OBJECT_T;
--    l_report_data   JSON_OBJECT_T;
--    l_columns       JSON_ARRAY_T;
--    l_col_def       JSON_OBJECT_T;
--    l_rows          JSON_ARRAY_T;
--    l_row           JSON_OBJECT_T;
--    l_cells         JSON_ARRAY_T;
--    l_curr_cell     JSON_OBJECT_T;
--
--    -- Formula Parsing Variables
--    l_vars          JSON_OBJECT_T;
--    l_var_keys      JSON_KEY_LIST;
--    l_curr_var      JSON_OBJECT_T;
--    l_filters       JSON_OBJECT_T;
--    l_bal_filter    JSON_ARRAY_T;
--    l_bal_obj       JSON_OBJECT_T;
--    l_cgl_values    JSON_ARRAY_T;
--    l_cgl_obj       JSON_OBJECT_T;
--
--    -- Config Variables
--    v_report_id     VARCHAR2(50);
--    v_header_id     VARCHAR2(20); -- Added for Stream Header
--    v_footer_id     VARCHAR2(20); -- Added for Stream Footer
--    v_head_pat      VARCHAR2(100);
--    v_desc_pat      VARCHAR2(100);
--    v_skip_pat      VARCHAR2(100);
--
--    -- Column Indexing
--    v_idx_head      NUMBER := 0;
--    v_idx_desc      NUMBER := 1;
--    v_col_name      VARCHAR2(100);
--    type t_idx_list IS TABLE OF NUMBER;
--    v_skip_cols     t_idx_list := t_idx_list();
--
--    -- Data Holders
--    v_head_code     VARCHAR2(100);
--    v_head_desc     VARCHAR2(4000);
--    v_var_name      VARCHAR2(100);
--    v_logic_type    VARCHAR2(50);
--    v_row_filter    VARCHAR2(20);
--    v_is_reverse    CHAR(1);
--    v_cgl           VARCHAR2(50);
--    v_header_line   VARCHAR2(200);
--
--BEGIN
--    -- 1. CLEANUP
--    DELETE FROM GTT_TB_BREAKUP_META;
--
--    -- 2. LOAD CONFIGURATION (Header/Footer/Patterns)
--    BEGIN
--        SELECT TEMPLATE_JSON, REPORT_ID INTO l_clob, v_report_id
--        FROM RB_REPORT_TEMPLATE WHERE TEMPLATE_ID = p_template_id;
--
--        -- Fetch Config (Patterns + ASCII Identifiers)
--        SELECT HEAD_PATTERN, DESC_PATTERN, SKIP_PATTERN, ASCII_HEADER_ID, ASCII_FOOTER_ID
--        INTO v_head_pat, v_desc_pat, v_skip_pat, v_header_id, v_footer_id
--        FROM ASCII_CONFIG_TB WHERE REPORT_ID = v_report_id;
--    EXCEPTION WHEN NO_DATA_FOUND THEN
--        -- Fallback if config missing (prevents crash)
--        v_head_pat := 'CODE|HEAD|FIELD'; v_desc_pat := 'DESC'; v_skip_pat := NULL;
--    END;
--
--    -- 3. CONSTRUCT HEADER STRING
--    -- Format: ID + Branch + Date + F
--    v_header_line := v_header_id || p_branch_code || TO_CHAR(p_bal_date, 'DDMMYYYY') || 'F';
--
--    -- 4. PARSE JSON (Using Constructor Syntax - The Working Logic)
--    l_root := JSON_OBJECT_T.parse(l_clob);
--
--    -- Handle Wrapper
--    IF l_root.has('template') THEN
--        l_template := l_root.get_Object('template');
--    ELSE
--        l_template := l_root;
--    END IF;
--
--    l_report_data := l_template.get_Object('reportData');
--
--    -- A. DETECT COLUMNS
--    IF l_report_data.has('columns') THEN
--        l_columns := l_report_data.get_Array('columns');
--        FOR i IN 0 .. l_columns.get_size - 1 LOOP
--            l_col_def := JSON_OBJECT_T(l_columns.get(i)); -- Constructor Syntax
--            v_col_name := UPPER(l_col_def.get_String('name'));
--
--            IF REGEXP_LIKE(v_col_name, v_head_pat, 'i') THEN v_idx_head := i; END IF;
--            IF REGEXP_LIKE(v_col_name, v_desc_pat, 'i') THEN v_idx_desc := i; END IF;
--            IF v_skip_pat IS NOT NULL AND REGEXP_LIKE(v_col_name, v_skip_pat, 'i') THEN
--                v_skip_cols.extend; v_skip_cols(v_skip_cols.count) := i;
--            END IF;
--        END LOOP;
--    END IF;
--
--    -- B. PARSE ROWS
--    l_rows := l_report_data.get_Array('rows');
--    FOR i IN 0 .. l_rows.get_size - 1 LOOP
--        l_row := JSON_OBJECT_T(l_rows.get(i)); -- Constructor Syntax
--
--        IF l_row.has('rowType') AND l_row.get_String('rowType') = 'DATA' THEN
--            l_cells := l_row.get_Array('cells');
--            v_head_code := NULL; v_head_desc := NULL;
--
--            -- Extract Head/Desc
--            IF l_cells.get_size > v_idx_head THEN
--                l_curr_cell := JSON_OBJECT_T(l_cells.get(v_idx_head)); -- Constructor Syntax
--                IF l_curr_cell.has('value') THEN v_head_code := l_curr_cell.get_String('value'); END IF;
--            END IF;
--            IF l_cells.get_size > v_idx_desc THEN
--                l_curr_cell := JSON_OBJECT_T(l_cells.get(v_idx_desc)); -- Constructor Syntax
--                IF l_curr_cell.has('value') THEN v_head_desc := l_curr_cell.get_String('value'); END IF;
--            END IF;
--
--            -- Extract Formula
--            FOR c_idx IN 0 .. l_cells.get_size - 1 LOOP
--                DECLARE v_skip BOOLEAN := FALSE; BEGIN
--                    FOR s IN 1 .. v_skip_cols.count LOOP IF v_skip_cols(s) = c_idx THEN v_skip := TRUE; EXIT; END IF; END LOOP;
--
--                    IF NOT v_skip THEN
--                        l_curr_cell := JSON_OBJECT_T(l_cells.get(c_idx)); -- Constructor Syntax
--
--                        IF l_curr_cell.get_String('type') = 'FORMULA' AND l_curr_cell.has('variables') THEN
--                            l_vars := l_curr_cell.get_Object('variables');
--                            l_var_keys := l_vars.get_keys;
--
--                            FOR j IN 1 .. l_var_keys.count LOOP
--                                v_var_name := l_var_keys(j);
--                                l_curr_var := l_vars.get_Object(v_var_name);
--
--                                -- Logic Type
--                                v_logic_type := 'NORMAL';
--                                IF v_var_name LIKE '%Swing_Neg%' OR v_var_name = 'SN' THEN v_logic_type := 'SN'; END IF;
--                                IF v_var_name LIKE '%Swing_Pos%' OR v_var_name = 'SP' THEN v_logic_type := 'SP'; END IF;
--                                IF v_var_name LIKE '%Credit_Neg%' OR v_var_name = 'CN' THEN v_logic_type := 'CN'; END IF;
--                                IF v_var_name LIKE '%Debit_Pos%' OR v_var_name = 'DP' THEN v_logic_type := 'DP'; END IF;
--
--                                v_is_reverse := 'N';
--                                IF v_var_name LIKE '%Reverse%' OR v_var_name = 'R' THEN v_is_reverse := 'Y'; END IF;
--
--                                -- Row Filter
--                                v_row_filter := NULL;
--                                IF l_curr_var.has('filters') THEN
--                                    l_filters := l_curr_var.get_Object('filters');
--
--                                    -- Balance Check
--                                    IF l_filters.has('BALANCE') THEN
--                                        l_bal_filter := l_filters.get_Array('BALANCE');
--                                        FOR z IN 0 .. l_bal_filter.get_size - 1 LOOP
--                                            l_bal_obj := JSON_OBJECT_T(l_bal_filter.get(z)); -- Constructor
--                                            IF l_bal_obj.get_String('op') = '<' AND l_bal_obj.get_String('value') = '0' THEN v_row_filter := 'NEG';
--                                            ELSIF l_bal_obj.get_String('op') = '>' AND l_bal_obj.get_String('value') = '0' THEN v_row_filter := 'POS';
--                                            END IF;
--                                        END LOOP;
--                                    END IF;
--
--                                    -- CGL Extraction
--                                    IF l_filters.has('CGL') THEN
--                                        l_cgl_values := l_filters.get_Array('CGL');
--                                        FOR k IN 0 .. l_cgl_values.get_size - 1 LOOP
--                                             l_cgl_obj := JSON_OBJECT_T(l_cgl_values.get(k)); -- Constructor
--
--                                             IF l_cgl_obj.get_String('op') IN ('IN', '=') THEN
--                                                IF l_cgl_obj.get('value').is_Array THEN
--                                                    DECLARE l_codes JSON_ARRAY_T := l_cgl_obj.get_Array('value');
--                                                    BEGIN
--                                                        FOR m IN 0 .. l_codes.get_size - 1 LOOP
--                                                            v_cgl := l_codes.get_String(m);
--                                                            INSERT INTO GTT_TB_BREAKUP_META
--                                                            VALUES (v_head_code, v_head_desc, v_var_name, v_logic_type, v_row_filter, v_is_reverse, v_cgl);
--                                                        END LOOP;
--                                                    END;
--                                                ELSE
--                                                    v_cgl := l_cgl_obj.get_String('value');
--                                                    INSERT INTO GTT_TB_BREAKUP_META
--                                                    VALUES (v_head_code, v_head_desc, v_var_name, v_logic_type, v_row_filter, v_is_reverse, v_cgl);
--                                                END IF;
--                                             END IF;
--                                        END LOOP;
--                                    END IF;
--                                END IF;
--                            END LOOP;
--                        END IF;
--                    END IF;
--                END;
--            END LOOP;
--        END IF;
--    END LOOP;
--
--    COMMIT;
--
--    -- 5. OPEN CURSOR FOR STREAMING
--    -- Uses "WITH" clause to avoid ORA-00935 (Nested Group Function)
--    OPEN p_cursor FOR
--        WITH
--        RAW_AGG AS (
--            SELECT /*+ MATERIALIZE */
--                   t.HEAD_CODE, t.HEAD_DESC, t.VAR_NAME, t.LOGIC_TYPE, t.IS_REVERSE,
--                   b.CGL,
--                   NVL(m.DESCRIPTION, ' ') AS CGL_DESC,
--                   SUM(b.BALANCE) AS CGL_BAL
--            FROM GL_BALANCE b
--            JOIN GTT_TB_BREAKUP_META t ON t.CGL = b.CGL
--            LEFT JOIN CGL_MASTER m ON m.CGL_NUMBER = b.CGL
--            WHERE b.BALANCE_DATE = p_bal_date
--              AND (p_branch_code = 'ALL' OR b.BRANCH_CODE = p_branch_code)
--            GROUP BY t.HEAD_CODE, t.HEAD_DESC, t.VAR_NAME, t.LOGIC_TYPE, t.IS_REVERSE, t.ROW_FILTER, b.CGL, m.DESCRIPTION
--            HAVING (t.ROW_FILTER IS NULL)
--                OR (t.ROW_FILTER = 'NEG' AND SUM(b.BALANCE) < 0)
--                OR (t.ROW_FILTER = 'POS' AND SUM(b.BALANCE) > 0)
--        ),
--        SWING_CALC AS (
--            SELECT r.*,
--                   SUM(r.CGL_BAL) OVER (PARTITION BY r.HEAD_CODE, r.VAR_NAME) AS GROUP_TOTAL
--            FROM RAW_AGG r
--        ),
--        FINAL_ROWS AS (
--            SELECT
--                -- Formatting Logic
--                LPAD(SUBSTR(HEAD_CODE, 1, 5), 5, '0') ||
--                RPAD(SUBSTR(HEAD_DESC, 1, 30), 30, ' ') ||
--                RPAD(SUBSTR(CGL, 1, 10), 10, ' ') ||
--                RPAD(SUBSTR(CGL_DESC, 1, 30), 30, ' ') ||
--                -- Amount Formatting: Sign + Zero Padding
--                TO_CHAR(
--                    CASE WHEN IS_REVERSE = 'Y' THEN CGL_BAL * -1 ELSE CGL_BAL END,
--                    'S00000000000000.00'
--                ) AS LINE_TXT,
--                HEAD_CODE, VAR_NAME, CGL
--            FROM SWING_CALC c
--            WHERE (LOGIC_TYPE IN ('NORMAL', 'CN', 'DP'))
--               OR (LOGIC_TYPE = 'SN' AND GROUP_TOTAL < 0)
--               OR (LOGIC_TYPE = 'SP' AND GROUP_TOTAL > 0)
--        )
--        -- Combine Header, Body, Footer
--        SELECT LINE FROM (
--            SELECT 1 AS ORD, v_header_line AS LINE, NULL AS HC, NULL AS VN, NULL AS CG FROM DUAL
--            UNION ALL
--            SELECT 2 AS ORD, LINE_TXT, HEAD_CODE, VAR_NAME, CGL FROM FINAL_ROWS
--            UNION ALL
--            SELECT 3 AS ORD, v_footer_id AS LINE, NULL, NULL, NULL FROM DUAL WHERE v_footer_id IS NOT NULL
--        )
--        ORDER BY ORD, HC, VN, CG;
--
--END;

--//v2
create or replace PROCEDURE SP_GENERATE_TB_ASCII_STREAM (
    p_template_id IN VARCHAR2,
    p_branch_code IN VARCHAR2,
    p_bal_date    IN DATE,
    p_cursor      OUT SYS_REFCURSOR
) AS
    -- JSON Handling Variables (Matches your working code)
    l_clob          CLOB;
    l_root          JSON_OBJECT_T;
    l_template      JSON_OBJECT_T;
    l_report_data   JSON_OBJECT_T;
    l_columns       JSON_ARRAY_T;
    l_col_def       JSON_OBJECT_T;
    l_rows          JSON_ARRAY_T;
    l_row           JSON_OBJECT_T;
    l_cells         JSON_ARRAY_T;
    l_curr_cell     JSON_OBJECT_T;

    -- Formula Parsing Variables
    l_vars          JSON_OBJECT_T;
    l_var_keys      JSON_KEY_LIST;
    l_curr_var      JSON_OBJECT_T;
    l_filters       JSON_OBJECT_T;
    l_bal_filter    JSON_ARRAY_T;
    l_bal_obj       JSON_OBJECT_T;
    l_cgl_values    JSON_ARRAY_T;
    l_cgl_obj       JSON_OBJECT_T;

    -- Config Variables
    v_report_id     VARCHAR2(50);
    v_header_id     VARCHAR2(20); -- Added for Stream Header
    v_footer_id     VARCHAR2(20); -- Added for Stream Footer
    v_head_pat      VARCHAR2(100);
    v_desc_pat      VARCHAR2(100);
    v_skip_pat      VARCHAR2(100);

    -- Column Indexing
    v_idx_head      NUMBER := 0;
    v_idx_desc      NUMBER := 1;
    v_col_name      VARCHAR2(100);
    type t_idx_list IS TABLE OF NUMBER;
    v_skip_cols     t_idx_list := t_idx_list();

    -- Data Holders
    v_head_code     VARCHAR2(100);
    v_head_desc     VARCHAR2(4000);
    v_var_name      VARCHAR2(100);
    v_logic_type    VARCHAR2(50);
    v_row_filter    VARCHAR2(20);
    v_is_reverse    CHAR(1);
    v_cgl           VARCHAR2(50);
    v_header_line   VARCHAR2(200);

BEGIN
    -- 1. CLEANUP
    DELETE FROM GTT_TB_BREAKUP_META;

    -- 2. LOAD CONFIGURATION (Header/Footer/Patterns)
    BEGIN
        SELECT TEMPLATE_JSON, REPORT_ID INTO l_clob, v_report_id
        FROM RB_REPORT_TEMPLATE WHERE TEMPLATE_ID = p_template_id;

        -- Fetch Config (Patterns + ASCII Identifiers)
        SELECT HEAD_PATTERN, DESC_PATTERN, SKIP_PATTERN, ASCII_HEADER_ID, ASCII_FOOTER_ID
        INTO v_head_pat, v_desc_pat, v_skip_pat, v_header_id, v_footer_id
        FROM ASCII_CONFIG_TB WHERE REPORT_ID = v_report_id;
    EXCEPTION WHEN NO_DATA_FOUND THEN
        -- Fallback if config missing (prevents crash)
        v_head_pat := 'CODE|HEAD|FIELD'; v_desc_pat := 'DESC'; v_skip_pat := NULL;
    END;

    -- 3. CONSTRUCT HEADER STRING
    -- Format: ID + Branch + Date + F
    v_header_line := v_header_id || p_branch_code || TO_CHAR(p_bal_date, 'DDMMYYYY') || 'F';

    -- 4. PARSE JSON (Using Constructor Syntax - The Working Logic)
    l_root := JSON_OBJECT_T.parse(l_clob);

    -- Handle Wrapper
    IF l_root.has('template') THEN
        l_template := l_root.get_Object('template');
    ELSE
        l_template := l_root;
    END IF;

    l_report_data := l_template.get_Object('reportData');

    -- A. DETECT COLUMNS
    IF l_report_data.has('columns') THEN
        l_columns := l_report_data.get_Array('columns');
        FOR i IN 0 .. l_columns.get_size - 1 LOOP
            l_col_def := JSON_OBJECT_T(l_columns.get(i)); -- Constructor Syntax
            v_col_name := UPPER(l_col_def.get_String('name'));

            IF REGEXP_LIKE(v_col_name, v_head_pat, 'i') THEN v_idx_head := i; END IF;
            IF REGEXP_LIKE(v_col_name, v_desc_pat, 'i') THEN v_idx_desc := i; END IF;
            IF v_skip_pat IS NOT NULL AND REGEXP_LIKE(v_col_name, v_skip_pat, 'i') THEN
                v_skip_cols.extend; v_skip_cols(v_skip_cols.count) := i;
            END IF;
        END LOOP;
    END IF;

    -- B. PARSE ROWS
    l_rows := l_report_data.get_Array('rows');
    FOR i IN 0 .. l_rows.get_size - 1 LOOP
        l_row := JSON_OBJECT_T(l_rows.get(i)); -- Constructor Syntax

        IF l_row.has('rowType') AND l_row.get_String('rowType') = 'DATA' THEN
            l_cells := l_row.get_Array('cells');
            v_head_code := NULL; v_head_desc := NULL;

            -- Extract Head/Desc
            IF l_cells.get_size > v_idx_head THEN
                l_curr_cell := JSON_OBJECT_T(l_cells.get(v_idx_head)); -- Constructor Syntax
                IF l_curr_cell.has('value') THEN v_head_code := l_curr_cell.get_String('value'); END IF;
            END IF;
            IF l_cells.get_size > v_idx_desc THEN
                l_curr_cell := JSON_OBJECT_T(l_cells.get(v_idx_desc)); -- Constructor Syntax
                IF l_curr_cell.has('value') THEN v_head_desc := l_curr_cell.get_String('value'); END IF;
            END IF;

            -- Extract Formula
            FOR c_idx IN 0 .. l_cells.get_size - 1 LOOP
                DECLARE v_skip BOOLEAN := FALSE; BEGIN
                    FOR s IN 1 .. v_skip_cols.count LOOP IF v_skip_cols(s) = c_idx THEN v_skip := TRUE; EXIT; END IF; END LOOP;

                    IF NOT v_skip THEN
                        l_curr_cell := JSON_OBJECT_T(l_cells.get(c_idx)); -- Constructor Syntax

                        IF l_curr_cell.get_String('type') = 'FORMULA' AND l_curr_cell.has('variables') THEN
                            l_vars := l_curr_cell.get_Object('variables');
                            l_var_keys := l_vars.get_keys;

                            FOR j IN 1 .. l_var_keys.count LOOP
                                v_var_name := l_var_keys(j);
                                l_curr_var := l_vars.get_Object(v_var_name);

                                -- Logic Type
                                v_logic_type := 'NORMAL';
                                IF v_var_name LIKE '%Swing_Neg%' OR v_var_name = 'SN' THEN v_logic_type := 'SN'; END IF;
                                IF v_var_name LIKE '%Swing_Pos%' OR v_var_name = 'SP' THEN v_logic_type := 'SP'; END IF;
                                IF v_var_name LIKE '%Credit_Neg%' OR v_var_name = 'CN' THEN v_logic_type := 'CN'; END IF;
                                IF v_var_name LIKE '%Debit_Pos%' OR v_var_name = 'DP' THEN v_logic_type := 'DP'; END IF;

                                v_is_reverse := 'N';
                                IF v_var_name LIKE '%Reverse%' OR v_var_name = 'R' THEN v_is_reverse := 'Y'; END IF;

                                -- Row Filter
                                v_row_filter := NULL;
                                IF l_curr_var.has('filters') THEN
                                    l_filters := l_curr_var.get_Object('filters');

                                    -- Balance Check
                                    IF l_filters.has('BALANCE') THEN
                                        l_bal_filter := l_filters.get_Array('BALANCE');
                                        FOR z IN 0 .. l_bal_filter.get_size - 1 LOOP
                                            l_bal_obj := JSON_OBJECT_T(l_bal_filter.get(z)); -- Constructor
                                            IF l_bal_obj.get_String('op') = '<' AND l_bal_obj.get_String('value') = '0' THEN v_row_filter := 'NEG';
                                            ELSIF l_bal_obj.get_String('op') = '>' AND l_bal_obj.get_String('value') = '0' THEN v_row_filter := 'POS';
                                            END IF;
                                        END LOOP;
                                    END IF;

                                    -- CGL Extraction
                                    IF l_filters.has('CGL') THEN
                                        l_cgl_values := l_filters.get_Array('CGL');
                                        FOR k IN 0 .. l_cgl_values.get_size - 1 LOOP
                                             l_cgl_obj := JSON_OBJECT_T(l_cgl_values.get(k)); -- Constructor

                                             IF l_cgl_obj.get_String('op') IN ('IN', '=') THEN
                                                IF l_cgl_obj.get('value').is_Array THEN
                                                    DECLARE l_codes JSON_ARRAY_T := l_cgl_obj.get_Array('value');
                                                    BEGIN
                                                        FOR m IN 0 .. l_codes.get_size - 1 LOOP
                                                            v_cgl := l_codes.get_String(m);
                                                            INSERT INTO GTT_TB_BREAKUP_META
                                                            VALUES (v_head_code, v_head_desc, v_var_name, v_logic_type, v_row_filter, v_is_reverse, v_cgl);
                                                        END LOOP;
                                                    END;
                                                ELSE
                                                    v_cgl := l_cgl_obj.get_String('value');
                                                    INSERT INTO GTT_TB_BREAKUP_META
                                                    VALUES (v_head_code, v_head_desc, v_var_name, v_logic_type, v_row_filter, v_is_reverse, v_cgl);
                                                END IF;
                                             END IF;
                                        END LOOP;
                                    END IF;
                                END IF;
                            END LOOP;
                        END IF;
                    END IF;
                END;
            END LOOP;
        END IF;
    END LOOP;

    COMMIT;

    -- 5. OPEN CURSOR FOR STREAMING
    -- Uses "WITH" clause to avoid ORA-00935 (Nested Group Function)
    OPEN p_cursor FOR
        WITH
        RAW_AGG AS (
            SELECT /*+ MATERIALIZE */
                   t.HEAD_CODE, t.HEAD_DESC, t.VAR_NAME, t.LOGIC_TYPE, t.IS_REVERSE,
                   b.CGL,
                   NVL(m.DESCRIPTION, ' ') AS CGL_DESC,
                   SUM(b.BALANCE) AS CGL_BAL
            FROM GL_BALANCE b
            JOIN GTT_TB_BREAKUP_META t ON t.CGL = b.CGL
            LEFT JOIN CGL_MASTER m ON m.CGL_NUMBER = b.CGL
            WHERE b.BALANCE_DATE = p_bal_date
              AND (p_branch_code = 'ALL' OR b.BRANCH_CODE = p_branch_code)
            GROUP BY t.HEAD_CODE, t.HEAD_DESC, t.VAR_NAME, t.LOGIC_TYPE, t.IS_REVERSE, t.ROW_FILTER, b.CGL, m.DESCRIPTION
            HAVING (t.ROW_FILTER IS NULL)
                OR (t.ROW_FILTER = 'NEG' AND SUM(b.BALANCE) < 0)
                OR (t.ROW_FILTER = 'POS' AND SUM(b.BALANCE) > 0)
        ),
        SWING_CALC AS (
            SELECT r.*,
                   SUM(r.CGL_BAL) OVER (PARTITION BY r.HEAD_CODE, r.VAR_NAME) AS GROUP_TOTAL
            FROM RAW_AGG r
        ),
        FINAL_ROWS AS (
            SELECT
                -- Formatting Logic
                LPAD(SUBSTR(HEAD_CODE, 1, 5), 5, '0') ||
                RPAD(SUBSTR(HEAD_DESC, 1, 30), 30, ' ') ||
                RPAD(SUBSTR(CGL, 1, 10), 10, ' ') ||
                RPAD(SUBSTR(CGL_DESC, 1, 30), 30, ' ') ||
                -- Amount Formatting: Sign + Zero Padding
--                TO_CHAR(
--                    CASE WHEN IS_REVERSE = 'Y' THEN CGL_BAL * -1 ELSE CGL_BAL END,
--                    'S00000000000000.00'
--                ) AS LINE_TXT,
                LPAD(TO_CHAR(CASE WHEN IS_REVERSE = 'Y' THEN CGL_BAL * -1 ELSE CGL_BAL END, 'FMS999999999999990.00'), 20, ' ') AS LINE_TXT,
                HEAD_CODE, VAR_NAME, CGL
            FROM SWING_CALC c
            WHERE (LOGIC_TYPE IN ('NORMAL', 'CN', 'DP'))
               OR (LOGIC_TYPE = 'SN' AND GROUP_TOTAL < 0)
               OR (LOGIC_TYPE = 'SP' AND GROUP_TOTAL > 0)
        )
        -- Combine Header, Body, Footer
        SELECT LINE FROM (
            SELECT 1 AS ORD, v_header_line AS LINE, NULL AS HC, NULL AS VN, NULL AS CG FROM DUAL
            UNION ALL
            SELECT 2 AS ORD, LINE_TXT, HEAD_CODE, VAR_NAME, CGL FROM FINAL_ROWS
            UNION ALL
            SELECT 3 AS ORD, v_footer_id AS LINE, NULL, NULL, NULL FROM DUAL WHERE v_footer_id IS NOT NULL
        )
        ORDER BY ORD, HC, VN, CG;

END;
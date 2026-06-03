--  File created - Monday-May-18-2026   
--  DDL for Procedure SP_GENERATE_TB_BREAKUP_ASCII
set define off;

  CREATE OR REPLACE EDITIONABLE PROCEDURE "SP_GENERATE_TB_BREAKUP_ASCII" (
    p_template_id IN VARCHAR2,
    p_branch_code IN VARCHAR2, -- Pass 'ALL' for Bank Level
    p_bal_date    IN DATE
) AS
    -- JSON Handling Variables
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
    v_insert_count  NUMBER := 0;

    v_header_txt    VARCHAR2(200);

    -- =========================================================================
    -- OUTPUT QUERY ENGINE
    -- =========================================================================
    CURSOR c_output IS
        WITH 
        -- 1. Fetch Balances & Apply Row-Level Filters (CN/DP)
        RAW_DATA AS (
            SELECT /*+ MATERIALIZE */
                   CASE WHEN p_branch_code = 'ALL' THEN 'BANK' ELSE b.BRANCH_CODE END AS BRANCH_CODE,
                   b.CGL, 
                   SUM(b.BALANCE) AS GL_BAL,
                   t.HEAD_CODE, t.HEAD_DESC, t.VAR_NAME, t.LOGIC_TYPE, t.IS_REVERSE, t.ROW_FILTER
            FROM GL_BALANCE b
            JOIN GTT_TB_BREAKUP_META t ON t.CGL = b.CGL
            WHERE b.BALANCE_DATE = p_bal_date 
              AND (p_branch_code = 'ALL' OR b.BRANCH_CODE = p_branch_code)
            GROUP BY 
                CASE WHEN p_branch_code = 'ALL' THEN 'BANK' ELSE b.BRANCH_CODE END,
                b.CGL, t.HEAD_CODE, t.HEAD_DESC, t.VAR_NAME, t.LOGIC_TYPE, t.IS_REVERSE, t.ROW_FILTER
            HAVING 
                (t.ROW_FILTER IS NULL) OR 
                (t.ROW_FILTER = 'NEG' AND SUM(b.BALANCE) < 0) OR
                (t.ROW_FILTER = 'POS' AND SUM(b.BALANCE) > 0)
        ),

        -- 2. Calculate Variable Group Totals (For Swing Logic)
        CALC_DATA AS (
            SELECT r.*,
                   SUM(r.GL_BAL) OVER (PARTITION BY r.HEAD_CODE, r.VAR_NAME) AS VAR_NET_SUM
            FROM RAW_DATA r
        ),

        -- 3. Final Filtering & Description Lookup
        FINAL_ROWS AS (
            SELECT c.*,
                   -- Lookup CGL Description (Default to empty space if null)
                   NVL(m.DESCRIPTION, ' ') AS CGL_DESC,
                   -- Apply Reverse Sign Logic
                   CASE WHEN c.IS_REVERSE = 'Y' THEN c.GL_BAL * -1 ELSE c.GL_BAL END AS DISPLAY_AMT
            FROM CALC_DATA c
            LEFT JOIN CGL_MASTER m ON m.CGL_NUMBER = c.CGL -- Ensure this column name matches your DB
            WHERE 
               (c.LOGIC_TYPE IN ('NORMAL', 'CN', 'DP')) OR
               (c.LOGIC_TYPE = 'SN' AND c.VAR_NET_SUM < 0) OR
               (c.LOGIC_TYPE = 'SP' AND c.VAR_NET_SUM > 0)
        )

        -- 4. Formatting (Fixed Width ASCII)
        SELECT 
            RPAD(SUBSTR(HEAD_CODE, 1, 10), 10) || ' ' || 
            RPAD(SUBSTR(HEAD_DESC, 1, 35), 35) || ' ' || 
            RPAD(SUBSTR(CGL, 1, 12), 12) || ' ' || 
            RPAD(SUBSTR(CGL_DESC, 1, 35), 35) || ' ' || 
            LPAD(TO_CHAR(DISPLAY_AMT, 'FM9999999999999999990.00'), 15) AS TXT
        FROM FINAL_ROWS
        ORDER BY HEAD_CODE, VAR_NAME, CGL;

BEGIN
    -- 1. INIT & CLEANUP
    DELETE FROM GTT_TB_BREAKUP_META;

    IF p_branch_code = 'ALL' THEN v_header_txt := 'BANK LEVEL REPORT';
    ELSE v_header_txt := 'BRANCH: ' || p_branch_code; END IF;

    -- 2. LOAD TEMPLATE & CONFIG
    BEGIN
        SELECT TEMPLATE_JSON, REPORT_ID INTO l_clob, v_report_id FROM RB_REPORT_TEMPLATE WHERE TEMPLATE_ID = p_template_id;

        BEGIN
            SELECT HEAD_PATTERN, DESC_PATTERN, SKIP_PATTERN 
            INTO v_head_pat, v_desc_pat, v_skip_pat 
            FROM ASCII_CONFIG_TB WHERE REPORT_ID = v_report_id;
        EXCEPTION WHEN NO_DATA_FOUND THEN
            v_head_pat := 'CODE|HEAD|FIELD'; v_desc_pat := 'DESC'; v_skip_pat := NULL;
        END;
    EXCEPTION WHEN NO_DATA_FOUND THEN
        DBMS_OUTPUT.PUT_LINE('ERROR: Template ' || p_template_id || ' not found.'); RETURN;
    END;

    l_root := JSON_OBJECT_T.parse(l_clob);
    IF l_root.has('template') THEN l_template := l_root.get_Object('template'); ELSE l_template := l_root; END IF;
    l_report_data := l_template.get_Object('reportData');

    -- 3. DETECT COLUMNS DYNAMICALLY
    IF l_report_data.has('columns') THEN
        l_columns := l_report_data.get_Array('columns');
        FOR i IN 0 .. l_columns.get_size - 1 LOOP
            l_col_def := JSON_OBJECT_T(l_columns.get(i));
            v_col_name := UPPER(l_col_def.get_String('name'));

            IF REGEXP_LIKE(v_col_name, v_head_pat, 'i') THEN v_idx_head := i; END IF;
            IF REGEXP_LIKE(v_col_name, v_desc_pat, 'i') THEN v_idx_desc := i; END IF;
            IF v_skip_pat IS NOT NULL AND REGEXP_LIKE(v_col_name, v_skip_pat, 'i') THEN
                v_skip_cols.extend; v_skip_cols(v_skip_cols.count) := i;
            END IF;
        END LOOP;
    END IF;

    l_rows := l_report_data.get_Array('rows');

    -- 4. PARSE ROWS & LOGIC
    FOR i IN 0 .. l_rows.get_size - 1 LOOP
        l_row := JSON_OBJECT_T(l_rows.get(i));
        IF l_row.has('rowType') AND l_row.get_String('rowType') = 'DATA' THEN
            l_cells := l_row.get_Array('cells');
            v_head_code := NULL; v_head_desc := NULL;

            -- Extract Head/Desc
            IF l_cells.get_size > v_idx_head THEN
                l_curr_cell := JSON_OBJECT_T(l_cells.get(v_idx_head));
                IF l_curr_cell.has('value') THEN v_head_code := l_curr_cell.get_String('value'); END IF;
            END IF;
            IF l_cells.get_size > v_idx_desc THEN
                l_curr_cell := JSON_OBJECT_T(l_cells.get(v_idx_desc));
                IF l_curr_cell.has('value') THEN v_head_desc := l_curr_cell.get_String('value'); END IF;
            END IF;

            -- Extract Formula
            FOR c_idx IN 0 .. l_cells.get_size - 1 LOOP
                DECLARE v_skip BOOLEAN := FALSE; BEGIN
                    FOR s IN 1 .. v_skip_cols.count LOOP IF v_skip_cols(s) = c_idx THEN v_skip := TRUE; EXIT; END IF; END LOOP;
                    IF NOT v_skip THEN
                        l_curr_cell := JSON_OBJECT_T(l_cells.get(c_idx));
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
                                    IF l_filters.has('BALANCE') THEN
                                        l_bal_filter := l_filters.get_Array('BALANCE');
                                        FOR z IN 0 .. l_bal_filter.get_size - 1 LOOP
                                            l_bal_obj := JSON_OBJECT_T(l_bal_filter.get(z));
                                            IF l_bal_obj.get_String('op') = '<' AND l_bal_obj.get_String('value') = '0' THEN v_row_filter := 'NEG';
                                            ELSIF l_bal_obj.get_String('op') = '>' AND l_bal_obj.get_String('value') = '0' THEN v_row_filter := 'POS';
                                            END IF;
                                        END LOOP;
                                    END IF;
                                    IF l_filters.has('CGL') THEN
                                        l_cgl_values := l_filters.get_Array('CGL'); 
                                        FOR k IN 0 .. l_cgl_values.get_size - 1 LOOP
                                             l_cgl_obj := JSON_OBJECT_T(l_cgl_values.get(k));
                                             IF l_cgl_obj.get_String('op') IN ('IN', '=') THEN
                                                IF l_cgl_obj.get('value').is_Array THEN
                                                    DECLARE l_codes JSON_ARRAY_T := l_cgl_obj.get_Array('value');
                                                    BEGIN
                                                        FOR m IN 0 .. l_codes.get_size - 1 LOOP
                                                            v_cgl := l_codes.get_String(m);
                                                            INSERT INTO GTT_TB_BREAKUP_META (HEAD_CODE, HEAD_DESC, VAR_NAME, LOGIC_TYPE, ROW_FILTER, IS_REVERSE, CGL)
                                                            VALUES (v_head_code, v_head_desc, v_var_name, v_logic_type, v_row_filter, v_is_reverse, v_cgl);
                                                            v_insert_count := v_insert_count + 1;
                                                        END LOOP;
                                                    END;
                                                ELSE
                                                    v_cgl := l_cgl_obj.get_String('value');
                                                    INSERT INTO GTT_TB_BREAKUP_META (HEAD_CODE, HEAD_DESC, VAR_NAME, LOGIC_TYPE, ROW_FILTER, IS_REVERSE, CGL)
                                                    VALUES (v_head_code, v_head_desc, v_var_name, v_logic_type, v_row_filter, v_is_reverse, v_cgl);
                                                    v_insert_count := v_insert_count + 1;
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

    -- 5. PRINT OUTPUT
    DBMS_OUTPUT.PUT_LINE('===============================================================================================================');
    DBMS_OUTPUT.PUT_LINE(v_header_txt || ' | AS ON: ' || TO_CHAR(p_bal_date, 'DD-MON-YYYY'));
    DBMS_OUTPUT.PUT_LINE('===============================================================================================================');
    DBMS_OUTPUT.PUT_LINE(
        RPAD('CODE', 10) || ' ' || 
        RPAD('DESCRIPTION', 35) || ' ' || 
        RPAD('CGL', 12) || ' ' || 
        RPAD('CGL NAME', 35) || ' ' || 
        LPAD('AMOUNT', 15)
    );
    DBMS_OUTPUT.PUT_LINE('---------------------------------------------------------------------------------------------------------------');

    FOR r IN c_output LOOP
        DBMS_OUTPUT.PUT_LINE(r.TXT);
    END LOOP;

EXCEPTION WHEN OTHERS THEN DBMS_OUTPUT.PUT_LINE('ERROR: ' || SQLERRM);
END;

/
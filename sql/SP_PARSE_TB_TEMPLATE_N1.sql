--  File created - Monday-May-18-2026   
--  DDL for Procedure SP_PARSE_TB_TEMPLATE_N1
set define off;

create or replace PROCEDURE SP_PARSE_TB_TEMPLATE_N1 (
    p_report_id IN  VARCHAR2,
    p_run_id      IN  VARCHAR2,
--    p_report_id   OUT VARCHAR2,
    p_header_id   OUT VARCHAR2,
    p_footer_id   OUT VARCHAR2
) AS

    -- -- JSON handles ---------------------------------------------------------
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
    l_vars          JSON_OBJECT_T;
    l_var_keys      JSON_KEY_LIST;
    l_curr_var      JSON_OBJECT_T;
    l_filters       JSON_OBJECT_T;
    l_bal_filter    JSON_ARRAY_T;
    l_bal_obj       JSON_OBJECT_T;
    l_cgl_values    JSON_ARRAY_T;
    l_cgl_obj       JSON_OBJECT_T;
    l_cgl_arr       JSON_ARRAY_T;

    -- -- Pattern / index config ------------------------------------------------
    v_head_pat      VARCHAR2(100);
    v_desc_pat      VARCHAR2(100);
    v_skip_pat      VARCHAR2(100);
    v_idx_head      PLS_INTEGER := 0;
    v_idx_desc      PLS_INTEGER := 1;
    v_col_name      VARCHAR2(100);

    -- -- O(1) skip-column lookup (key = col index, value = 1) -----------------
    -- Replaces the old inner FOR loop scan — hash lookup is O(1) vs O(n)
    TYPE t_skip_map IS TABLE OF PLS_INTEGER INDEX BY PLS_INTEGER;
    v_skip_map      t_skip_map;

    -- -- Row-level variables ---------------------------------------------------
    v_head_code     VARCHAR2(100);
    v_head_desc     VARCHAR2(4000);
    v_var_name      VARCHAR2(100);
    v_logic_type    VARCHAR2(50);
    v_row_filter    VARCHAR2(20);
    v_is_reverse    CHAR(1);
    v_cgl           VARCHAR2(50);
    v_cell_type     VARCHAR2(50);
    v_bal_op        VARCHAR2(10);
    v_cgl_op        VARCHAR2(10);
    v_cells_size    PLS_INTEGER;
    v_cgl_arr_size  PLS_INTEGER;

    -- -- Bulk insert collection ------------------------------------------------
    -- All rows accumulated here; single FORALL at the end — no per-row INSERT
    TYPE t_vc50   IS TABLE OF VARCHAR2(50)   INDEX BY PLS_INTEGER;
    TYPE t_vc100  IS TABLE OF VARCHAR2(100)  INDEX BY PLS_INTEGER;
    TYPE t_vc4000 IS TABLE OF VARCHAR2(4000) INDEX BY PLS_INTEGER;
    TYPE t_vc1    IS TABLE OF CHAR(1)        INDEX BY PLS_INTEGER;

    -- Parallel arrays (one slot per meta row to insert)
    v_b_run_id      t_vc50;
    v_b_head_code   t_vc100;
    v_b_head_desc   t_vc4000;
    v_b_var_name    t_vc100;
    v_b_logic_type  t_vc50;
    v_b_row_filter  t_vc50;
    v_b_is_reverse  t_vc1;
    v_b_cgl         t_vc50;

    v_buf_idx       PLS_INTEGER := 0;  -- current buffer position

    -- -- Helper: append one row to the buffer ---------------------------------
    PROCEDURE buf_add (
        p_head_code  VARCHAR2,
        p_head_desc  VARCHAR2,
        p_var_name   VARCHAR2,
        p_logic_type VARCHAR2,
        p_row_filter VARCHAR2,
        p_is_reverse CHAR,
        p_cgl        VARCHAR2
    ) IS
    BEGIN
        v_buf_idx := v_buf_idx + 1;
        v_b_run_id    (v_buf_idx) := p_run_id;
        v_b_head_code (v_buf_idx) := p_head_code;
        v_b_head_desc (v_buf_idx) := p_head_desc;
        v_b_var_name  (v_buf_idx) := p_var_name;
        v_b_logic_type(v_buf_idx) := p_logic_type;
        v_b_row_filter(v_buf_idx) := p_row_filter;
        v_b_is_reverse(v_buf_idx) := p_is_reverse;
        v_b_cgl       (v_buf_idx) := p_cgl;
    END buf_add;

BEGIN

    -- -- 1. Clean any prior run with the same run_id ---------------------------
    DELETE /*+ DIRECT */ FROM TB_BREAKUP_META_STAGE WHERE RUN_ID = p_run_id;

    -- -- 2. Load template JSON -------------------------------------------------
    SELECT TEMPLATE_JSON
      INTO l_clob
      FROM RB_REPORT_TEMPLATE
     WHERE REPORT_ID = p_report_id AND STATUS='ACTIVE' ORDER BY VERSION_NO DESC FETCH FIRST 1 ROW ONLY;

    -- -- 3. Load ASCII config --------------------------------------------------
    BEGIN
        SELECT HEAD_PATTERN, DESC_PATTERN, SKIP_PATTERN,
               ASCII_HEADER_ID, ASCII_FOOTER_ID
          INTO v_head_pat, v_desc_pat, v_skip_pat,
               p_header_id, p_footer_id
          FROM ASCII_CONFIG_TB
         WHERE REPORT_ID = p_report_id;
    EXCEPTION
        WHEN NO_DATA_FOUND THEN
            v_head_pat := 'CODE|HEAD|FIELD';
            v_desc_pat := 'DESC';
            v_skip_pat := NULL;
            p_header_id := NULL;
            p_footer_id := NULL;
    END;

    -- -- 4. Parse JSON root ----------------------------------------------------
    l_root := JSON_OBJECT_T.parse(l_clob);

    IF l_root.has('template') THEN
        l_template := l_root.get_Object('template');
    ELSE
        l_template := l_root;
    END IF;

    l_report_data := l_template.get_Object('reportData');

    -- -- 5. Detect head / desc / skip column indexes ---------------------------
    IF l_report_data.has('columns') THEN
        l_columns := l_report_data.get_Array('columns');
        FOR i IN 0 .. l_columns.get_size - 1 LOOP
            l_col_def  := JSON_OBJECT_T(l_columns.get(i));
            v_col_name := UPPER(l_col_def.get_String('name'));

            IF REGEXP_LIKE(v_col_name, v_head_pat, 'i') THEN
                v_idx_head := i;
            END IF;
            IF REGEXP_LIKE(v_col_name, v_desc_pat, 'i') THEN
                v_idx_desc := i;
            END IF;
            -- O(1) insert into hash map — lookup later is v_skip_map.EXISTS(c_idx)
            IF v_skip_pat IS NOT NULL AND REGEXP_LIKE(v_col_name, v_skip_pat, 'i') THEN
                v_skip_map(i) := 1;
            END IF;
        END LOOP;
    END IF;

    -- -- 6. Walk rows ----------------------------------------------------------
    l_rows := l_report_data.get_Array('rows');

    FOR i IN 0 .. l_rows.get_size - 1 LOOP
        l_row := JSON_OBJECT_T(l_rows.get(i));

        -- Skip non-DATA rows immediately (no further JSON work)
        CONTINUE WHEN NOT (l_row.has('rowType')
                           AND l_row.get_String('rowType') = 'DATA');

        l_cells      := l_row.get_Array('cells');
        v_cells_size := l_cells.get_size;
        v_head_code  := NULL;
        v_head_desc  := NULL;

        -- Extract HEAD code
        IF v_cells_size > v_idx_head THEN
            l_curr_cell := JSON_OBJECT_T(l_cells.get(v_idx_head));
            IF l_curr_cell.has('value') THEN
                v_head_code := l_curr_cell.get_String('value');
            END IF;
        END IF;

        -- Extract DESC
        IF v_cells_size > v_idx_desc THEN
            l_curr_cell := JSON_OBJECT_T(l_cells.get(v_idx_desc));
            IF l_curr_cell.has('value') THEN
                v_head_desc := l_curr_cell.get_String('value');
            END IF;
        END IF;

        -- Walk cells
        FOR c_idx IN 0 .. v_cells_size - 1 LOOP

            -- O(1) skip check — hash map lookup, no inner loop
            CONTINUE WHEN v_skip_map.EXISTS(c_idx);

            l_curr_cell := JSON_OBJECT_T(l_cells.get(c_idx));

            -- Read type once; skip early if not FORMULA
            v_cell_type := l_curr_cell.get_String('type');
            CONTINUE WHEN v_cell_type IS NULL OR v_cell_type <> 'FORMULA';
            CONTINUE WHEN NOT l_curr_cell.has('variables');

            l_vars     := l_curr_cell.get_Object('variables');
             IF l_vars IS NULL THEN
                l_vars := JSON_OBJECT_T(); -- Creates an empty JSON object {}
             END IF;
            l_var_keys := l_vars.get_keys;

            FOR j IN 1 .. l_var_keys.count LOOP
                v_var_name := l_var_keys(j);
                l_curr_var := l_vars.get_Object(v_var_name);

                -- -- Derive logic type (single pass, no redundant checks) ------
                v_logic_type :=
                    CASE
                        WHEN v_var_name LIKE '%Swing_Neg%'  OR v_var_name = 'SN' THEN 'SN'
                        WHEN v_var_name LIKE '%Swing_Pos%'  OR v_var_name = 'SP' THEN 'SP'
                        WHEN v_var_name LIKE '%Credit_Neg%' OR v_var_name = 'CN' THEN 'CN'
                        WHEN v_var_name LIKE '%Debit_Pos%'  OR v_var_name = 'DP' THEN 'DP'
                        ELSE 'NORMAL'
                    END;

                v_is_reverse :=
                    CASE WHEN v_var_name LIKE '%Reverse%' OR v_var_name = 'R'
                         THEN 'Y' ELSE 'N' END;

                -- -- Filters ---------------------------------------------------
                v_row_filter := NULL;

                IF l_curr_var.has('filters') THEN
                    l_filters := l_curr_var.get_Object('filters');

                    -- BALANCE filter
                    IF l_filters.has('BALANCE') THEN
                        l_bal_filter := l_filters.get_Array('BALANCE');
                        FOR z IN 0 .. l_bal_filter.get_size - 1 LOOP
                            l_bal_obj := JSON_OBJECT_T(l_bal_filter.get(z));
                            v_bal_op  := l_bal_obj.get_String('op');
                            IF    v_bal_op = '<' THEN v_row_filter := 'NEG';
                            ELSIF v_bal_op = '>' THEN v_row_filter := 'POS';
                            END IF;
                            EXIT; -- Only ever one BALANCE filter entry; no need to loop further
                        END LOOP;
                    END IF;

                    -- CGL filter — push each CGL code into buffer
                    IF l_filters.has('CGL') THEN
                        l_cgl_values := l_filters.get_Array('CGL');

                        FOR k IN 0 .. l_cgl_values.get_size - 1 LOOP
                            l_cgl_obj := JSON_OBJECT_T(l_cgl_values.get(k));
                            v_cgl_op  := l_cgl_obj.get_String('op');

                            CONTINUE WHEN v_cgl_op NOT IN ('IN', '=');

                            IF l_cgl_obj.get('value').is_Array THEN
                                -- Multi-value IN list
                                l_cgl_arr      := l_cgl_obj.get_Array('value');
                                v_cgl_arr_size := l_cgl_arr.get_size;
                                FOR m IN 0 .. v_cgl_arr_size - 1 LOOP
                                    buf_add(v_head_code, v_head_desc, v_var_name,
                                            v_logic_type, v_row_filter, v_is_reverse,
                                            l_cgl_arr.get_String(m));
                                END LOOP;
                            ELSE
                                -- Single value
                                buf_add(v_head_code, v_head_desc, v_var_name,
                                        v_logic_type, v_row_filter, v_is_reverse,
                                        l_cgl_obj.get_String('value'));
                            END IF;
                        END LOOP;
                    END IF; -- has CGL
                END IF; -- has filters

            END LOOP; -- variables
        END LOOP; -- cells
    END LOOP; -- rows

    -- -- 7. FORALL bulk insert — ONE statement for all rows --------------------
    -- APPEND hint: bypasses buffer cache, writes directly to HWM.
    -- Dramatically reduces redo and latch contention vs row-by-row.
    IF v_buf_idx > 0 THEN
        FORALL idx IN 1 .. v_buf_idx
            INSERT /*+ APPEND */ INTO TB_BREAKUP_META_STAGE (
                RUN_ID, HEAD_CODE, HEAD_DESC,
                VAR_NAME, LOGIC_TYPE, ROW_FILTER,
                IS_REVERSE, CGL
            ) VALUES (
                v_b_run_id    (idx),
                v_b_head_code (idx),
                v_b_head_desc (idx),
                v_b_var_name  (idx),
                v_b_logic_type(idx),
                v_b_row_filter(idx),
                v_b_is_reverse(idx),
                v_b_cgl       (idx)
            );
    END IF;

    COMMIT;

    -- -- 8. Refresh stats so the branch query gets accurate cardinality ---------
    -- estimate_percent=>100 is fine — meta stage is small (few thousand rows max)
    DBMS_STATS.GATHER_TABLE_STATS(
        ownname          => USER,
        tabname          => 'TB_BREAKUP_META_STAGE',
        estimate_percent => 100,
        no_invalidate    => FALSE
    );

END SP_PARSE_TB_TEMPLATE_N1;
/
-- Atomically create a new document or supersede the active version of an existing one.
--
-- p_chunks is a jsonb array of objects:
--   [{"text": "...", "embedding": [0.1, 0.2, ... 768 values], "metadata": {...}}, ...]
-- "metadata" is optional (defaults to {}).
--
-- Returns the id of the newly inserted documents row.
CREATE OR REPLACE FUNCTION upsert_document(
    p_name        VARCHAR,
    p_user_id     VARCHAR,
    p_source_path VARCHAR,
    p_is_new      BOOLEAN,
    p_chunks      JSONB
) RETURNS UUID
LANGUAGE plpgsql
AS $$
DECLARE
    c_embedding_dims CONSTANT INT := 768;
    v_now     TIMESTAMPTZ := CURRENT_TIMESTAMP; -- frozen per transaction: old effective_to == new effective_from
    v_old_id  UUID;
    v_new_id  UUID;
    v_chunk   JSONB;
    v_idx     INT := 0;
BEGIN
    -- 1. Validation (no writes until everything passes)
    IF p_name IS NULL OR btrim(p_name) = '' THEN
        RAISE EXCEPTION 'Document name is required' USING ERRCODE = '22023';
    END IF;
    IF p_user_id IS NULL OR btrim(p_user_id) = '' THEN
        RAISE EXCEPTION 'User id is required' USING ERRCODE = '22023';
    END IF;
    IF p_source_path IS NULL OR btrim(p_source_path) = '' THEN
        RAISE EXCEPTION 'Source document path is required' USING ERRCODE = '22023';
    END IF;
    IF p_is_new IS NULL THEN
        RAISE EXCEPTION 'is_new flag is required' USING ERRCODE = '22023';
    END IF;
    IF p_chunks IS NULL OR jsonb_typeof(p_chunks) <> 'array' OR jsonb_array_length(p_chunks) = 0 THEN
        RAISE EXCEPTION 'Chunk list must be a non-empty JSON array' USING ERRCODE = '22023';
    END IF;

    FOR v_chunk IN SELECT * FROM jsonb_array_elements(p_chunks) LOOP
        v_idx := v_idx + 1;

        IF jsonb_typeof(v_chunk) <> 'object'
           OR v_chunk->>'text' IS NULL
           OR btrim(v_chunk->>'text') = '' THEN
            RAISE EXCEPTION 'Chunk % has missing or empty text', v_idx USING ERRCODE = '22023';
        END IF;

        IF jsonb_typeof(v_chunk->'embedding') IS DISTINCT FROM 'array' THEN
            RAISE EXCEPTION 'Chunk % has a missing or non-array embedding', v_idx USING ERRCODE = '22023';
        END IF;

        IF jsonb_array_length(v_chunk->'embedding') <> c_embedding_dims THEN
            RAISE EXCEPTION 'Chunk % embedding has % dimensions, expected %',
                v_idx, jsonb_array_length(v_chunk->'embedding'), c_embedding_dims
                USING ERRCODE = '22023';
        END IF;
    END LOOP;

    -- 2. Enforce the is_new contract; lock the active row when superseding.
    --    Lookup is by name + is_active, never by a UI-supplied id.
    SELECT id INTO v_old_id
    FROM documents
    WHERE name = p_name AND is_active
    FOR UPDATE;

    IF p_is_new AND v_old_id IS NOT NULL THEN
        RAISE EXCEPTION 'A document named "%" already exists', p_name USING ERRCODE = '23505';
    END IF;
    IF NOT p_is_new AND v_old_id IS NULL THEN
        RAISE EXCEPTION 'No active document named "%" exists', p_name USING ERRCODE = 'P0002';
    END IF;
    
    -- 3. Deactivate the superseded version. The row is already locked by the FOR UPDATE in
    --    step 2; the lock is held until the surrounding transaction commits or rolls back,
    --    so a concurrent update of the same name blocks, then sees is_active = false.
    IF v_old_id IS NOT NULL THEN
        UPDATE documents
        SET is_active       = false,
            deactivated_by  = p_user_id,
            deactivate_date = v_now,
            effective_to    = v_now
        WHERE id = v_old_id;
    END IF;

    -- 4. Insert the new version. For a brand-new name there is nothing to lock, so two
    --    concurrent inserts race; the partial unique index rejects the loser.
    BEGIN
        INSERT INTO documents (name, is_active, source_document_path, created_by, created_by_date, effective_from)
        VALUES (p_name, true, p_source_path, p_user_id, v_now, v_now)
        RETURNING id INTO v_new_id;
    EXCEPTION WHEN unique_violation THEN
        RAISE EXCEPTION 'A document named "%" was created concurrently; retry as an update', p_name
            USING ERRCODE = '23505';
    END;

    -- 5. Insert all chunks
    INSERT INTO document_chunks (document_id, document_chunk, embedding, metadata)
    SELECT v_new_id,
           e->>'text',
           (e->>'embedding')::vector,   -- pgvector parses the '[0.1,0.2,...]' text form
           COALESCE(e->'metadata', '{}'::jsonb)
    FROM jsonb_array_elements(p_chunks) AS e;

    RETURN v_new_id;
END;
$$;

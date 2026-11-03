-- Queries for the versioning part of the demo. Run with:
--   docker exec -it jtms-postgres psql -U jtms -d jtms
-- then paste a query, or: docker exec -i jtms-postgres psql -U jtms -d jtms < demo/queries.sql

-- 1. Version history of one document: every upload is kept, exactly one row is active,
--    and the old version's effective_to equals the new version's effective_from.
SELECT name, is_active, created_by, effective_from, effective_to, deactivated_by
FROM documents
WHERE name = 'procurement-policy'
ORDER BY effective_from;

-- 2. Chunks per document version (retrieval only searches the active ones).
SELECT d.name, d.is_active, count(c.id) AS chunks
FROM documents d
JOIN document_chunks c ON c.document_id = d.id
GROUP BY d.name, d.is_active, d.effective_from
ORDER BY d.name, d.effective_from;

-- 3. "Which version was in force on a given date?" Change the timestamp to a moment between
--    the two uploads (copy one from query 1) to get version 1 back.
SELECT name, id, effective_from, effective_to, created_by
FROM documents
WHERE name = 'procurement-policy'
  AND effective_from <= now()
  AND (effective_to IS NULL OR effective_to > now());

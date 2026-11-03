CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE TABLE documents (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name VARCHAR(255) NOT NULL,
    is_active BOOLEAN NOT NULL,
    source_document_path VARCHAR(255) NOT NULL,
    created_by VARCHAR(50) NOT NULL,
    created_by_date TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    effective_from TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    effective_to TIMESTAMP WITH TIME ZONE,
    deactivated_by VARCHAR(50),
    deactivate_date TIMESTAMP WITH TIME ZONE
);

CREATE TABLE document_chunks (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    document_id UUID NOT NULL REFERENCES documents(id) ON DELETE RESTRICT,
    document_chunk TEXT NOT NULL,
    embedding VECTOR(768),
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb
);

CREATE UNIQUE INDEX unique_name_active
ON documents (name)
WHERE is_active IS true;

CREATE INDEX idx_document_chunks_embedding ON document_chunks
USING hnsw (embedding vector_cosine_ops);

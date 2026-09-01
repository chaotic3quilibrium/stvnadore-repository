-- STVN Repository Version Catalog Schema DDL
-- Compatible with PostgreSQL 15+ and H2 Database (MODE=PostgreSQL)

CREATE TABLE IF NOT EXISTS version_catalog (
    schema_name      VARCHAR(255) NOT NULL,
    shape_signature  TEXT         NOT NULL,
    cas_hash         VARCHAR(64)  NOT NULL,
    created_at       TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_version_catalog PRIMARY KEY (schema_name),
    CONSTRAINT uq_version_catalog_cas_hash UNIQUE (cas_hash)
);

CREATE INDEX IF NOT EXISTS idx_version_catalog_shape ON version_catalog (shape_signature);

CREATE TABLE IF NOT EXISTS schema_source_audit (
    audit_id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    schema_name      VARCHAR(255) NOT NULL,
    cas_hash         VARCHAR(64)  NOT NULL,
    source_text      TEXT         NOT NULL,
    created_at       TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_schema_source_audit_schema FOREIGN KEY (schema_name)
        REFERENCES version_catalog (schema_name) ON DELETE RESTRICT
);

CREATE INDEX IF NOT EXISTS idx_schema_source_audit_hash ON schema_source_audit (cas_hash);

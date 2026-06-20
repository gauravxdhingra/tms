-- Run once at DB init: enable extensions used across all TMS schemas.
-- Postgres 17 required for pg_logical (Debezium CDC).

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";      -- UUID generation
CREATE EXTENSION IF NOT EXISTS "pg_trgm";        -- trigram indexes for text search
CREATE EXTENSION IF NOT EXISTS "btree_gist";     -- GiST indexes for exclusion constraints

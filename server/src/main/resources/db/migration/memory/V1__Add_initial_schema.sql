-- Utility functions
CREATE ALIAS code_point_length DETERMINISTIC
    FOR 'app.accrescent.server.adapters.driven.datastore2.vertx.MemoryDataStore.codePointLength';

-- Organizations and users
--
-- Organizations and users have a strict one-to-one relationship
CREATE TABLE organizations (
    id varchar PRIMARY KEY,
    owner_user_id varchar,
    create_time timestamp with time zone NOT NULL
);
CREATE TABLE users (
    id varchar PRIMARY KEY,
    organization_id varchar NOT NULL UNIQUE REFERENCES organizations(id),
    create_time timestamp with time zone NOT NULL,
    github_user_id bigint NOT NULL UNIQUE,
    UNIQUE (id, organization_id)
);
ALTER TABLE organizations
    ADD CONSTRAINT fk_organizations_owner
    FOREIGN KEY (owner_user_id, id) REFERENCES users(id, organization_id);

-- Sessions
CREATE TABLE sessions (
    id_hash varbinary PRIMARY KEY CHECK (octet_length(id_hash) = 32),
    user_id varchar NOT NULL REFERENCES users(id),
    create_time timestamp with time zone NOT NULL,
    expire_time timestamp with time zone NOT NULL,
    CHECK (expire_time > create_time)
);

-- App drafts
CREATE TABLE app_drafts (
    id varchar PRIMARY KEY,
    organization_id varchar NOT NULL REFERENCES organizations(id),
    create_time timestamp with time zone NOT NULL
);
CREATE TABLE app_draft_listing_texts (
    app_draft_id varchar PRIMARY KEY REFERENCES app_drafts(id) ON DELETE CASCADE,
    name varchar NOT NULL CHECK (code_point_length(name) BETWEEN 1 AND 30),
    short_description varchar NOT NULL
        CHECK (code_point_length(short_description) BETWEEN 1 AND 80),
    -- This method of combining the listing text's name and short description is injective - which
    -- is necessary to avoid accidental collisions - because of the length prefixes
    etag varchar GENERATED ALWAYS AS (RAWTOHEX(HASH(
        'SHA-256',
        CHAR_LENGTH(name) || ':' || name
            || CHAR_LENGTH(short_description) || ':' || short_description
    )))
);

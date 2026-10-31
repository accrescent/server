-- Reusable domain types

CREATE DOMAIN stable_text
AS text
-- We must use a stable collation for indexed text columns because otherwise collation upgrades can
-- result in corrupt indexes. As per
-- https://www.postgresql.org/docs/18/collation.html#COLLATION-MANAGING-STANDARD, ucs_basic gives
-- the strongest stability guarantee of "stable across all versions", so we use that. It is the
-- safest default in case we add an index to columns later.
COLLATE "ucs_basic";

-- Organizations and users
--
-- Organizations and users have a strict one-to-one relationship.
CREATE TABLE organizations (
    id stable_text PRIMARY KEY,
    owner_user_id stable_text NOT NULL UNIQUE,
    create_time timestamp with time zone NOT NULL
);
CREATE TABLE users (
    id stable_text PRIMARY KEY,
    organization_id stable_text NOT NULL UNIQUE REFERENCES organizations(id),
    create_time timestamp with time zone NOT NULL,
    github_user_id bigint NOT NULL UNIQUE,
    UNIQUE (id, organization_id)
);
ALTER TABLE organizations
    ADD CONSTRAINT fk_organizations_owner
    FOREIGN KEY (owner_user_id, id) REFERENCES users(id, organization_id);

-- Sessions
CREATE TABLE sessions (
    id_hash bytea PRIMARY KEY CHECK (octet_length(id_hash) = 32),
    user_id stable_text NOT NULL REFERENCES users(id),
    create_time timestamp with time zone NOT NULL,
    expire_time timestamp with time zone NOT NULL,
    CHECK (expire_time > create_time)
);

-- App drafts
CREATE TABLE app_drafts (
    id stable_text PRIMARY KEY,
    organization_id stable_text NOT NULL REFERENCES organizations(id),
    create_time timestamp with time zone NOT NULL
);
CREATE TABLE app_draft_listing_texts (
    app_draft_id stable_text PRIMARY KEY REFERENCES app_drafts(id) ON DELETE CASCADE,
    -- Use the expansion of stable_text for these columns instead of stable_text directly since
    -- virtual generated columns can't use or reference columns using user-defined types in
    -- PostgreSQL 18 (see https://www.postgresql.org/docs/18/ddl-generated-columns.html)
    name text COLLATE "ucs_basic" NOT NULL CHECK (char_length(name) BETWEEN 1 AND 30),
    short_description text COLLATE "ucs_basic" NOT NULL
        CHECK (char_length(short_description) BETWEEN 1 AND 80),
    -- This method of combining the listing text's name and short description is injective - which
    -- is necessary to avoid accidental collisions - since the text type cannot contain NULL bytes
    etag text COLLATE "ucs_basic" GENERATED ALWAYS AS (encode(
        sha256(
            decode(replace(name, '\', '\\'), 'escape')
            || '\x00'::bytea
            || decode(replace(short_description, '\', '\\'), 'escape')
        ),
        'base64')
    )
);

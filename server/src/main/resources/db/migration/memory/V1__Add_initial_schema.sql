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

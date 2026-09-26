-- Reusable domain types

CREATE DOMAIN canon_text
AS text
-- We must use a stable collation for indexed text columns because otherwise collation upgrades can
-- result in corrupt indexes. As per
-- https://www.postgresql.org/docs/18/collation.html#COLLATION-MANAGING-STANDARD, ucs_basic gives
-- the strongest stability guarantee of "stable across all versions", so we use that. It is the
-- safest default in case we add an index to columns later.
COLLATE "ucs_basic"
-- We define the following constraints because:
--
-- * Empty strings are not usually useful.
-- * The W3C recommends NFC form for all content in multiple specifications and the web in
--   particular to avoid interoperability problems
--   (https://www.unicode.org/reports/tr15/tr15-57.html#Norm_Forms).
-- * NFC normalization is only forward-compatible with Unicode upgrades when the text contains only
--   assigned Unicode characters, so we need to forbid unassigned characters to ensure our NFC-only
--   constraint isn't violated after an upgrade. Note that this requirement means that data valid
--   on, e.g., a PostgreSQL 18 instance may not be valid on a PostgreSQL 17 instance because a
--   character in the data may not have been assigned in that past version.
CHECK (VALUE != '' AND VALUE IS NFC NORMALIZED AND unicode_assigned(VALUE));

CREATE DOMAIN id_text
AS canon_text
-- Requirements adapted from https://google.aip.dev/210#unique-identifiers with the hyphen
-- disallowed since it's a selection boundary when double-clicking
CHECK (char_length(VALUE) <= 64 AND VALUE ~ '^[a-zA-Z0-9_]*$');

-- External blobs
--
-- Columns referencing another table use canon_text rather than id_text since the length of a
-- foreign key's value is already restricted at the column it references.
CREATE TABLE external_blobs (
    id id_text PRIMARY KEY,
    create_time timestamp with time zone NOT NULL,
    service canon_text NOT NULL CHECK (service IN ('local', 'gcs')),
    status canon_text NOT NULL CHECK (status IN ('pending', 'committed', 'deleted')),
    bucket_name canon_text NOT NULL CHECK (char_length(bucket_name) <= 256),
    object_key canon_text NOT NULL CHECK (char_length(object_key) <= 256),
    generation bigint,
    meta_generation bigint,
    delete_time timestamp with time zone,
    app_package_id canon_text,
    pending_app_draft_upload_id canon_text,
    pending_app_draft_listing_icon_upload_id canon_text,
    UNIQUE (service, bucket_name, object_key),
    -- Required so each owning table can reference a blob's owner alongside its ID
    UNIQUE (id, app_package_id),
    UNIQUE (id, pending_app_draft_upload_id),
    UNIQUE (id, pending_app_draft_listing_icon_upload_id),
    CHECK (status != 'pending' OR generation IS NULL),
    CHECK (status != 'committed' OR generation IS NOT NULL),
    CHECK ((service = 'gcs' AND generation IS NOT NULL) = (meta_generation IS NOT NULL)),
    CHECK ((status = 'deleted') = (delete_time IS NOT NULL)),
    -- A blob is owned by at most one entity. Together with the two constraints below, this makes a
    -- deleted blob owned by nothing at all, since it is neither pending nor committed.
    CHECK (
        (CASE WHEN app_package_id IS NOT NULL THEN 1 ELSE 0 END)
        + (CASE WHEN pending_app_draft_upload_id IS NOT NULL THEN 1 ELSE 0 END)
        + (CASE WHEN pending_app_draft_listing_icon_upload_id IS NOT NULL THEN 1 ELSE 0 END)
        <= 1
    ),
    -- A pending blob is owned by a pending upload of exactly one kind
    CHECK (
        (status = 'pending') = (
            pending_app_draft_upload_id IS NOT NULL
            OR pending_app_draft_listing_icon_upload_id IS NOT NULL
        )
    ),
    -- A committed blob is owned by an app package
    CHECK ((status = 'committed') = (app_package_id IS NOT NULL))
);

-- Organizations and users
CREATE TABLE organizations (
    id id_text PRIMARY KEY,
    owner_user_id canon_text NOT NULL,
    create_time timestamp with time zone NOT NULL
);
CREATE TABLE users (
    id id_text PRIMARY KEY,
    organization_id canon_text NOT NULL UNIQUE REFERENCES organizations(id),
    create_time timestamp with time zone NOT NULL,
    github_user_id bigint NOT NULL UNIQUE,
    UNIQUE (id, organization_id)
);
ALTER TABLE organizations
    ADD CONSTRAINT organizations_owner_user_fk
    FOREIGN KEY (owner_user_id, id) REFERENCES users(id, organization_id);

-- App drafts and their listings
CREATE TABLE app_drafts (
    id id_text PRIMARY KEY,
    organization_id canon_text NOT NULL REFERENCES organizations(id),
    create_time timestamp with time zone NOT NULL,
    default_app_draft_listing_id canon_text,
    app_package_id canon_text,
    submit_time timestamp with time zone,
    -- Required so app packages can reference their app draft alongside that draft's package ID.
    -- This is what caps an app draft at one app package: a second package for the same draft would
    -- have to be referenced by the same single-valued column.
    UNIQUE (id, app_package_id),
    -- A submitted app draft must have both an app package and a default listing
    CHECK (submit_time IS NULL OR app_package_id IS NOT NULL),
    CHECK (submit_time IS NULL OR default_app_draft_listing_id IS NOT NULL)
);
CREATE TABLE app_draft_listings (
    id id_text PRIMARY KEY,
    app_draft_id canon_text NOT NULL REFERENCES app_drafts(id) ON DELETE CASCADE,
    language canon_text NOT NULL CHECK (language IN ('en-US')),
    name canon_text NOT NULL CHECK (char_length(name) <= 30),
    short_description canon_text NOT NULL CHECK (char_length(short_description) <= 80),
    -- Only one listing is allowed per language per app draft
    UNIQUE (app_draft_id, language),
    -- Required so app drafts can reference a listing's app draft alongside its ID
    UNIQUE (app_draft_id, id)
);
-- Ensure an app draft's default listing 1) points to an actual app draft listing and 2) points to
-- an app draft listing _for this app draft_.
ALTER TABLE app_drafts
    ADD CONSTRAINT app_drafts_default_listing_fk
    FOREIGN KEY (id, default_app_draft_listing_id)
    REFERENCES app_draft_listings(app_draft_id, id);

-- App packages and their permissions
CREATE TABLE app_packages (
    id id_text PRIMARY KEY,
    app_draft_id canon_text NOT NULL,
    external_blob_id canon_text NOT NULL,
    upload_event_time timestamp with time zone NOT NULL,
    app_id canon_text NOT NULL CHECK (char_length(app_id) <= 128),
    version_code integer NOT NULL CHECK (version_code BETWEEN 1 AND 2100000000),
    version_name canon_text NOT NULL CHECK (char_length(version_name) <= 1024),
    target_sdk integer NOT NULL CHECK (target_sdk > 0),
    signer_certificate bytea NOT NULL,
    build_apks_result bytea NOT NULL,
    -- Required so app drafts can reference a package's app draft alongside its ID
    UNIQUE (app_draft_id, id),
    -- Required so external blobs can reference a package's blob alongside its ID
    UNIQUE (id, external_blob_id),
    FOREIGN KEY (app_draft_id, id) REFERENCES app_drafts(id, app_package_id),
    FOREIGN KEY (external_blob_id, id) REFERENCES external_blobs(id, app_package_id)
);
-- Ensure an app draft's package 1) points to an actual app package and 2) points to an app package
-- _for this app draft_.
ALTER TABLE app_drafts
    ADD CONSTRAINT app_drafts_app_package_fk
    FOREIGN KEY (id, app_package_id) REFERENCES app_packages(app_draft_id, id);
-- Ensure a committed blob's owning app package 1) exists and 2) points back to this blob
ALTER TABLE external_blobs
    ADD CONSTRAINT external_blobs_app_package_fk
    FOREIGN KEY (app_package_id, id) REFERENCES app_packages(id, external_blob_id);

CREATE TABLE app_package_permissions (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    app_package_id canon_text NOT NULL REFERENCES app_packages(id) ON DELETE CASCADE,
    name canon_text NOT NULL CHECK (char_length(name) <= 1024),
    max_sdk_version integer CHECK (max_sdk_version > 0),
    UNIQUE (app_package_id, name)
);

-- Published apps and their listings
CREATE TABLE apps (
    id id_text PRIMARY KEY,
    organization_id canon_text NOT NULL REFERENCES organizations(id),
    default_app_listing_id canon_text NOT NULL,
    publicly_listed boolean NOT NULL
);
CREATE TABLE app_listings (
    id id_text PRIMARY KEY,
    app_id canon_text NOT NULL REFERENCES apps(id),
    language canon_text NOT NULL CHECK (language IN ('en-US')),
    -- Only one listing is allowed per language per app
    UNIQUE (app_id, language),
    -- Required so apps can reference a listing's app alongside its ID
    UNIQUE (app_id, id)
);
-- Ensure an app's default listing 1) points to an actual app listing and 2) points to an app
-- listing _for this app_
ALTER TABLE apps
    ADD CONSTRAINT apps_default_listing_fk
    FOREIGN KEY (id, default_app_listing_id) REFERENCES app_listings(app_id, id);

-- Pending uploads
CREATE TABLE pending_app_draft_uploads (
    id id_text PRIMARY KEY,
    app_draft_id canon_text NOT NULL UNIQUE REFERENCES app_drafts(id),
    external_blob_id canon_text,
    object_key canon_text NOT NULL UNIQUE CHECK (char_length(object_key) <= 256),
    create_time timestamp with time zone NOT NULL,
    processing_result canon_text
        CHECK (processing_result IN (
            'success',
            'app_draft_submitted',
            'apk_set_invalid_format',
            'apk_set_io_error',
            'apk_set_no_modern_signature',
            'apk_set_signed_with_debug_cert',
            'apk_set_signed_with_multiple_certs',
            'apk_set_unverified',
            'apk_set_test_only',
            'apk_set_debuggable',
            'apk_set_missing_64_bit_code',
            'apk_set_low_target_sdk',
            'apk_set_duplicate_permission',
            'apk_set_invalid_application_id',
            'apk_set_multiple_application_elements',
            'apk_set_multiple_uses_sdk_elements',
            'apk_set_no_version_code',
            'apk_set_permission_max_sdk_out_of_range',
            'apk_set_permission_name_too_long',
            'apk_set_version_code_out_of_range',
            'apk_set_version_code_major_non_zero',
            'apk_set_version_name_too_long'
        )),
    -- Required so external blobs can reference an upload's blob alongside its ID
    UNIQUE (id, external_blob_id),
    -- A pending app draft upload owns exactly one external blob if and only if it has not been
    -- completed. Completing an upload therefore hands its blob off to an app package or releases it
    -- for deletion.
    CHECK ((processing_result IS NULL) = (external_blob_id IS NOT NULL)),
    -- The owned blob must reference this upload back. The blob's own constraints then guarantee it
    -- is pending, since only a pending blob may be owned by a pending upload.
    FOREIGN KEY (external_blob_id, id)
        REFERENCES external_blobs(id, pending_app_draft_upload_id)
);
-- Ensure a pending blob's owning app draft upload 1) exists and 2) points back to this blob
ALTER TABLE external_blobs
    ADD CONSTRAINT external_blobs_pending_app_draft_upload_fk
    FOREIGN KEY (pending_app_draft_upload_id, id)
    REFERENCES pending_app_draft_uploads(id, external_blob_id);

CREATE TABLE pending_app_draft_listing_icon_uploads (
    id id_text PRIMARY KEY,
    app_draft_listing_id canon_text NOT NULL UNIQUE REFERENCES app_draft_listings(id),
    external_blob_id canon_text,
    object_key canon_text NOT NULL UNIQUE CHECK (char_length(object_key) <= 256),
    create_time timestamp with time zone NOT NULL,
    processing_result canon_text
        CHECK (processing_result IN (
            'success',
            'app_draft_submitted',
            'invalid_image',
            'incorrect_image_dimensions'
        )),
    -- Required so external blobs can reference an upload's blob alongside its ID
    UNIQUE (id, external_blob_id),
    -- A pending app draft listing icon upload owns exactly one external blob if and only if it has
    -- not been completed
    CHECK ((processing_result IS NULL) = (external_blob_id IS NOT NULL)),
    -- The owned blob must reference this upload back. The blob's own constraints then guarantee it
    -- is pending, since only a pending blob may be owned by a pending upload.
    FOREIGN KEY (external_blob_id, id)
        REFERENCES external_blobs(id, pending_app_draft_listing_icon_upload_id)
);
-- Ensure a pending blob's owning app draft listing icon upload 1) exists and 2) points back to this
-- blob
ALTER TABLE external_blobs
    ADD CONSTRAINT external_blobs_pending_app_draft_listing_icon_upload_fk
    FOREIGN KEY (pending_app_draft_listing_icon_upload_id, id)
    REFERENCES pending_app_draft_listing_icon_uploads(id, external_blob_id);

-- Sessions
CREATE TABLE sessions (
    id_hash bytea PRIMARY KEY CHECK (octet_length(id_hash) = 32),
    user_id canon_text NOT NULL REFERENCES users(id),
    create_time timestamp with time zone NOT NULL,
    expire_time timestamp with time zone NOT NULL,
    CHECK (expire_time > create_time)
);

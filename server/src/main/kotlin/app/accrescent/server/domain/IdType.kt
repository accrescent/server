// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain

/**
 * A type of resource identifier.
 *
 * Each type represents a different kind of resource.
 */
enum class IdType {
    APP,
    APP_DRAFT,
    APP_DRAFT_LISTING,
    APP_PACKAGE,
    BLOB_OBJECT_KEY,
    EXTERNAL_BLOB,
    ORGANIZATION,
    PENDING_APP_DRAFT_UPLOAD,
    PENDING_APP_DRAFT_LISTING_ICON_UPLOAD,
    SESSION,
    USER,
}
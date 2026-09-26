// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.ports.driven.datastore

sealed class HasPermissionRequest(val resourceId: String, val subjectId: String) {
    data class CreateAppDraft(private val organizationId: String, private val userId: String) :
        HasPermissionRequest(organizationId, userId)

    data class CreateAppDraftListing(private val appDraftId: String, private val userId: String) :
        HasPermissionRequest(appDraftId, userId)

    data class DeleteAppDraft(private val appDraftId: String, private val userId: String) :
        HasPermissionRequest(appDraftId, userId)

    data class DeleteAppDraftListing(private val appDraftListingId: String, private val userId: String) :
        HasPermissionRequest(appDraftListingId, userId)

    data class DownloadAppDraft(private val appDraftId: String, private val userId: String) :
        HasPermissionRequest(appDraftId, userId)

    data class DownloadAppDraftListingIcon(private val listingId: String, private val userId: String) :
        HasPermissionRequest(listingId, userId)

    data class ReplaceAppDraftPackage(private val appDraftId: String, private val userId: String) :
        HasPermissionRequest(appDraftId, userId)

    data class SubmitAppDraft(private val appDraftId: String, private val userId: String) :
        HasPermissionRequest(appDraftId, userId)

    data class UpdateApp(private val appId: String, private val userId: String) :
        HasPermissionRequest(appId, userId)

    data class UpdateAppDraft(private val appDraftId: String, private val userId: String) :
        HasPermissionRequest(appDraftId, userId)

    data class UpdateAppDraftListing(private val listingId: String, private val userId: String) :
        HasPermissionRequest(listingId, userId)

    data class UploadAppDraftListingIcon(private val listingId: String, private val userId: String) :
        HasPermissionRequest(listingId, userId)

    data class ViewApp(private val appId: String, private val userId: String) :
        HasPermissionRequest(appId, userId)

    data class ViewAppDraft(private val appDraftId: String, private val userId: String) :
        HasPermissionRequest(appDraftId, userId)

    data class ViewAppDraftListing(private val listingId: String, private val userId: String) :
        HasPermissionRequest(listingId, userId)
}
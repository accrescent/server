// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.ports.driving.api.console

import arrow.core.Either

interface AppDraftApi {
    fun createAppDraft(
        context: CallContext,
        request: CreateAppDraftRequest,
    ): Either<CreateAppDraftError, CreateAppDraftResponse>

    fun getAppDraft(
        context: CallContext,
        request: GetAppDraftRequest,
    ): Either<GetAppDraftError, GetAppDraftResponse>

    fun listAppDrafts(
        context: CallContext,
        request: ListAppDraftsRequest,
    ): Either<ListAppDraftsError, ListAppDraftsResponse>

    fun uploadAppDraft(
        context: CallContext,
        request: UploadAppDraftRequest,
    ): Either<UploadAppDraftError, UploadAppDraftResponse>

    fun downloadAppDraft(
        context: CallContext,
        request: DownloadAppDraftRequest,
    ): Either<DownloadAppDraftError, DownloadAppDraftResponse>

    fun updateAppDraft(
        context: CallContext,
        request: UpdateAppDraftRequest,
    ): Either<UpdateAppDraftError, Unit>

    fun submitAppDraft(
        context: CallContext,
        request: SubmitAppDraftRequest,
    ): Either<SubmitAppDraftError, Unit>

    fun deleteAppDraft(
        context: CallContext,
        request: DeleteAppDraftRequest,
    ): Either<DeleteAppDraftError, Unit>

    fun createAppDraftListing(
        context: CallContext,
        request: CreateAppDraftListingRequest,
    ): Either<CreateAppDraftListingError, CreateAppDraftListingResponse>

    fun getAppDraftListing(
        context: CallContext,
        request: GetAppDraftListingRequest,
    ): Either<GetAppDraftListingError, GetAppDraftListingResponse>

    /**
     * Lists app draft listings for a given app draft.
     *
     * Returned listings are sorted alphabetically by their language's BCP-47 code.
     */
    fun listAppDraftListings(
        context: CallContext,
        request: ListAppDraftListingsRequest,
    ): Either<ListAppDraftListingsError, ListAppDraftListingsResponse>

    fun updateAppDraftListing(
        context: CallContext,
        request: UpdateAppDraftListingRequest,
    ): Either<UpdateAppDraftListingError, Unit>

    /**
     * Requests an upload for an app draft listing's icon.
     */
    fun uploadAppDraftListingIcon(
        context: CallContext,
        request: UploadAppDraftListingIconRequest,
    ): Either<UploadAppDraftListingIconError, UploadAppDraftListingIconResponse>

    /**
     * Requests a download for an app draft listing's icon.
     */
    fun downloadAppDraftListingIcon(
        context: CallContext,
        request: DownloadAppDraftListingIconRequest,
    ): Either<DownloadAppDraftListingIconError, DownloadAppDraftListingIconResponse>

    /**
     * Deletes an app draft listing.
     *
     * If the listing is its app draft's default, the app draft's default listing is unset.
     */
    fun deleteAppDraftListing(
        context: CallContext,
        request: DeleteAppDraftListingRequest,
    ): Either<DeleteAppDraftListingError, Unit>

    /**
     * Publishes a given app draft.
     */
    fun publishAppDraft(
        context: CallContext,
        request: PublishAppDraftRequest,
    ): Either<PublishAppDraftError, PublishAppDraftResponse>
}

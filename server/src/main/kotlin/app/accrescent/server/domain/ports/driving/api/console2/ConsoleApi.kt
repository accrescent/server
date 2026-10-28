// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.ports.driving.api.console2

import arrow.core.Either

/**
 * The Accrescent console API.
 */
interface ConsoleApi {
    /**
     * Creates an app draft.
     *
     * @param context the context for this call.
     * @param request the request parameters.
     */
    suspend fun createAppDraft(
        context: CallContext,
        request: CreateAppDraftRequest,
    ): Either<CreateAppDraftError, CreateAppDraftResponse>

    /**
     * Deletes an app draft.
     *
     * @param context the context for this call.
     * @param request the request parameters.
     */
    suspend fun deleteAppDraft(
        context: CallContext,
        request: DeleteAppDraftRequest,
    ): Either<DeleteAppDraftError, Unit>

    /**
     * Gets the organization of the currently authenticated user.
     *
     * @param context the context for this call.
     */
    suspend fun getMyOrganization(
        context: CallContext,
    ): Either<GetMyOrganizationError, GetMyOrganizationResponse>

    /**
     * Gets an organization.
     *
     * @param context the context for this call.
     * @param request the request parameters.
     */
    suspend fun getOrganization(
        context: CallContext,
        request: GetOrganizationRequest,
    ): Either<GetOrganizationError, GetOrganizationResponse>

    /**
     * Gets the currently authenticated user.
     *
     * @param context the context for this call.
     */
    suspend fun getSelf(context: CallContext): Either<GetSelfError, GetSelfResponse>

    /**
     * Lists app draft summaries for a given organization.
     *
     * App draft summaries are listed from newest to oldest.
     *
     * @param context the context for this call.
     * @param request the request parameters.
     */
    suspend fun listAppDraftSummaries(
        context: CallContext,
        request: ListAppDraftSummariesRequest,
    ): Either<ListAppDraftSummariesError, ListAppDraftSummariesResponse>
}

// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.ports.driven.datastore2

/**
 * A request to check whether a subject has a given permission on a given resource.
 *
 * @property resourceId the ID of the resource the permission applies to.
 * @property subjectId the ID of the subject to check the permission of.
 */
sealed class HasPermissionRequest(val resourceId: String, val subjectId: String) {
    /**
     * The permission to create app drafts in an organization.
     *
     * @param organizationId the ID of the organization to create app drafts in.
     * @param userId the ID of the user to check the permission of.
     */
    data class CreateAppDraft(private val organizationId: String, private val userId: String) :
        HasPermissionRequest(organizationId, userId)

    /**
     * The permission to view an organization.
     *
     * @param organizationId the ID of the organization to view.
     * @param userId the ID of the user to check the permission of.
     */
    data class ViewOrganization(private val organizationId: String, private val userId: String) :
        HasPermissionRequest(organizationId, userId)
}

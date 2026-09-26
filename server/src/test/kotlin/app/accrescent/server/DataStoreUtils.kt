// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server

import app.accrescent.server.adapters.driven.timestampsource.FixedTimestampSource
import app.accrescent.server.core.Bytes
import app.accrescent.server.core.unwrap
import app.accrescent.server.domain.android.ApplicationId
import app.accrescent.server.domain.android.NameAttribute
import app.accrescent.server.domain.android.SdkVersion
import app.accrescent.server.domain.android.VersionCode
import app.accrescent.server.domain.android.VersionName
import app.accrescent.server.domain.appstore.ListingLanguage
import app.accrescent.server.domain.authn.ExternalUserId
import app.accrescent.server.domain.crypto.Sha256Hash
import app.accrescent.server.domain.ports.driven.datastore.AppDraftApiView
import app.accrescent.server.domain.ports.driven.datastore.AppDraftListingApiView
import app.accrescent.server.domain.ports.driven.datastore.AppPackage
import app.accrescent.server.domain.ports.driven.datastore.AppPackageApiView
import app.accrescent.server.domain.ports.driven.datastore.DataStore
import app.accrescent.server.domain.ports.driven.datastore.DataStoreResult
import app.accrescent.server.domain.ports.driven.datastore.ExternalBlob
import app.accrescent.server.domain.ports.driven.datastore.PendingAppDraftListingIconUpload
import app.accrescent.server.domain.ports.driven.datastore.PendingAppDraftUpload
import arrow.core.None
import arrow.core.Option
import arrow.core.raise.either
import java.time.OffsetDateTime

val UNIX_EPOCH = FixedTimestampSource().now()

fun appDraftListingApiView(
    id: String = "appDraftListing1",
    appDraftId: String = "appDraft1",
    language: ListingLanguage = ListingLanguage.EN_US,
    name: String = "Example App",
    shortDescription: String = "Example Short Description",
): AppDraftListingApiView {
    return AppDraftListingApiView(
        id = id,
        appDraftId = appDraftId,
        language = language,
        name = name,
        shortDescription = shortDescription,
    )
}

fun createAppDraftListing(
    tx: DataStore.Transaction,
    id: String = "appDraftListing1",
    appDraftId: String = "appDraft1",
    language: ListingLanguage = ListingLanguage.EN_US,
    name: String = "Example App",
    shortDescription: String = "Example Short Description",
): DataStoreResult<Unit> {
    return tx.appDrafts.createListing(
        id = id,
        appDraftId = appDraftId,
        language = language,
        name = name,
        shortDescription = shortDescription,
    )
}

fun signInNewUser(
    tx: DataStore.Transaction,
    userId: String = "user1",
    organizationId: String = "org1",
    externalUserId: ExternalUserId = ExternalUserId.Github(1),
    sessionId: String = "session1",
): DataStoreResult<Unit> = either {
    tx.organizations.saveWithOwner(organizationId, userId, externalUserId, UNIX_EPOCH).bind()
    tx.sessions
        .create(Sha256Hash.hash(sessionId.toByteArray()), userId, UNIX_EPOCH, UNIX_EPOCH.plusDays(1))
        .bind()
}

fun appPackage(
    id: String = "appPackage1",
    appDraftId: String = "appDraft1",
    externalBlobId: String = "blob1",
    uploadEventTime: OffsetDateTime = UNIX_EPOCH,
    appId: ApplicationId = ApplicationId.fromString("com.example.app").unwrap(),
    versionCode: VersionCode = VersionCode.fromInt(1).unwrap(),
    versionName: VersionName = VersionName.fromString("1.0").unwrap(),
    targetSdk: SdkVersion = SdkVersion.fromInt(37).unwrap(),
    signingCertificate: Bytes = Bytes(byteArrayOf(1, 2, 3)),
    buildApksResult: Bytes = Bytes(byteArrayOf(4, 5, 6)),
): AppPackage {
    return AppPackage(
        id = id,
        appDraftId = appDraftId,
        externalBlobId = externalBlobId,
        uploadEventTime = uploadEventTime,
        appId = appId,
        versionCode = versionCode,
        versionName = versionName,
        targetSdk = targetSdk,
        signerCertificate = signingCertificate,
        buildApksResult = buildApksResult,
    )
}

/**
 * Saves an app package for an existing app draft by way of the pending upload it must come from.
 *
 * An app package can only enter the data store by committing a pending app draft upload, since a
 * committed external blob is always one an app package took over from an upload. Tests needing a
 * package therefore have to create that upload and its blob first, which this does for them.
 *
 * @param tx the transaction to save the app package within.
 * @param appPackage the app package to save.
 * @param permissions the permissions to save as part of the app package.
 * @param pendingUploadId the ID to give the pending upload the package is committed from.
 * @param bucketName the bucket to place the package's blob in.
 * @param objectKey the object key to give both the pending upload and the package's blob.
 */
fun saveAppPackageFromNewUpload(
    tx: DataStore.Transaction,
    appPackage: AppPackage = appPackage(),
    permissions: Map<NameAttribute, Option<SdkVersion>> = emptyMap(),
    pendingUploadId: String = "appDraftUpload1",
    bucketName: String = "bucket1",
    objectKey: String = "object1",
): DataStoreResult<Unit> = either {
    tx.appDrafts
        .saveUpload(
            incompletePendingAppDraftUpload(
                id = pendingUploadId,
                appDraftId = appPackage.appDraftId,
                externalBlobId = appPackage.externalBlobId,
                objectKey = objectKey,
                createTime = UNIX_EPOCH,
            ),
            pendingExternalBlob(
                id = appPackage.externalBlobId,
                bucketName = bucketName,
                objectKey = objectKey,
                createTime = UNIX_EPOCH,
            ),
        )
        .bind()
    tx.appPackages
        .saveFromPendingUpload(
            pendingUploadId = pendingUploadId,
            appPackage = appPackage,
            permissions = permissions,
            blobVersion = ExternalBlob.LocalBlobVersion(1),
            replacedBlobDeleteTime = UNIX_EPOCH,
        )
        .bind()
}

fun committedExternalBlob(
    id: String = "blob1",
    bucketName: String = "bucket1",
    objectKey: String = "object1",
    createTime: OffsetDateTime = UNIX_EPOCH,
    generation: Long = 1,
): ExternalBlob<ExternalBlob.Status.Committed<*>> {
    return ExternalBlob.Local(
        id = id,
        createTime = createTime,
        bucketName = bucketName,
        objectKey = objectKey,
        status = ExternalBlob.Status.Committed(ExternalBlob.LocalBlobVersion(generation)),
    )
}

fun pendingExternalBlob(
    id: String = "blob1",
    bucketName: String = "bucket1",
    objectKey: String = "object1",
    createTime: OffsetDateTime = UNIX_EPOCH,
): ExternalBlob<ExternalBlob.Status.Pending> {
    return ExternalBlob.Local(
        id = id,
        createTime = createTime,
        bucketName = bucketName,
        objectKey = objectKey,
        status = ExternalBlob.Status.Pending,
    )
}

fun incompletePendingAppDraftUpload(
    id: String = "appDraftUpload1",
    appDraftId: String = "appDraft1",
    externalBlobId: String = "blob1",
    objectKey: String = "object1",
    createTime: OffsetDateTime = UNIX_EPOCH,
): PendingAppDraftUpload.Incomplete {
    return PendingAppDraftUpload.Incomplete(
        id = id,
        appDraftId = appDraftId,
        objectKey = objectKey,
        createTime = createTime,
        externalBlobId = externalBlobId,
    )
}

fun incompletePendingAppDraftListingIconUpload(
    id: String = "adliu1",
    appDraftListingId: String = "appDraftListing1",
    externalBlobId: String = "blob1",
    objectKey: String = "object1",
    createTime: OffsetDateTime = UNIX_EPOCH,
): PendingAppDraftListingIconUpload.Incomplete {
    return PendingAppDraftListingIconUpload.Incomplete(
        id = id,
        appDraftListingId = appDraftListingId,
        objectKey = objectKey,
        createTime = createTime,
        externalBlobId = externalBlobId,
    )
}

fun unsubmittedAppDraftApiView(
    id: String = "appDraft1",
    defaultAppDraftListingId: Option<String> = None,
    appPackage: Option<AppPackageApiView> = None,
): AppDraftApiView.Unsubmitted {
    return AppDraftApiView.Unsubmitted(
        id = id,
        createTime = UNIX_EPOCH,
        defaultAppDraftListingId = defaultAppDraftListingId,
        appPackage = appPackage,
    )
}

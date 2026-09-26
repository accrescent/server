// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.async

import app.accrescent.server.UNIX_EPOCH
import app.accrescent.server.VALID_TOC_BASE64
import app.accrescent.server.adapters.driven.blobstorage.LocalBlobStorage
import app.accrescent.server.adapters.driven.blobstorage.LocalOnlyBlobStorage
import app.accrescent.server.adapters.driven.datastore.jdbc.InMemoryDataStore
import app.accrescent.server.adapters.driven.file.LocalTempFile
import app.accrescent.server.adapters.driven.randomsource.DeterministicRandomSource
import app.accrescent.server.adapters.driven.timestampsource.FixedTimestampSource
import app.accrescent.server.appPackage
import app.accrescent.server.core.Bytes
import app.accrescent.server.core.unwrap
import app.accrescent.server.core.unwrap2
import app.accrescent.server.core.unwrapErr
import app.accrescent.server.createAppDraftListing
import app.accrescent.server.domain.IdGenerator
import app.accrescent.server.domain.android.ApkSetParseError
import app.accrescent.server.domain.android.ApplicationId
import app.accrescent.server.domain.android.SdkVersion
import app.accrescent.server.domain.android.VersionCode
import app.accrescent.server.domain.android.VersionName
import app.accrescent.server.domain.authn.ExternalUserId
import app.accrescent.server.domain.ports.driven.blobstorage.BlobId
import app.accrescent.server.domain.ports.driven.datastore.AppDraftApiView
import app.accrescent.server.domain.ports.driven.datastore.AppDraftUploadProcessingError
import app.accrescent.server.domain.ports.driven.datastore.AppPackage
import app.accrescent.server.domain.ports.driven.datastore.AppPackageApiView
import app.accrescent.server.domain.ports.driven.datastore.DataStore
import app.accrescent.server.domain.ports.driven.datastore.ExternalBlob
import app.accrescent.server.domain.ports.driven.randomsource.RandomSource
import app.accrescent.server.domain.ports.driven.timestampsource.TimestampSource
import app.accrescent.server.domain.ports.driving.async.UploadEvent
import app.accrescent.server.domain.ports.driving.async.UploadProcessingError
import app.accrescent.server.incompletePendingAppDraftUpload
import app.accrescent.server.pendingExternalBlob
import app.accrescent.server.saveAppPackageFromNewUpload
import arrow.core.Some
import arrow.core.left
import arrow.core.right
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.io.encoding.Base64

private fun BlobId.Local.toUploadEvent(eventTime: OffsetDateTime = UNIX_EPOCH): UploadEvent.Local {
    return UploadEvent.Local(location.bucketName, location.objectKey, eventTime, version.generation)
}

private fun validApkSetPath(): Path {
    return Path.of(
        requireNotNull(System.getProperty("testdata.apkset.valid.path")) {
            "APK set test data was not built; run this test via the Gradle 'test' task"
        }
    )
}

private fun uploadEventProcessor(
    dataStore: DataStore,
    blobStorage: LocalBlobStorage,
    randomSource: RandomSource,
    downloadDir: Path,
    timestampSource: TimestampSource = FixedTimestampSource(),
): UploadEventProcessorImpl {
    return UploadEventProcessorImpl(
        dataStore,
        LocalOnlyBlobStorage(blobStorage),
        IdGenerator(randomSource),
        timestampSource,
        LocalTempFile,
        downloadDir,
    )
}

// Uploads the valid APK set to its public upload location and records an unsubmitted app draft with
// no existing package plus a pending upload targeting a reserved private location. Returns the
// upload event corresponding to the uploaded blob.
private fun seedSuccessfulUpload(
    dataStore: DataStore,
    blobStorage: LocalBlobStorage,
): UploadEvent.Local {
    val blobId = blobStorage
        .upload(validApkSetPath(), BlobId.Location("uploads", "upload1"))
        .unwrap()
    dataStore.runTxWithRetry { tx ->
        tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
        tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
        tx.appDrafts.saveUpload(
            incompletePendingAppDraftUpload(objectKey = "upload1"),
            pendingExternalBlob(bucketName = "private", objectKey = "reserved1"),
        )
            .bind()
    }
        .unwrap2()
    return blobId.toUploadEvent()
}

class UploadEventProcessorImplTest {
    @Test
    fun `processAppDraftUpload acks and returns NoPendingUpload when no pending upload exists`(
        @TempDir downloadDir: Path,
    ) {
        val randomSource = DeterministicRandomSource()
        LocalBlobStorage(randomSource).use { blobStorage ->
            InMemoryDataStore(randomSource).use { dataStore ->
                dataStore.migrateToHead().unwrap()
                val processor = uploadEventProcessor(dataStore, blobStorage, randomSource, downloadDir)
                val event = UploadEvent.Local("bucket1", "object1", UNIX_EPOCH, 1L)

                var acked = false
                val result = processor.processAppDraftUpload(event) { acked = true }

                assertTrue(acked)
                assertEquals(UploadProcessingError.NoPendingUpload, result.unwrapErr())
            }
        }
    }

    @Test
    fun `processAppDraftUpload acks and returns AlreadyProcessed when pending upload already has a result`(
        @TempDir downloadDir: Path,
    ) {
        val randomSource = DeterministicRandomSource()
        LocalBlobStorage(randomSource).use { blobStorage ->
            InMemoryDataStore(randomSource).use { dataStore ->
                dataStore.migrateToHead().unwrap()
                dataStore.runTxWithRetry { tx ->
                    tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                    tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                    tx.appDrafts
                        .saveUpload(incompletePendingAppDraftUpload(), pendingExternalBlob())
                        .bind()
                    tx.appDrafts
                        .completePendingUpload(
                            "appDraftUpload1",
                            AppDraftUploadProcessingError.AppDraftSubmitted,
                            UNIX_EPOCH,
                        )
                        .bind()
                }
                    .unwrap2()
                val processor = uploadEventProcessor(dataStore, blobStorage, randomSource, downloadDir)
                val event = UploadEvent.Local("bucket1", "object1", UNIX_EPOCH, 1L)

                var acked = false
                val result = processor.processAppDraftUpload(event) { acked = true }

                assertTrue(acked)
                assertEquals(UploadProcessingError.AlreadyProcessed, result.unwrapErr())
            }
        }
    }

    @Test
    fun `processAppDraftUpload acks and returns StaleEvent when event is not newer than current package's upload event time`(
        @TempDir downloadDir: Path,
    ) {
        val randomSource = DeterministicRandomSource()
        LocalBlobStorage(randomSource).use { blobStorage ->
            InMemoryDataStore(randomSource).use { dataStore ->
                dataStore.migrateToHead().unwrap()
                dataStore.runTxWithRetry { tx ->
                    tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                    tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                    saveAppPackageFromNewUpload(
                        tx,
                        appPackage = appPackage(uploadEventTime = UNIX_EPOCH.plusSeconds(1)),
                        objectKey = "staleUpload1",
                    )
                        .bind()
                    tx.appDrafts.deletePendingUploadByAppDraftId("appDraft1", UNIX_EPOCH).bind()
                    tx.appDrafts.saveUpload(
                        incompletePendingAppDraftUpload(externalBlobId = "blob2"),
                        pendingExternalBlob(id = "blob2"),
                    )
                        .bind()
                }
                    .unwrap2()
                val processor = uploadEventProcessor(dataStore, blobStorage, randomSource, downloadDir)
                val event = UploadEvent.Local("bucket1", "object1", UNIX_EPOCH, 1L)

                var acked = false
                val result = processor.processAppDraftUpload(event) { acked = true }

                assertTrue(acked)
                assertEquals(UploadProcessingError.StaleEvent, result.unwrapErr())
            }
        }
    }

    @Test
    fun `processAppDraftUpload acks and saves AppDraftSubmitted if app draft is already submitted`(
        @TempDir downloadDir: Path,
    ) {
        val randomSource = DeterministicRandomSource()
        LocalBlobStorage(randomSource).use { blobStorage ->
            InMemoryDataStore(randomSource).use { dataStore ->
                dataStore.migrateToHead().unwrap()
                dataStore.runTxWithRetry { tx ->
                    tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                    tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                    saveAppPackageFromNewUpload(tx).bind()
                    createAppDraftListing(tx).bind()
                    tx.appDrafts.updateDefaultListing("appDraft1", Some("appDraftListing1")).bind()
                    tx.appDrafts.updateSubmitTime("appDraft1", UNIX_EPOCH).bind()
                    tx.appDrafts.deletePendingUploadByAppDraftId("appDraft1", UNIX_EPOCH).bind()
                    // The released blob keeps its storage location, so the replacement upload
                    // reserves a different one
                    tx.appDrafts.saveUpload(
                        incompletePendingAppDraftUpload(externalBlobId = "blob2"),
                        pendingExternalBlob(id = "blob2", objectKey = "object2"),
                    )
                        .bind()
                }
                    .unwrap2()
                val processor = uploadEventProcessor(dataStore, blobStorage, randomSource, downloadDir)
                // Newer than the package's upload event time so the event isn't treated as stale
                val event = UploadEvent.Local("bucket1", "object1", UNIX_EPOCH.plusSeconds(1), 1L)

                var acked = false
                processor.processAppDraftUpload(event) { acked = true }.unwrap()
                val upload = dataStore.runTxWithRetry { tx ->
                    tx.appDrafts.findPendingUploadByObjectKey("object1").bind()
                }
                    .unwrap2()
                    .unwrap()

                assertTrue(acked)
                assertEquals(Some(AppDraftUploadProcessingError.AppDraftSubmitted.left()), upload.optionalResult)
            }
        }
    }

    @Test
    fun `processAppDraftUpload acks and returns BlobNotFound when the object is not in storage`(
        @TempDir downloadDir: Path,
    ) {
        val randomSource = DeterministicRandomSource()
        LocalBlobStorage(randomSource).use { blobStorage ->
            InMemoryDataStore(randomSource).use { dataStore ->
                dataStore.migrateToHead().unwrap()
                // A pending upload exists for the blob, but the object blob was never actually
                // uploaded to blob storage
                dataStore.runTxWithRetry { tx ->
                    tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                    tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                    tx.appDrafts
                        .saveUpload(incompletePendingAppDraftUpload(), pendingExternalBlob())
                        .bind()
                }
                    .unwrap2()
                val processor = uploadEventProcessor(dataStore, blobStorage, randomSource, downloadDir)
                val event = UploadEvent.Local("bucket1", "object1", UNIX_EPOCH, 1L)

                var acked = false
                val result = processor.processAppDraftUpload(event) { acked = true }

                assertTrue(acked)
                assertEquals(UploadProcessingError.BlobNotFound, result.unwrapErr())
            }
        }
    }

    @Test
    fun `processAppDraftUpload acks and persists ApkSetParseFailed when the APK set fails to parse`(
        @TempDir downloadDir: Path,
    ) {
        val apkSetPath = Path.of(
            requireNotNull(System.getProperty("testdata.apkset.low-target-sdk.path")) {
                "APK set test data was not built; run this test via the Gradle 'test' task"
            }
        )
        val randomSource = DeterministicRandomSource()
        LocalBlobStorage(randomSource).use { blobStorage ->
            InMemoryDataStore(randomSource).use { dataStore ->
                dataStore.migrateToHead().unwrap()
                // Upload the APK set so the processor can download it from object storage
                val blobId = blobStorage
                    .upload(apkSetPath, BlobId.Location("b1", "o1"))
                    .unwrap()
                dataStore.runTxWithRetry { tx ->
                    tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                    tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                    tx.appDrafts
                        .saveUpload(
                            incompletePendingAppDraftUpload(objectKey = "o1"),
                            pendingExternalBlob(),
                        )
                        .bind()
                }
                    .unwrap2()
                val processor = uploadEventProcessor(
                    dataStore,
                    blobStorage,
                    randomSource,
                    downloadDir,
                    // A time after MinTargetSdkEvaluator's most recent threshold, so the
                    // low-target-sdk test data's target SDK actually violates the policy
                    FixedTimestampSource(OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)),
                )
                val event = blobId.toUploadEvent()

                var acked = false
                processor.processAppDraftUpload(event) { acked = true }.unwrap()
                val upload = dataStore.runTxWithRetry { tx ->
                    tx.appDrafts
                        .findPendingUploadByObjectKey("o1")
                        .bind()
                }
                    .unwrap2()
                    .unwrap()

                assertTrue(acked)
                assertEquals(
                    Some(
                        AppDraftUploadProcessingError.ApkSetParseFailed(
                            ApkSetParseError.Policy.LowTargetSdk,
                        ).left(),
                    ),
                    upload.optionalResult,
                )
            }
        }
    }

    @Test
    fun `processAppDraftUpload acks and returns success when processing succeeds`(
        @TempDir downloadDir: Path,
    ) {
        val randomSource = DeterministicRandomSource()
        LocalBlobStorage(randomSource).use { blobStorage ->
            InMemoryDataStore(randomSource).use { dataStore ->
                dataStore.migrateToHead().unwrap()
                val event = seedSuccessfulUpload(dataStore, blobStorage)
                val processor = uploadEventProcessor(dataStore, blobStorage, randomSource, downloadDir)

                var acked = false
                val result = processor.processAppDraftUpload(event) { acked = true }

                assertTrue(acked)
                assertEquals(Unit.right(), result)
            }
        }
    }

    @Test
    fun `processAppDraftUpload persists a new app package with the parsed APK set metadata`(
        @TempDir downloadDir: Path,
    ) {
        val randomSource = DeterministicRandomSource()
        LocalBlobStorage(randomSource).use { blobStorage ->
            InMemoryDataStore(randomSource).use { dataStore ->
                dataStore.migrateToHead().unwrap()
                val event = seedSuccessfulUpload(dataStore, blobStorage)
                val processor = uploadEventProcessor(dataStore, blobStorage, randomSource, downloadDir)

                processor.processAppDraftUpload(event) {}.unwrap()
                val appPackage = dataStore.runTxWithRetry { tx ->
                    tx.appPackages.findByAppDraftId("appDraft1").bind()
                }
                    .unwrap2()
                    .unwrap()

                assertEquals(
                    AppPackage(
                        id = appPackage.id,
                        appDraftId = "appDraft1",
                        externalBlobId = "blob1",
                        uploadEventTime = UNIX_EPOCH,
                        appId = ApplicationId.fromString("com.example.app").unwrap(),
                        versionCode = VersionCode.fromInt(1).unwrap(),
                        versionName = VersionName.fromString("1.0").unwrap(),
                        targetSdk = SdkVersion.fromInt(37).unwrap(),
                        signerCertificate = Bytes(
                            Base64.decode(
                                "MIIBmjCCAUGgAwIBAgIIL/ZV8tyksMQwCgYIKoZIzj0EAwMwQTELMAkGA1UEBhMC" +
                                        "VVMxEzARBgNVBAoTCkFjY3Jlc2NlbnQxHTAbBgNVBAMTFEFjY3Jlc2NlbnQgVGVz" +
                                        "dCBEYXRhMCAXDTI1MTIzMDAwMzgwNVoYDzIwNTAxMjI0MDAzODA1WjBBMQswCQYD" +
                                        "VQQGEwJVUzETMBEGA1UEChMKQWNjcmVzY2VudDEdMBsGA1UEAxMUQWNjcmVzY2Vu" +
                                        "dCBUZXN0IERhdGEwWTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAATyiqc/LercXEgv" +
                                        "d38Abqbw4BlIH46nV3tYoQnZzqTm+1RrhlqzgVViuw7S8/c/Bm8rCtpvOlyQpJMy" +
                                        "hUp5MNNMoyEwHzAdBgNVHQ4EFgQUsIrVCtW/FY9akOgHQ5rNCTYdP/cwCgYIKoZI" +
                                        "zj0EAwMDRwAwRAIgf+pJNAN8t0P78r28E0Dwnd5m9euS0lQpwiKmykf4dT4CIBUR" +
                                        "U/eST77QsXax9VvGr/aTTXV3uf+ZWxt4joaW73Pu",
                            )
                        ),
                        buildApksResult = Bytes(Base64.decode(VALID_TOC_BASE64)),
                    ),
                    appPackage,
                )
            }
        }
    }

    @Test
    fun `processAppDraftUpload points the app draft at the newly persisted package`(
        @TempDir downloadDir: Path,
    ) {
        val randomSource = DeterministicRandomSource()
        LocalBlobStorage(randomSource).use { blobStorage ->
            InMemoryDataStore(randomSource).use { dataStore ->
                dataStore.migrateToHead().unwrap()
                val event = seedSuccessfulUpload(dataStore, blobStorage)
                val processor = uploadEventProcessor(dataStore, blobStorage, randomSource, downloadDir)

                processor.processAppDraftUpload(event) {}.unwrap()
                // The app draft started with no package, so its view should now expose the package
                // persisted from this upload
                val appDraftApiView = dataStore.runTxWithRetry { tx ->
                    tx.appDrafts.requireApiViewById("appDraft1").bind()
                }
                    .unwrap2()

                assertEquals(
                    Some(
                        AppPackageApiView(
                            androidApplicationId = ApplicationId
                                .fromString("com.example.app")
                                .unwrap(),
                            versionCode = VersionCode.fromInt(1).unwrap(),
                            versionName = VersionName.fromString("1.0").unwrap(),
                            targetSdk = SdkVersion.fromInt(37).unwrap(),
                        )
                    ),
                    (appDraftApiView as AppDraftApiView.Unsubmitted).appPackage,
                )
            }
        }
    }

    @Test
    fun `processAppDraftUpload commits the pending external blob`(
        @TempDir downloadDir: Path,
    ) {
        val randomSource = DeterministicRandomSource()
        LocalBlobStorage(randomSource).use { blobStorage ->
            InMemoryDataStore(randomSource).use { dataStore ->
                dataStore.migrateToHead().unwrap()
                val event = seedSuccessfulUpload(dataStore, blobStorage)
                val processor = uploadEventProcessor(dataStore, blobStorage, randomSource, downloadDir)

                processor.processAppDraftUpload(event) {}.unwrap()
                val externalBlob = dataStore.runTxWithRetry { tx ->
                    tx.externalBlobs.requireById("blob1").bind()
                }
                    .unwrap2()

                val status = assertInstanceOf<ExternalBlob.Status.Committed<*>>(externalBlob.status)
                assertInstanceOf<ExternalBlob.LocalBlobVersion>(status.version)
            }
        }
    }

    @Test
    fun `processAppDraftUpload copies the uploaded blob to the reserved private location`(
        @TempDir downloadDir: Path,
    ) {
        val randomSource = DeterministicRandomSource()
        LocalBlobStorage(randomSource).use { blobStorage ->
            InMemoryDataStore(randomSource).use { dataStore ->
                dataStore.migrateToHead().unwrap()
                val event = seedSuccessfulUpload(dataStore, blobStorage)
                val processor = uploadEventProcessor(dataStore, blobStorage, randomSource, downloadDir)

                processor.processAppDraftUpload(event) {}.unwrap()
                // Read the committed version so the copied blob can be located and downloaded
                val externalBlob = dataStore.runTxWithRetry { tx ->
                    tx.externalBlobs.requireById("blob1").bind()
                }
                    .unwrap2()
                val status = assertInstanceOf<ExternalBlob.Status.Committed<*>>(externalBlob.status)
                val version = assertInstanceOf<ExternalBlob.LocalBlobVersion>(status.version)
                val downloaded = downloadDir.resolve("downloaded.apks")
                blobStorage
                    .download(
                        BlobId.Local(
                            BlobId.Location("private", "reserved1"),
                            BlobId.Version.Local(version.generation),
                        ),
                        downloaded,
                    )
                    .unwrap()

                assertArrayEquals(Files.readAllBytes(validApkSetPath()), Files.readAllBytes(downloaded))
            }
        }
    }

    @Test
    fun `processAppDraftUpload replaces the existing package`(
        @TempDir downloadDir: Path,
    ) {
        val randomSource = DeterministicRandomSource()
        LocalBlobStorage(randomSource).use { blobStorage ->
            InMemoryDataStore(randomSource).use { dataStore ->
                dataStore.migrateToHead().unwrap()
                val blobId = blobStorage
                    .upload(validApkSetPath(), BlobId.Location("uploads", "upload1"))
                    .unwrap()
                dataStore.runTxWithRetry { tx ->
                    tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                    tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                    saveAppPackageFromNewUpload(
                        tx,
                        appPackage = appPackage(
                            id = "oldPackage",
                            externalBlobId = "oldBlob",
                            appId = ApplicationId.fromString("com.example.app2").unwrap(),
                        ),
                        bucketName = "private",
                        objectKey = "old1",
                    )
                        .bind()
                    // An app draft holds one pending upload at a time, so the committed one makes
                    // way for the upload that replaces the package
                    tx.appDrafts.deletePendingUploadByAppDraftId("appDraft1", UNIX_EPOCH).bind()
                    tx.appDrafts
                        .saveUpload(
                            incompletePendingAppDraftUpload(
                                externalBlobId = "newBlob",
                                objectKey = "upload1",
                            ),
                            pendingExternalBlob(
                                id = "newBlob",
                                bucketName = "private",
                                objectKey = "new1",
                            ),
                        )
                        .bind()
                }
                    .unwrap2()
                val processor = uploadEventProcessor(dataStore, blobStorage, randomSource, downloadDir)
                // Newer than the existing package's upload event time so the event isn't stale
                val event = blobId.toUploadEvent(UNIX_EPOCH.plusSeconds(1))

                processor.processAppDraftUpload(event) {}.unwrap()
                val appDraftView = dataStore.runTxWithRetry { tx ->
                    tx.appDrafts.requireApiViewById("appDraft1").bind()
                }
                    .unwrap2()

                assertEquals(
                    Some(ApplicationId.fromString("com.example.app").unwrap()),
                    (appDraftView as AppDraftApiView.Unsubmitted).appPackage.map { it.androidApplicationId },
                )
            }
        }
    }

    @Test
    fun `processAppDraftListingIconUpload acks and returns NoPendingUpload when no pending upload exists`() {
        // TODO
    }

    @Test
    fun `processAppDraftListingIconUpload acks and returns AlreadyProcessed when pending upload already has a result`() {
        // TODO
    }

    @Test
    fun `processAppDraftListingIconUpload acks and returns StaleEvent when event is not newer that current icon's upload event time`() {
        // TODO
    }

    @Test
    fun `processAppDraftListingIconUpload acks and saves AppDraftSubmitted if app draft is already submitted`() {
        // TODO
    }

    @Test
    fun `processAppDraftListingIconUpload acks and returns BlobNotFound when the object is not in storage`() {
        // TODO
    }

    @Test
    fun `processAppDraftListingIconUpload acks and persists IconParseFailed when the icon fails to parse`() {
        // TODO
    }

    @Test
    fun `processAppDraftListingIconUpload acks and returns success when processing succeeds`() {
        // TODO
    }

    @Test
    fun `processAppDraftListingIconUpload persists a new icon`() {
        // TODO
    }
}

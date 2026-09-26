// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.ports.driven.blobstorage

import app.accrescent.server.core.intoInt
import app.accrescent.server.core.unwrap
import app.accrescent.server.core.unwrapErr
import io.restassured.RestAssured.given
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInfo
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Conformance test suite for [BlobStorage] implementations.
 */
abstract class BlobStorageConformanceTest<B : BlobId> {
    /**
     * Runs a lambda with a new [BlobStorage] instance.
     *
     * Each call creates a new class instance which may share bucket state with other instances.
     * Tests should be written with this knowledge in mind. Attempts to call any methods on the
     * [BlobStorage] outside of [block]'s scope result in undefined behavior.
     *
     * @param block the lambda to run with access to a new [BlobStorage] instance.
     * @return the return value of [block].
     */
    protected abstract fun <T> withBlobStorage(block: (BlobStorage<B>) -> T): T

    protected abstract val bucketName: String

    protected abstract fun makeBlobId(location: BlobId.Location): B

    protected abstract fun withDifferentVersion(blobId: B): B

    @Test
    fun `signUploadUri for APK set prohibits upload larger than maxSizeBytes`(testInfo: TestInfo) {
        val maxSizeBytes = 100uL
        withBlobStorage { storage ->
            val (_, uri) = storage
                .signUploadUri(
                    type = UploadType.APK_SET,
                    location = testLocation(testInfo),
                    maxSizeBytes = maxSizeBytes,
                )
                .unwrap()

            val statusCode = given()
                .body(ByteArray(maxSizeBytes.intoInt().unwrap() + 1))
                .put(uri.intoString())
                .statusCode

            assertEquals(400, statusCode)
        }
    }

    @Test
    fun `signUploadUri for APK set allows upload at maxSizeBytes`(testInfo: TestInfo) {
        val maxSizeBytes = 100uL
        withBlobStorage { storage ->
            val (_, uri) = storage
                .signUploadUri(
                    type = UploadType.APK_SET,
                    location = testLocation(testInfo),
                    maxSizeBytes = maxSizeBytes,
                )
                .unwrap()

            val statusCode = given()
                .body(ByteArray(maxSizeBytes.intoInt().unwrap()))
                .put(uri.intoString())
                .statusCode

            assertEquals(200, statusCode)
        }
    }

    @Test
    fun `signUploadUri URI returns 412 on upload when object already exists at location`(
        testInfo: TestInfo,
    ) {
        withBlobStorage { storage ->
            val (_, uri) = storage
                .signUploadUri(UploadType.APK_SET, testLocation(testInfo))
                .unwrap()

            val firstStatus = given()
                .body(byteArrayOf(0))
                .put(uri.intoString())
                .statusCode

            assertEquals(200, firstStatus)

            val secondStatus = given()
                .body(byteArrayOf(0))
                .put(uri.intoString())
                .statusCode

            assertEquals(412, secondStatus)
        }
    }

    @Test
    fun `signUploadUri URI expires after configured lifetime`(testInfo: TestInfo) {
        val lifetime = UriLifetime.new(1u).unwrap()
        withBlobStorage { storage ->
            val (_, uri) = storage
                .signUploadUri(UploadType.APK_SET, testLocation(testInfo), lifetime = lifetime)
                .unwrap()
            Thread.sleep(lifetime.seconds.toLong() * 1000 + 1)

            val statusCode = given()
                .body(byteArrayOf(0))
                .put(uri.intoString())
                .statusCode

            assertEquals(403, statusCode)
        }
    }

    @Test
    fun `signDownloadUri URI expires after configured lifetime`(testInfo: TestInfo) {
        val lifetime = UriLifetime.new(1u).unwrap()
        withBlobStorage { storage ->
            // Upload the initial object
            val blobId = storage
                .create("deadbeef".hexToByteArray(), testLocation(testInfo))
                .unwrap()

            // Generate a download URI
            val uri = storage.signDownloadUri(blobId, lifetime).unwrap()

            // Wait until the URI expires
            Thread.sleep(lifetime.seconds.toLong() * 1000 + 1)

            // Attempt to download the object
            val statusCode = given().get(uri.intoString()).statusCode

            assertEquals(403, statusCode)
        }
    }

    @Test
    fun `signDownloadUri URI downloads previously saved object`(testInfo: TestInfo) {
        withBlobStorage { storage ->
            // Upload the initial object
            val blobId = storage
                .create("deadbeef".hexToByteArray(), testLocation(testInfo))
                .unwrap()

            // Attempt to download the uploaded object
            val uri = storage.signDownloadUri(blobId).unwrap()
            val response = given().get(uri.intoString())

            assertEquals(200, response.statusCode)
            assertArrayEquals("deadbeef".hexToByteArray(), response.body.asByteArray())
        }
    }

    @Test
    fun `download writes a previously saved object to the destination`(
        testInfo: TestInfo,
        @TempDir tempDir: Path,
    ) {
        withBlobStorage { storage ->
            // Upload the initial object
            val blobId = storage
                .create("deadbeef".hexToByteArray(), testLocation(testInfo))
                .unwrap()

            // Download the object to a local destination
            val destination = tempDir.resolve("object")
            storage.download(blobId, destination).unwrap()

            assertArrayEquals("deadbeef".hexToByteArray(), Files.readAllBytes(destination))
        }
    }

    @Test
    fun `download returns NotFound when the object does not exist`(
        testInfo: TestInfo,
        @TempDir tempDir: Path,
    ) {
        withBlobStorage { storage ->
            val destination = tempDir.resolve("object")

            val error = storage
                .download(makeBlobId(testLocation(testInfo)), destination)
                .unwrapErr()

            assertEquals(BlobStorageError.NotFound, error)
        }
    }

    @Test
    fun `download returns NotFound when the version does not match`(
        testInfo: TestInfo,
        @TempDir tempDir: Path,
    ) {
        withBlobStorage { storage ->
            val blobId = storage
                .create("deadbeef".hexToByteArray(), testLocation(testInfo))
                .unwrap()

            val wrongVersionId = withDifferentVersion(blobId)
            val destination = tempDir.resolve("object")
            val error = storage.download(wrongVersionId, destination).unwrapErr()

            assertEquals(BlobStorageError.NotFound, error)
        }
    }

    private fun testLocation(testInfo: TestInfo, suffix: String = "object1"): BlobId.Location {
        return BlobId.Location(bucketName, "${testInfo.testMethod.get().name}/$suffix")
    }
}

// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.android

import app.accrescent.server.core.unwrapErr
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createTempFile
import kotlin.io.path.writeBytes

class ApkTest {
    @Test
    fun `parse returns Io if reading from path fails`(@TempDir tempDir: Path) {
        // Create a file we can't read so that we know the path we pass to Apk.parse() doesn't
        // contain a readable file
        val path = createTempFile(
            directory = tempDir,
            attributes = arrayOf(PosixFilePermissions.asFileAttribute(emptySet())),
        )

        val error = Apk.parse(path).unwrapErr()

        assertEquals(ApkParseError.Io, error)
    }

    @Test
    fun `parse returns InvalidFormat for invalid ZIP`(@TempDir tempDir: Path) {
        val path = createTempFile(tempDir)
        // The correct local file header is 504b0304
        // (https://en.wikipedia.org/wiki/ZIP_(file_format)#Local_file_header), so a single zero
        // does not start a valid ZIP file.
        path.writeBytes(byteArrayOf(0))

        val error = Apk.parse(path).unwrapErr()

        assertEquals(ApkParseError.InvalidFormat, error)
    }

    @Test
    fun `parse returns NoModernSignature for APK signed with only v1 signature`() {
        val error = Apk.parse(signedApkPath("no-modern-signature")).unwrapErr()

        assertEquals(ApkParseError.Policy.NoModernSignature, error)
    }

    @Test
    fun `parse returns SignedWithDebugCert for APK signed with debug certificate`() {
        val error = Apk.parse(signedApkPath("debug-cert")).unwrapErr()

        assertEquals(ApkParseError.Policy.SignedWithDebugCert, error)
    }

    @Test
    fun `parse returns SignedWithMultipleCerts for APK signed with multiple certificates`() {
        val error = Apk.parse(signedApkPath("multiple-certs")).unwrapErr()

        assertEquals(ApkParseError.Policy.SignedWithMultipleCerts, error)
    }

    @Test
    fun `parse returns Unverified for invalid signature`() {
        val error = Apk.parse(signedApkPath("unverified")).unwrapErr()

        assertEquals(ApkParseError.Policy.Unverified, error)
    }

    companion object {
        private fun signedApkPath(name: String): Path {
            return Path.of(
                requireNotNull(System.getProperty("testdata.apk.$name.path")) {
                    "APK signing test data was not built; run this test via the Gradle 'test' task"
                }
            )
        }
    }
}

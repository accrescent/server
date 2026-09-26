// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.ports.driven.file

import app.accrescent.server.core.unwrap
import app.accrescent.server.core.unwrapErr
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isExecutable
import kotlin.io.path.isReadable
import kotlin.io.path.isWritable

/**
 * Conformance test suite for [TempFile.Factory] implementations.
 */
abstract class TempFileConformanceTest {
    /**
     * The [TempFile.Factory] under test.
     *
     * Each access may return the same factory instance. The factory must not share any state with
     * the files it creates beyond what is required to create them.
     */
    protected abstract val factory: TempFile.Factory<out TempFile>

    @Test
    fun `createInDirectory returns an error when the file cannot be created`(
        @TempDir tempDir: Path,
    ) {
        val nonExistentDirectory = tempDir.resolve("nonexistent")

        val error = factory.createInDirectory(nonExistentDirectory).unwrapErr()

        assertEquals(TempFileCreateError, error)
    }

    @Test
    fun `createInDirectory creates a file at path`(@TempDir tempDir: Path) {
        val tempFile = factory.createInDirectory(tempDir).unwrap()

        assertTrue(tempFile.path.exists())
    }

    @Test
    fun `createInDirectory creates a readable file`(@TempDir tempDir: Path) {
        val tempFile = factory.createInDirectory(tempDir).unwrap()

        assertTrue(tempFile.path.isReadable())
    }

    @Test
    fun `createInDirectory creates a writeable file`(@TempDir tempDir: Path) {
        val tempFile = factory.createInDirectory(tempDir).unwrap()

        assertTrue(tempFile.path.isWritable())
    }

    @Test
    fun `createInDirectory creates a non-executable file`(@TempDir tempDir: Path) {
        val tempFile = factory.createInDirectory(tempDir).unwrap()

        assertFalse(tempFile.path.isExecutable())
    }

    @Test
    fun `close removes the file at path from the filesystem`(@TempDir tempDir: Path) {
        val tempFile = factory.createInDirectory(tempDir).unwrap()

        tempFile.close().unwrap()

        assertFalse(tempFile.path.exists())
    }
}

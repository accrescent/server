// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.adapters.driven.file

import app.accrescent.server.domain.ports.driven.file.TempFile
import app.accrescent.server.domain.ports.driven.file.TempFileCloseError
import app.accrescent.server.domain.ports.driven.file.TempFileCreateError
import arrow.core.Either
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteExisting

class LocalTempFile private constructor(override val path: Path) : TempFile {
    override fun close(): Either<TempFileCloseError, Unit> {
        return try {
            Either.Right(path.deleteExisting())
        } catch (_: Throwable) {
            Either.Left(TempFileCloseError)
        }
    }

    companion object : TempFile.Factory<LocalTempFile> {
        private val attributes = arrayOf(
            PosixFilePermissions
                .asFileAttribute(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
        )

        override fun createInDirectory(directory: Path): Either<TempFileCreateError, LocalTempFile> {
            val path = try {
                createTempFile(directory = directory, attributes = attributes)
            } catch (_: Throwable) {
                return Either.Left(TempFileCreateError)
            }
            val file = LocalTempFile(path)

            return Either.Right(file)
        }
    }
}

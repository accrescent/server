// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.ports.driven.file

import app.accrescent.server.core.FalliblyCloseable
import arrow.core.Either
import java.nio.file.Path

data object TempFileCloseError
data object TempFileCreateError

interface TempFile : FalliblyCloseable<TempFileCloseError> {
    val path: Path

    interface Factory<T : TempFile> {
        /**
         * Creates a new temporary file.
         *
         * The resulting file is readable and writeable by the file's creator, but not executable.
         *
         * @param directory the directory to create the temporary file in.
         * @return the created file if creation succeeded, otherwise [TempFileCreateError].
         */
        fun createInDirectory(directory: Path): Either<TempFileCreateError, T>
    }
}

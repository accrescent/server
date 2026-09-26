// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.adapters.driven.blobstorage

import app.accrescent.server.core.bindMapLeft
import app.accrescent.server.core.unwrap
import app.accrescent.server.domain.ports.driven.blobstorage.BlobId
import app.accrescent.server.domain.ports.driven.blobstorage.BlobStorage
import app.accrescent.server.domain.ports.driven.blobstorage.BlobStorageBackend
import app.accrescent.server.domain.ports.driven.blobstorage.BlobStorageError
import app.accrescent.server.domain.ports.driven.blobstorage.BlobStorageResult
import app.accrescent.server.domain.ports.driven.blobstorage.UploadType
import app.accrescent.server.domain.ports.driven.blobstorage.UriLifetime
import app.accrescent.server.domain.ports.driven.randomsource.RandomSource
import app.accrescent.server.domain.ports.driven.randomsource.RandomSourceResult
import app.accrescent.server.domain.uri.HttpUri
import arrow.core.Either
import arrow.core.left
import arrow.core.raise.context.bind
import arrow.core.raise.context.either
import arrow.core.right
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.encoding.Base64
import kotlin.io.path.createTempDirectory

class LocalBlobStorage(
    private val randomSource: RandomSource,
) : AutoCloseable, BlobStorage<BlobId.Local> {
    private val server: HttpServer = HttpServer.create(InetSocketAddress("localhost", 0), 0).apply {
        createContext("/upload") { exchange -> handleUpload(exchange) }
        createContext("/download") { exchange -> handleDownload(exchange) }
        start()
    }
    private val tempDir = createTempDirectory()
    private val objects = ConcurrentHashMap<BlobId.Location, ObjectRecord>()
    private val uploadTokens = ConcurrentHashMap<String, UploadEntry>()
    private val downloadTokens = ConcurrentHashMap<String, DownloadEntry>()

    val port: Int get() = server.address.port

    private fun handleUpload(exchange: HttpExchange) {
        if (exchange.requestMethod != "PUT") {
            exchange.sendResponseHeaders(405, -1)
            exchange.close()
            return
        }

        val token = exchange.requestURI.path.removePrefix("/upload/")
        val entry = uploadTokens[token]

        if (entry == null || Instant.now().isAfter(entry.expiry)) {
            uploadTokens.remove(token)
            exchange.sendResponseHeaders(403, -1)
            exchange.close()
            return
        }

        if (objects.containsKey(entry.location)) {
            exchange.sendResponseHeaders(412, -1)
            exchange.close()
            return
        }

        val tempFile = Files.createTempFile(tempDir, null, null)
        try {
            var sizeExceeded = false
            exchange.requestBody.use { input ->
                Files.newOutputStream(tempFile).use { output ->
                    var total = 0uL
                    val buf = ByteArray(8192)
                    var n: Int
                    while (input.read(buf).also { n = it } >= 0) {
                        total += n.toULong()
                        if (total > entry.maxSizeBytes) {
                            sizeExceeded = true
                            break
                        }
                        output.write(buf, 0, n)
                    }
                }
            }

            if (sizeExceeded) {
                exchange.sendResponseHeaders(400, -1)
                exchange.close()
                return
            }

            val generation = newGeneration()
            if (generation == null) {
                exchange.sendResponseHeaders(500, -1)
                exchange.close()
                return
            }

            Files.createDirectories(bucketDir(entry.location))
            Files.move(tempFile, pathFor(entry.location), StandardCopyOption.REPLACE_EXISTING)
            objects[entry.location] = ObjectRecord(generation)
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }

    private fun handleDownload(exchange: HttpExchange) {
        if (exchange.requestMethod != "GET") {
            exchange.sendResponseHeaders(405, -1)
            exchange.close()
            return
        }

        val token = exchange.requestURI.path.removePrefix("/download/")
        val entry = downloadTokens[token]

        if (entry == null || Instant.now().isAfter(entry.expiry)) {
            downloadTokens.remove(token)
            exchange.sendResponseHeaders(403, -1)
            exchange.close()
            return
        }

        val record = objects[entry.blobId.location]
        if (record == null || record.generation != entry.blobId.version.generation) {
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
            return
        }

        val path = pathFor(entry.blobId.location)
        exchange.sendResponseHeaders(200, Files.size(path))
        Files.copy(path, exchange.responseBody)
        exchange.close()
    }

    override fun signUploadUri(
        type: UploadType,
        location: BlobId.Location,
        lifetime: UriLifetime,
        maxSizeBytes: ULong,
    ): BlobStorageResult<Pair<BlobStorageBackend, HttpUri>> = either {
        val token = generateToken().bindMapLeft { BlobStorageError.Other }
        val expiry = Instant.now().plusSeconds(lifetime.seconds.toLong())
        uploadTokens[token] = UploadEntry(location, expiry, maxSizeBytes)
        val uri = HttpUri.fromString("http://localhost:$port/upload/$token").unwrap()
        return Either.Right(BlobStorageBackend.LOCAL to uri)
    }

    override fun signDownloadUri(
        blobId: BlobId.Local,
        lifetime: UriLifetime
    ): BlobStorageResult<HttpUri> = either {
        val token = generateToken().bindMapLeft { BlobStorageError.Other }
        val expiry = Instant.now().plusSeconds(lifetime.seconds.toLong())
        downloadTokens[token] = DownloadEntry(blobId, expiry)
        return HttpUri.fromString("http://localhost:$port/download/$token").unwrap().right()
    }

    override fun copy(
        source: BlobId.Local,
        destination: BlobId.Location
    ): BlobStorageResult<BlobId.Local> {
        val record = objects[source.location]
        if (record == null || record.generation != source.version.generation) {
            return BlobStorageError.NotFound.left()
        }
        val newGeneration = newGeneration() ?: return BlobStorageError.Other.left()
        Files.createDirectories(bucketDir(destination))
        Files.copy(pathFor(source.location), pathFor(destination), StandardCopyOption.REPLACE_EXISTING)
        objects[destination] = ObjectRecord(newGeneration)
        return BlobId.Local(destination, BlobId.Version.Local(newGeneration)).right()
    }

    override fun create(
        contents: ByteArray,
        destination: BlobId.Location,
    ): BlobStorageResult<BlobId.Local> {
        val generation = newGeneration() ?: return BlobStorageError.Other.left()
        Files.createDirectories(bucketDir(destination))
        Files.write(pathFor(destination), contents)
        objects[destination] = ObjectRecord(generation)
        return BlobId.Local(destination, BlobId.Version.Local(generation)).right()
    }

    override fun upload(
        source: Path,
        destination: BlobId.Location,
    ): BlobStorageResult<BlobId.Local> {
        val generation = newGeneration() ?: return BlobStorageError.Other.left()
        Files.createDirectories(bucketDir(destination))
        Files.copy(source, pathFor(destination), StandardCopyOption.REPLACE_EXISTING)
        objects[destination] = ObjectRecord(generation)
        return BlobId.Local(destination, BlobId.Version.Local(generation)).right()
    }

    override fun download(
        blobId: BlobId.Local,
        destination: Path
    ): BlobStorageResult<Unit> {
        val record = objects[blobId.location]
        if (record == null || record.generation != blobId.version.generation) {
            return BlobStorageError.NotFound.left()
        }
        Files.copy(pathFor(blobId.location), destination, StandardCopyOption.REPLACE_EXISTING)
        return Unit.right()
    }

    override fun close() {
        server.stop(0)
        tempDir.toFile().deleteRecursively()
    }

    private fun encode(s: String) = Base64.UrlSafe.encode(s.toByteArray())

    private fun bucketDir(location: BlobId.Location): Path =
        tempDir.resolve(encode(location.bucketName))

    private fun pathFor(location: BlobId.Location): Path =
        bucketDir(location).resolve(encode(location.objectKey))

    private fun generateToken(): RandomSourceResult<String> = either {
        UUID(randomSource.randomLong().bind(), randomSource.randomLong().bind()).toString()
    }

    private fun newGeneration(): Long? {
        return randomSource.randomLong().getOrNull()
    }

    private data class UploadEntry(
        val location: BlobId.Location,
        val expiry: Instant,
        val maxSizeBytes: ULong,
    )

    private data class DownloadEntry(val blobId: BlobId.Local, val expiry: Instant)

    private data class ObjectRecord(val generation: Long)
}

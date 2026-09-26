// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.crypto

import app.accrescent.server.core.Bytes
import app.accrescent.server.core.unwrap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class Sha256HashTest {
    @Test
    fun `fromDigest accepts 32-byte digest`() {
        val result = Sha256Hash.fromDigest(
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef".hexToByteArray()
        )

        assertTrue(result.isSome())
    }

    @Test
    fun `fromDigest rejects digest which is not 32 bytes long`() {
        val result = Sha256Hash.fromDigest(byteArrayOf(0))

        assertTrue(result.isNone())
    }

    @ParameterizedTest
    @MethodSource("sampleTestVectors")
    fun `hash produces the expected digest`(testVector: TestVector) {
        val hash = Sha256Hash.hash(testVector.data)

        assertEquals(Bytes(testVector.expectedDigest), hash.digest())
    }

    @Test
    fun `instances with same digest are equal`() {
        val instance1 = Sha256Hash.fromDigest(
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef".hexToByteArray()
        )
            .unwrap()
        val instance2 = Sha256Hash.fromDigest(
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef".hexToByteArray()
        )
            .unwrap()

        assertEquals(instance1, instance2)
    }

    @Test
    fun `instances with same digest have the same hash code`() {
        val instance1 = Sha256Hash.fromDigest(
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef".hexToByteArray()
        )
            .unwrap()
        val instance2 = Sha256Hash.fromDigest(
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef".hexToByteArray()
        )
            .unwrap()

        assertEquals(instance1.hashCode(), instance2.hashCode())
    }

    class TestVector(val data: ByteArray, val expectedDigest: ByteArray)

    companion object {
        @JvmStatic
        fun sampleTestVectors(): List<TestVector> {
            return listOf(
                TestVector(
                    "".toByteArray(),
                    "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855".hexToByteArray()
                ),
                TestVector(
                    "abc".toByteArray(),
                    "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad".hexToByteArray(),
                ),
                TestVector(
                    "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".toByteArray(),
                    "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1".hexToByteArray(),
                ),
            )
        }
    }
}

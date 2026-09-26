// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.core

/**
 * An immutable [ByteArray] wrapper with structural equality.
 *
 * The [equals] and [hashCode] implementations for [ByteArray] are instance-based, not
 * content-based, meaning that two [ByteArray]s with the same contents are not considered equal.
 * This behavior is often undesirable because it is unexpected and requires developers to maintain
 * their own [equals] and [hashCode] implementations for data classes with [ByteArray] fields.
 *
 * This class is usable in place of [ByteArray] where structural equality is desired, such as in
 * data classes.
 *
 * [ByteArray]s are also normally mutable. This class performs defensive copies on construction and
 * [copyToByteArray] conversion to prevent internal mutation.
 */
class Bytes(value: ByteArray) {
    private val value: ByteArray = value.copyOf()

    /**
     * Copies this object's bytes into a new byte array.
     *
     * @return a copy of this object's bytes
     */
    fun copyToByteArray(): ByteArray {
        return value.copyOf()
    }

    override fun equals(other: Any?): Boolean {
        return other is Bytes && value.contentEquals(other.value)
    }

    override fun hashCode(): Int {
        return value.contentHashCode()
    }
}

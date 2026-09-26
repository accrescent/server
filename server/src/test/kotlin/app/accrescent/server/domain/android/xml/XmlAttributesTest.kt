// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.android.xml

import app.accrescent.server.core.unwrap
import app.accrescent.server.core.unwrapErr
import arrow.core.None
import arrow.core.Some
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class XmlAttributesTest {
    @Test
    fun `findMatch returns MismatchedName when attribute exists with same resource ID but different name`() {
        val resourceId = ResourceId(0x0101021cu)
        val attributes = XmlAttributes.fromList(
            listOf(unqualifiedAttribute("attr1", ResourceValue.Bool(false), Some(resourceId))),
        ).unwrap()

        val error = attributes
            .findMatch(XmlAttributeId(unqualifiedName("attr2"), Some(resourceId)))
            .unwrapErr()

        assertEquals(XmlAttributes.FindMatchError.MismatchedName(unqualifiedName("attr1")), error)
    }

    @Test
    fun `findMatch returns MismatchedResourceId when attribute exists with same name but different resource ID`() {
        val existingResourceId = ResourceId(0x0101021cu)
        val attributes = XmlAttributes.fromList(
            listOf(unqualifiedAttribute("attr", ResourceValue.Bool(false), Some(existingResourceId))),
        ).unwrap()
        val queriedResourceId = ResourceId(0x0101021du)

        val error = attributes
            .findMatch(XmlAttributeId(unqualifiedName("attr"), Some(queriedResourceId)))
            .unwrapErr()

        assertEquals(
            XmlAttributes.FindMatchError.MismatchedResourceId(Some(existingResourceId)),
            error,
        )
    }

    @Test
    fun `findMatch returns None if no matching attribute exists`() {
        val attributes = XmlAttributes.fromList(
            listOf(unqualifiedAttribute("present", ResourceValue.Bool(false))),
        ).unwrap()

        val match = attributes.findMatch(XmlAttributeId(unqualifiedName("missing"), None)).unwrap()

        assertEquals(None, match)
    }

    @Test
    fun `findMatch returns attribute value if matching attribute exists`() {
        val resourceId = ResourceId(0x0101021cu)
        val value = ResourceValue.Bool(true)
        val attributes = XmlAttributes.fromList(
            listOf(unqualifiedAttribute("attr", value, Some(resourceId))),
        ).unwrap()

        val match = attributes
            .findMatch(XmlAttributeId(unqualifiedName("attr"), Some(resourceId)))
            .unwrap()

        assertEquals(Some(value), match)
    }

    @Test
    fun `fromList returns DuplicateName if passed attributes with duplicate names`() {
        val duplicateName = unqualifiedName("attr")
        val attributes = listOf(
            unqualifiedAttribute("attr", ResourceValue.Bool(false)),
            unqualifiedAttribute("attr", ResourceValue.IntDec(1)),
        )

        val error = XmlAttributes.fromList(attributes).unwrapErr()

        assertEquals(XmlAttributes.FromListError.DuplicateName(duplicateName), error)
    }

    @Test
    fun `fromList returns DuplicateResourceId if passed attributes with duplicate resource IDs`() {
        val duplicateId = ResourceId(0x0101021cu)
        val attributes = listOf(
            unqualifiedAttribute("attr1", ResourceValue.Bool(false), Some(duplicateId)),
            unqualifiedAttribute("attr2", ResourceValue.Bool(false), Some(duplicateId)),
        )

        val error = XmlAttributes.fromList(attributes).unwrapErr()

        assertEquals(XmlAttributes.FromListError.DuplicateResourceId(duplicateId), error)
    }

    @Test
    fun `fromList succeeds if passed empty list`() {
        XmlAttributes.fromList(emptyList()).unwrap()
    }

    @Test
    fun `fromList and asList round-trip data`() {
        val attributes = listOf(
            unqualifiedAttribute("attr1", ResourceValue.Bool(false), Some(ResourceId(0x0101021cu))),
            unqualifiedAttribute("attr2", ResourceValue.IntDec(1)),
        )

        val roundTripped = XmlAttributes.fromList(attributes).unwrap().asList()

        assertEquals(attributes, roundTripped)
    }
}

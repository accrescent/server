// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.android.xml

import arrow.core.Either
import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import arrow.core.getOrNone
import arrow.core.left
import arrow.core.right

/**
 * The [attributes](https://www.w3.org/TR/2008/REC-xml-20081126/#dt-attr) of an Android binary XML
 * element.
 *
 * This collection guarantees that each attribute's [name][XmlAttributeId.name] is unique within it
 * and that each attribute's [resource ID][XmlAttributeId.resourceId], when present, is unique
 * within it.
 */
class XmlAttributes private constructor(
    private val byName: Map<XmlExpandedName, XmlAttribute>,
    private val byResourceId: Map<ResourceId, XmlAttribute>,
) {
    sealed class FindMatchError {
        /**
         * An attribute exists with the queried resource ID, but its name differs from the queried
         * name.
         *
         * @property actualName the name of the attribute with the queried resource ID.
         */
        data class MismatchedName(val actualName: XmlExpandedName) : FindMatchError()

        /**
         * An attribute exists with the queried name, but its resource ID differs from the queried
         * resource ID.
         *
         * @property actualResourceId the resource ID of the attribute with the queried name, or
         * [None] if that attribute has no resource ID.
         */
        data class MismatchedResourceId(val actualResourceId: Option<ResourceId>) : FindMatchError()
    }

    /**
     * Finds the XML attribute matching the query if one exists.
     *
     * @param attributeId the ID of the attribute to find.
     * @return [FindMatchError.MismatchedName] if an attribute exists with the provided resource ID
     * but a different name, [FindMatchError.MismatchedResourceId] if one exists with the provided
     * name but a different resource ID, [None] if no matching attribute exists, or the matching
     * attribute value if it exists.
     */
    fun findMatch(attributeId: XmlAttributeId): Either<FindMatchError, Option<ResourceValue>> {
        attributeId.resourceId
            .flatMap { byResourceId.getOrNone(it) }
            .onSome { attribute ->
                if (attribute.id.name != attributeId.name) {
                    return FindMatchError.MismatchedName(attribute.id.name).left()
                }
            }

        return byName.getOrNone(attributeId.name).fold(
            { None.right() },
            { attribute ->
                if (attribute.id.resourceId != attributeId.resourceId) {
                    FindMatchError.MismatchedResourceId(attribute.id.resourceId).left()
                } else {
                    Some(attribute.value).right()
                }
            },
        )
    }

    /**
     * Retrieves the attributes in this collection.
     *
     * @return a list of the attributes in this collection.
     */
    fun asList(): List<XmlAttribute> {
        return byName.values.toList()
    }

    // byResourceId indexes a subset of byName's values, so byName alone determines equality
    override fun equals(other: Any?): Boolean {
        return other is XmlAttributes && byName == other.byName
    }

    override fun hashCode(): Int {
        return byName.hashCode()
    }

    override fun toString(): String {
        return "XmlAttributes(${byName.values})"
    }

    companion object {
        /**
         * Creates a collection of XML attributes from a list of attributes.
         *
         * @param attributes the attributes to create the collection from.
         * @return the created collection, or a [FromListError] if [attributes] contains two
         * attributes with the same name or two attributes with the same resource ID.
         */
        fun fromList(attributes: List<XmlAttribute>): Either<FromListError, XmlAttributes> {
            val byName = mutableMapOf<XmlExpandedName, XmlAttribute>()
            val byResourceId = mutableMapOf<ResourceId, XmlAttribute>()

            for (attribute in attributes) {
                if (byName.put(attribute.id.name, attribute) != null) {
                    return FromListError.DuplicateName(attribute.id.name).left()
                }

                attribute.id.resourceId.onSome { resourceId ->
                    if (byResourceId.put(resourceId, attribute) != null) {
                        return FromListError.DuplicateResourceId(resourceId).left()
                    }
                }
            }

            return XmlAttributes(byName, byResourceId).right()
        }
    }

    /**
     * An error which may occur when attempting to construct an [XmlAttributes] from a list of
     * attributes.
     */
    sealed class FromListError {
        /**
         * The list contains more than one attribute with the same name.
         *
         * @property name the duplicated attribute name.
         */
        data class DuplicateName(val name: XmlExpandedName) : FromListError()

        /**
         * The list contains more than one attribute with the same resource ID.
         *
         * @property resourceId the duplicated resource ID.
         */
        data class DuplicateResourceId(val resourceId: ResourceId) : FromListError()
    }
}

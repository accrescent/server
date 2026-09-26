// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.android.xml

import app.accrescent.server.core.NonEmptyString
import app.accrescent.server.core.bindMapLeft
import app.accrescent.server.core.downcast
import app.accrescent.server.core.then
import app.accrescent.server.core.toEitherBind
import app.accrescent.server.domain.uri.Uri
import arrow.core.Either
import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import arrow.core.lastOrNone
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.singleOrNone
import com.google.devrel.gmscore.tools.apk.arsc.ResourceFile
import com.google.devrel.gmscore.tools.apk.arsc.ResourceIdentifier
import com.google.devrel.gmscore.tools.apk.arsc.StringPoolChunk
import com.google.devrel.gmscore.tools.apk.arsc.XmlChunk
import com.google.devrel.gmscore.tools.apk.arsc.XmlEndElementChunk
import com.google.devrel.gmscore.tools.apk.arsc.XmlNamespaceEndChunk
import com.google.devrel.gmscore.tools.apk.arsc.XmlNamespaceStartChunk
import com.google.devrel.gmscore.tools.apk.arsc.XmlResourceMapChunk
import com.google.devrel.gmscore.tools.apk.arsc.XmlStartElementChunk
import java.nio.ByteBuffer
import com.google.devrel.gmscore.tools.apk.arsc.ResourceValue as BinaryResourceValue
import com.google.devrel.gmscore.tools.apk.arsc.XmlAttribute as BinaryXmlAttribute

/**
 * An Android binary [XML document](https://www.w3.org/TR/2008/REC-xml-20081126/#NT-document).
 *
 * @property root the document's [root element](https://www.w3.org/TR/2008/REC-xml-20081126/#dt-root).
 */
data class XmlDocument(val root: XmlElement) {
    companion object {
        /**
         * Parses an XML document from Android binary XML.
         *
         * This parser is strict in some ways and lenient in others. It is strict in that it rejects
         * documents with ambiguous attributes or invalid structure where the Android platform might
         * accept them so that we avoid parser differential vulnerabilities. However, it is lenient
         * in that it does not validate parts of the document we don't use so that it can stay
         * relatively the same even as Android evolves and adds new allowed attributes. As a result,
         * a document which parses successfully with this function at one point in time may fail to
         * parse in the future, and vice versa.
         *
         * @param buf the binary XML to parse the document from.
         * @return the parsed document if [buf] represents a valid Android binary XML document,
         * otherwise [FromBytesError].
         */
        fun fromBinaryXml(buf: ByteBuffer): Either<FromBytesError, XmlDocument> = either {
            val xmlChunk = try {
                ResourceFile(buf)
            } catch (_: RuntimeException) {
                raise(FromBytesError)
            }
                .chunks
                .singleOrNone()
                .toEitherBind { FromBytesError }
                .downcast<XmlChunk>()
                .toEitherBind { FromBytesError }
            // Sort chunks by their file offset to traverse them in the correct order
            val childChunks = xmlChunk.chunks.toSortedMap().values.toList()

            // Well-formed XML documents generally have at most one string pool and at most one
            // resource map, so as far as we know, we shouldn't support encountering multiple and
            // figure out how to properly disambiguate string pool / resource map references.
            val stringPool = childChunks.filterIsInstance<StringPoolChunk>().atMostOne().bind()
            val resourceMap = childChunks.filterIsInstance<XmlResourceMapChunk>().atMostOne().bind()

            val openNamespaces = ArrayDeque<OpenNamespace>()
            val openElements = ArrayDeque<OpenElement>()
            var root: Option<XmlElement> = None

            for (chunk in childChunks) {
                when (chunk) {
                    // These elements are separately handled above and don't contribute nodes to the
                    // document tree, so we should ignore them here
                    is StringPoolChunk,
                    is XmlResourceMapChunk -> Unit

                    is XmlNamespaceStartChunk -> openNamespaces.addLast(
                        OpenNamespace(
                            prefix = readNonEmptyString(chunk::getPrefix).bind(),
                            uri = readNonEmptyString(chunk::getUri).bind(),
                        )
                    )

                    is XmlNamespaceEndChunk -> {
                        // There must be a corresponding open namespace
                        val openNamespace = openNamespaces
                            .removeLastOrNull()
                            ?: raise(FromBytesError)

                        // The namespace end chunk must match the open namespace
                        val chunkNamespace = OpenNamespace(
                            prefix = readNonEmptyString(chunk::getPrefix).bind(),
                            uri = readNonEmptyString(chunk::getUri).bind(),
                        )
                        ensure(chunkNamespace == openNamespace) { FromBytesError }
                    }

                    is XmlStartElementChunk -> {
                        // Ensure we're either the root element or inside another element
                        ensure(openElements.isNotEmpty() || root.isNone()) {
                            FromBytesError
                        }

                        val namespace = readNonEmptyStringOption(chunk::getNamespace).bind()
                        val name = readNonEmptyString(chunk::getName).bind()
                        val attributes = chunk.attributes
                            .map { toXmlAttribute(it, resourceMap, stringPool).bind() }
                            .let(XmlAttributes::fromList)
                            .bindMapLeft { FromBytesError }

                        openElements.addLast(
                            OpenElement(
                                namespace = namespace,
                                name = name,
                                attributes = attributes,
                                children = mutableListOf(),
                            )
                        )
                    }

                    is XmlEndElementChunk -> {
                        // There must be a corresponding open element
                        val openElement = openElements.removeLastOrNull() ?: raise(FromBytesError)

                        // The element end chunk must match its corresponding start chunk
                        val chunkNamespace = readNonEmptyStringOption(chunk::getNamespace).bind()
                        val chunkName = readNonEmptyString(chunk::getName).bind()
                        ensure(
                            chunkNamespace == openElement.namespace
                                    && chunkName == openElement.name
                        ) {
                            FromBytesError
                        }

                        val expandedName =
                            toExpandedName(openElement.namespace, openElement.name).bind()
                        val element = XmlElement(expandedName, openElement.attributes, openElement.children)
                        when (val parent = openElements.lastOrNone()) {
                            None -> root = Some(element)
                            is Some -> parent.value.children.add(element)
                        }
                    }

                    // Unrecognized chunk type
                    else -> raise(FromBytesError)
                }
            }

            // There must be no unclosed namespaces or elements
            ensure(openNamespaces.isEmpty()) { FromBytesError }
            ensure(openElements.isEmpty()) { FromBytesError }

            // There must be a root element
            XmlDocument(root.toEitherBind { FromBytesError })
        }

        private fun readString(read: () -> String): Either<FromBytesError, String> {
            return try {
                Either.Right(read())
            } catch (_: RuntimeException) {
                Either.Left(FromBytesError)
            }
        }

        private fun readNonEmptyStringOption(
            read: () -> String,
        ): Either<FromBytesError, Option<NonEmptyString>> {
            return readString(read).map(NonEmptyString::fromString)
        }

        private fun readNonEmptyString(
            read: () -> String,
        ): Either<FromBytesError, NonEmptyString> = either {
            readNonEmptyStringOption(read).map { it.toEitherBind { FromBytesError } }.bind()
        }

        private fun <T> List<T>.atMostOne(): Either<FromBytesError, Option<T>> {
            return when (size) {
                0 -> Either.Right(None)
                1 -> Either.Right(Some(this[0]))
                else -> Either.Left(FromBytesError)
            }
        }

        private fun toExpandedName(
            namespace: Option<NonEmptyString>,
            localName: NonEmptyString,
        ): Either<FromBytesError, XmlExpandedName> = either {
            XmlExpandedName(
                namespaceName = namespace.map {
                    Uri.fromString(it.value).toEitherBind { FromBytesError }
                },
                localName = AsciiNcName
                    .fromString(localName.value)
                    .toEitherBind { FromBytesError },
            )
        }

        private fun resourceIdOf(
            resourceMap: XmlResourceMapChunk,
            nameIndex: Int,
        ): Option<ResourceId> {
            return resourceMap
                .hasResourceId(nameIndex)
                .then { resourceMap.getResourceId(nameIndex) }
                .map {
                    ResourceIdentifier
                        .asInt(it.packageId(), it.typeId(), it.entryId())
                        .toUInt()
                        .let(::ResourceId)
                }
        }

        private fun toXmlAttribute(
            attribute: BinaryXmlAttribute,
            resourceMap: Option<XmlResourceMapChunk>,
            stringPool: Option<StringPoolChunk>,
        ): Either<FromBytesError, XmlAttribute> = either {
            val namespace = readNonEmptyStringOption(attribute::namespace).bind()
            val name = readNonEmptyString(attribute::name).bind()
            val typedValue = attribute.typedValue()

            XmlAttribute(
                id = XmlAttributeId(
                    name = toExpandedName(namespace, name).bind(),
                    resourceId = resourceMap.flatMap { resourceIdOf(it, attribute.nameIndex()) },
                ),
                value = when (typedValue.type()) {
                    BinaryResourceValue.Type.STRING -> {
                        // Ensure the raw value is the same as the typed value to prevent parser
                        // differential vulnerabilities.
                        ensure(attribute.rawValueIndex() == typedValue.data()) { FromBytesError }

                        stringPool
                            .toEitherBind { FromBytesError }
                            .let { sp -> readString { sp.getString(typedValue.data()) } }
                            .bind()
                            .let(ResourceValue::String)
                    }

                    BinaryResourceValue.Type.INT_DEC -> ResourceValue.IntDec(typedValue.data())

                    // The documentation for TypedArray.getBoolean() at
                    // https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d/core/java/android/content/res/TypedArray.java#385
                    // states:
                    //
                    // > If the attribute is an integer value, this method returns false if the
                    // > attribute is equal to zero, and true otherwise
                    //
                    // Thus, it's okay for us to parse booleans in the same way since doing so
                    // exhibits behavior at least as strict as the Android platform's.
                    BinaryResourceValue.Type.INT_BOOLEAN ->
                        ResourceValue.Bool(typedValue.data() != 0)

                    else -> ResourceValue.Unsupported(typedValue.data().toUInt())
                },
            )
        }

        private data class OpenNamespace(val prefix: NonEmptyString, val uri: NonEmptyString)

        private data class OpenElement(
            val namespace: Option<NonEmptyString>,
            val name: NonEmptyString,
            val attributes: XmlAttributes,
            val children: MutableList<XmlElement>
        )
    }

    data object FromBytesError
}

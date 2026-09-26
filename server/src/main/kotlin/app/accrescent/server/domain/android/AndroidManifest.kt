// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.android

import app.accrescent.server.core.bindMapLeft
import app.accrescent.server.core.downcast
import app.accrescent.server.core.toEitherBind
import app.accrescent.server.core.unwrap
import app.accrescent.server.domain.android.xml.AsciiNcName
import app.accrescent.server.domain.android.xml.ResourceId
import app.accrescent.server.domain.android.xml.ResourceValue
import app.accrescent.server.domain.android.xml.XmlAttributeId
import app.accrescent.server.domain.android.xml.XmlAttributes
import app.accrescent.server.domain.android.xml.XmlDocument
import app.accrescent.server.domain.android.xml.XmlElement
import app.accrescent.server.domain.android.xml.XmlExpandedName
import app.accrescent.server.domain.uri.Uri
import arrow.core.Either
import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import arrow.core.firstOrNone
import arrow.core.getOrElse
import arrow.core.raise.Raise
import arrow.core.raise.either
import arrow.core.raise.ensure

/**
 * An [Android application manifest](https://developer.android.com/guide/topics/manifest/manifest-intro).
 *
 * This class represents only the subset of the Android manifest that we need for processing.
 *
 * @property applicationId the manifest's
 * [application ID](https://developer.android.com/build/configure-app-module#set-application-id).
 * @property splitId the manifest's
 * [split ID](https://github.com/google/bundletool/blob/586a43a450712a1067f3d92cf7574dee68226302/src/main/proto/commands.proto#L279)
 * if set.
 * @property versionCode the manifest's
 * [long version code](https://developer.android.com/reference/android/content/pm/PackageInfo.html#getLongVersionCode()).
 * @property versionName the manifest's
 * [version name](https://developer.android.com/studio/publish/versioning#versioningsettings) if set.
 * @property minSdkVersion the manifest's effective
 * [minSdkVersion](https://developer.android.com/guide/topics/manifest/uses-sdk-element#min).
 * @property targetSdkVersion the manifest's effective
 * [targetSdkVersion](https://developer.android.com/guide/topics/manifest/uses-sdk-element#target).
 * @property permissions the
 * [permissions](https://developer.android.com/guide/topics/manifest/uses-permission-element) the
 * manifest requests, mapping each permission's unique name to its optional
 * [maxSdkVersion](https://developer.android.com/guide/topics/manifest/uses-permission-element#maxSdk).
 */
data class AndroidManifest(
    val applicationId: ApplicationId,
    val splitId: Option<String>,
    val versionCode: VersionCode,
    val versionName: Option<VersionName>,
    val minSdkVersion: SdkVersion,
    val targetSdkVersion: SdkVersion,
    val permissions: Map<NameAttribute, Option<SdkVersion>>,
) {
    companion object {
        /**
         * Parses an Android manifest from an XML document.
         *
         * @param document the XML document to parse the manifest from.
         */
        fun fromXmlDocument(document: XmlDocument): Either<FromXmlError, AndroidManifest> = either {
            ensure(document.root.name == Elements.MANIFEST) { FromXmlError.InvalidManifest }

            // Manifest element and its attributes
            val manifestAttributes = document.root.attributes
            val applicationId = manifestAttributes.findString(Attributes.PACKAGE)
                .toEitherBind { FromXmlError.InvalidManifest }
                .let(ApplicationId::fromString)
                .toEitherBind { FromXmlError.Policy.InvalidApplicationId }
            val split = manifestAttributes.findString(Attributes.SPLIT)
            val versionCode = manifestAttributes.findInt(Attributes.VERSION_CODE)
                .toEitherBind { FromXmlError.Policy.NoVersionCode }
                .let(VersionCode::fromInt)
                .toEitherBind { FromXmlError.Policy.VersionCodeOutOfRange }
            val versionCodeMajor = manifestAttributes
                .findInt(Attributes.VERSION_CODE_MAJOR)
                .getOrElse { 0 }
            ensure(versionCodeMajor == 0) { FromXmlError.Policy.VersionCodeMajorNonZero }
            val versionName = manifestAttributes.findString(Attributes.VERSION_NAME)
                .map { name ->
                    VersionName.fromString(name)
                        .toEitherBind { FromXmlError.Policy.VersionNameTooLong }
                }

            // Application element and its attributes
            val applicationElement = document.root
                .findUniqueChild(Elements.APPLICATION) {
                    FromXmlError.Policy.MultipleApplicationElements
                }
                .toEitherBind { FromXmlError.InvalidManifest }

            // Check compliance with debuggable requirement
            //
            // https://accrescent.app/docs/guide/appendix/requirements.html#androiddebuggable
            val debuggable = applicationElement.attributes
                .findBool(Attributes.DEBUGGABLE)
                // Defaults to false according to
                // https://developer.android.com/guide/topics/manifest/application-element#debug
                .getOrElse { false }
            ensure(!debuggable) { FromXmlError.Policy.DebuggableTrue }

            // Check compliance with testOnly requirement
            //
            // https://accrescent.app/docs/guide/appendix/requirements.html#androidtestonly
            val testOnly = applicationElement.attributes
                .findBool(Attributes.TEST_ONLY)
                // Defaults to false according to
                // https://developer.android.com/guide/topics/manifest/application-element#testOnly
                .getOrElse { false }
            ensure(!testOnly) { FromXmlError.Policy.TestOnlyTrue }

            // uses-sdk element and its attributes
            val usesSdkElement = document.root.findUniqueChild(Elements.USES_SDK) {
                FromXmlError.Policy.MultipleUsesSdkElements
            }
            val minSdk = usesSdkElement
                .flatMap { it.attributes.findInt(Attributes.MIN_SDK_VERSION) }
                .map { SdkVersion.fromInt(it).toEitherBind { FromXmlError.InvalidManifest } }
                // According to
                // https://developer.android.com/guide/topics/manifest/uses-sdk-element#min,
                // the OS defaults minSdkVersion to 1 when absent.
                .getOrElse { SdkVersion.MINIMUM }
            val targetSdk = usesSdkElement
                .flatMap { it.attributes.findInt(Attributes.TARGET_SDK_VERSION) }
                .map { SdkVersion.fromInt(it).toEitherBind { FromXmlError.InvalidManifest } }
                // According to
                // https://developer.android.com/guide/topics/manifest/uses-sdk-element#target,
                // the OS defaults targetSdkVersion to minSdkVersion when absent.
                .getOrElse { minSdk }

            // Permission elements and their attributes
            val permissions = buildMap {
                val permissionElements = document.root.children.filter {
                    it.name == Elements.USES_PERMISSION
                            || it.name == Elements.USES_PERMISSION_SDK_23
                            || it.name == Elements.USES_PERMISSION_SDK_M
                }
                for (element in permissionElements) {
                    val name = element.attributes.findString(Attributes.PERMISSION_NAME)
                        .toEitherBind { FromXmlError.InvalidManifest }
                        .let(NameAttribute::fromString)
                        .toEitherBind { FromXmlError.Policy.PermissionNameTooLong }
                    val maxSdkVersion = element.attributes.findInt(Attributes.MAX_SDK_VERSION)
                        .map {
                            SdkVersion.fromInt(it)
                                .toEitherBind { FromXmlError.Policy.PermissionMaxSdkOutOfRange }
                        }
                    ensure(put(name, maxSdkVersion) == null) {
                        FromXmlError.Policy.DuplicatePermission
                    }
                }
            }

            AndroidManifest(
                applicationId = applicationId,
                splitId = split,
                versionCode = versionCode,
                versionName = versionName,
                minSdkVersion = minSdk,
                targetSdkVersion = targetSdk,
                permissions = permissions,
            )
        }
    }

    private object Elements {
        // https://developer.android.com/guide/topics/manifest/application-element
        val APPLICATION = unqualifiedName("application")

        // https://developer.android.com/guide/topics/manifest/manifest-element
        val MANIFEST = unqualifiedName("manifest")

        // https://developer.android.com/guide/topics/manifest/uses-permission-element
        val USES_PERMISSION = unqualifiedName("uses-permission")

        // https://developer.android.com/guide/topics/manifest/uses-permission-sdk-23-element
        val USES_PERMISSION_SDK_23 = unqualifiedName("uses-permission-sdk-23")

        // An old alias for uses-permission-sdk-23. No documentation exists for this element
        // anymore, and it cannot be produced by recent versions of aapt2, but it is still parsed as
        // a permission element in AOSP, so we need to parse it to prevent review bypasses.
        //
        // https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d/core/java/com/android/internal/pm/pkg/parsing/ParsingPackageUtils.java#208
        val USES_PERMISSION_SDK_M = unqualifiedName("uses-permission-sdk-m")

        // https://developer.android.com/guide/topics/manifest/uses-sdk-element
        val USES_SDK = unqualifiedName("uses-sdk")
    }

    private object Attributes {
        // https://developer.android.com/reference/android/R.attr#debuggable
        val DEBUGGABLE = androidAttribute("debuggable", 0x0101000fu)

        // https://developer.android.com/reference/android/R.attr#maxSdkVersion
        val MAX_SDK_VERSION = androidAttribute("maxSdkVersion", 0x01010271u)

        // https://developer.android.com/reference/android/R.attr#minSdkVersion
        val MIN_SDK_VERSION = androidAttribute("minSdkVersion", 0x0101020cu)

        // https://developer.android.com/guide/topics/manifest/manifest-element#package
        val PACKAGE = XmlAttributeId(unqualifiedName("package"), None)

        // https://developer.android.com/reference/android/R.attr#name
        val PERMISSION_NAME = androidAttribute("name", 0x01010003u)

        // https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d/core/java/android/content/pm/parsing/ApkLiteParseUtils.java#926
        val SPLIT = XmlAttributeId(unqualifiedName("split"), None)

        // https://developer.android.com/reference/android/R.attr#targetSdkVersion
        val TARGET_SDK_VERSION = androidAttribute("targetSdkVersion", 0x01010270u)

        // https://developer.android.com/reference/android/R.attr#testOnly
        val TEST_ONLY = androidAttribute("testOnly", 0x01010272u)

        // https://developer.android.com/reference/android/R.attr#versionCode
        val VERSION_CODE = androidAttribute("versionCode", 0x0101021bu)

        // https://developer.android.com/reference/android/R.attr#versionCodeMajor
        val VERSION_CODE_MAJOR = androidAttribute("versionCodeMajor", 0x01010576u)

        // https://developer.android.com/reference/android/R.attr#versionName
        val VERSION_NAME = androidAttribute("versionName", 0x0101021cu)
    }

    sealed class FromXmlError {
        /**
         * The manifest could not have been produced by official Android developer tooling (i.e.,
         * aapt2). This covers every incorrectly typed attribute, every attribute whose name and
         * resource ID don't consistently identify it (which would let a name-based parser and a
         * resource-ID-based parser like the Android platform disagree on its value), and every
         * missing element or attribute that aapt2 always supplies.
         */
        data object InvalidManifest : FromXmlError()

        /**
         * The manifest is well-formed and could have been produced by official Android developer
         * tooling, but it violates an Accrescent-specific policy.
         */
        sealed class Policy : FromXmlError() {
            /**
             * The manifest declares the same permission name more than once across the
             * [`uses-permission`](https://developer.android.com/guide/topics/manifest/uses-permission-element),
             * [`uses-permission-sdk-23`](https://developer.android.com/guide/topics/manifest/uses-permission-sdk-23-element),
             * and `uses-permission-sdk-m` elements.
             */
            data object DuplicatePermission : Policy()

            /**
             * The manifest's
             * [package](https://developer.android.com/guide/topics/manifest/manifest-element#package)
             * attribute doesn't meet Accrescent's application ID requirements (e.g. it's too
             * long).
             */
            data object InvalidApplicationId : Policy()

            /**
             * The application element's
             * [debuggable](https://developer.android.com/guide/topics/manifest/application-element#debug)
             * attribute is `true`, which is not allowed in Accrescent because it can be a security
             * issue.
             */
            data object DebuggableTrue : Policy()

            /**
             * The application element's
             * [testOnly](https://developer.android.com/guide/topics/manifest/application-element#testOnly)
             * attribute is `true`, which is not allowed in Accrescent because it can cause an app
             * to expose security holes.
             */
            data object TestOnlyTrue : Policy()

            /**
             * The manifest contains multiple
             * [application](https://developer.android.com/guide/topics/manifest/application-element)
             * elements.
             */
            data object MultipleApplicationElements : Policy()

            /**
             * The manifest contains multiple
             * [uses-sdk](https://developer.android.com/guide/topics/manifest/uses-sdk-element)
             * elements.
             */
            data object MultipleUsesSdkElements : Policy()

            /**
             * The manifest has no
             * [versionCode](https://developer.android.com/guide/topics/manifest/manifest-element#vcode)
             * attribute.
             */
            data object NoVersionCode : Policy()

            /**
             * A requested permission's
             * [maxSdkVersion](https://developer.android.com/guide/topics/manifest/uses-permission-element#maxSdk)
             * is out of the range of valid values.
             */
            data object PermissionMaxSdkOutOfRange : Policy()

            /**
             * A requested permission's
             * [name](https://developer.android.com/guide/topics/manifest/uses-permission-element#nm)
             * attribute is too long.
             */
            data object PermissionNameTooLong : Policy()

            /**
             * The manifest's
             * [versionCode](https://developer.android.com/guide/topics/manifest/manifest-element#vcode)
             * attribute is out of the
             * [range of valid values](https://developer.android.com/studio/publish/versioning#versioningsettings).
             */
            data object VersionCodeOutOfRange : Policy()

            /**
             * The manifest's
             * [versionCodeMajor](https://developer.android.com/reference/android/R.attr#versionCodeMajor)
             * attribute is a non-zero value, which is not allowed in Accrescent.
             */
            data object VersionCodeMajorNonZero : Policy()

            /**
             * The manifest's
             * [versionName](https://developer.android.com/studio/publish/versioning#versioningsettings)
             * attribute is too long.
             */
            data object VersionNameTooLong : Policy()
        }
    }
}

/**
 * Finds an attribute with a specific resource type.
 *
 * Raises [InvalidManifest][AndroidManifest.FromXmlError.InvalidManifest] if the attribute ID is
 * ambiguous or if the resource type doesn't match what's expected.
 *
 * @param attributeId the ID of the attribute to find.
 * @param V the resource value type the attribute is expected to hold.
 * @return the attribute value, or [None] if the attribute is absent.
 */
context(_: Raise<AndroidManifest.FromXmlError>)
private inline fun <reified V : ResourceValue> XmlAttributes.findTyped(
    attributeId: XmlAttributeId,
): Option<V> {
    return findMatch(attributeId)
        .bindMapLeft { AndroidManifest.FromXmlError.InvalidManifest }
        .map { value ->
            value
                .downcast<V>()
                .toEitherBind { AndroidManifest.FromXmlError.InvalidManifest }
        }
}

context(_: Raise<AndroidManifest.FromXmlError>)
private fun XmlAttributes.findBool(attributeId: XmlAttributeId): Option<Boolean> {
    return findTyped<ResourceValue.Bool>(attributeId).map { it.value }
}

context(_: Raise<AndroidManifest.FromXmlError>)
private fun XmlAttributes.findInt(attributeId: XmlAttributeId): Option<Int> {
    return findTyped<ResourceValue.IntDec>(attributeId).map { it.value }
}

context(_: Raise<AndroidManifest.FromXmlError>)
private fun XmlAttributes.findString(attributeId: XmlAttributeId): Option<String> {
    return findTyped<ResourceValue.String>(attributeId).map { it.value }
}

/**
 * Finds this element's only child element named [name].
 *
 * @param name the name of the child element to find.
 * @param onMultiple the error to raise if more than one child element has the given name.
 * @return the matching child element, or [None] if no child element has the given name.
 */
context(raise: Raise<AndroidManifest.FromXmlError>)
private inline fun XmlElement.findUniqueChild(
    name: XmlExpandedName,
    onMultiple: () -> AndroidManifest.FromXmlError,
): Option<XmlElement> {
    val matches = children.filter { it.name == name }
    if (matches.size > 1) {
        raise.raise(onMultiple())
    }

    return matches.firstOrNone()
}

private fun unqualifiedName(localName: String): XmlExpandedName {
    return XmlExpandedName(None, AsciiNcName.fromString(localName).unwrap())
}

private val ANDROID_NAMESPACE_URI =
    Uri.fromString("http://schemas.android.com/apk/res/android").unwrap()

private fun androidAttribute(localName: String, resourceId: UInt): XmlAttributeId {
    return XmlAttributeId(
        name = XmlExpandedName(
            namespaceName = Some(ANDROID_NAMESPACE_URI),
            localName = AsciiNcName.fromString(localName).unwrap(),
        ),
        resourceId = Some(ResourceId(resourceId)),
    )
}

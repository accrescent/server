// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.appstore

import arrow.core.Some
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class ListingLanguageTest {
    @ParameterizedTest
    @MethodSource("fromLanguageTagTestCases")
    fun `fromLanguageTag returns correct listing language`(
        testCase: FromLanguageTagTestCase,
    ) {
        val result = ListingLanguage.fromLanguageTag(testCase.languageTag)

        assertEquals(Some(testCase.expectedListingLanguage), result)
    }

    @ParameterizedTest
    @MethodSource("languageTagTestCases")
    fun `languageTag returns correct BCP-47 language tag`(testCase: LanguageTagTestCase) {
        val result = testCase.language.languageTag()

        assertEquals(testCase.expectedLanguageTag, result)
    }

    companion object {
        private val LANGUAGE_TAG_MAP = mapOf(ListingLanguage.EN_US to "en-US")

        @JvmStatic
        fun fromLanguageTagTestCases(): List<FromLanguageTagTestCase> {
            return LANGUAGE_TAG_MAP.map { (lang, tag) ->
                FromLanguageTagTestCase(tag, lang)
            }
        }

        @JvmStatic
        fun languageTagTestCases(): List<LanguageTagTestCase> {
            return LANGUAGE_TAG_MAP.map { (lang, tag) ->
                LanguageTagTestCase(ListingLanguage.EN_US, "en-US")
            }
        }
    }

    data class FromLanguageTagTestCase(
        val languageTag: String,
        val expectedListingLanguage: ListingLanguage,
    )

    data class LanguageTagTestCase(val language: ListingLanguage, val expectedLanguageTag: String)
}

// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain

import app.accrescent.server.adapters.driven.randomsource.DeterministicRandomSource
import app.accrescent.server.core.unwrap
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class IdGeneratorTest {
    @ParameterizedTest
    @MethodSource("generateIdGeneratesIdsWithCorrectPrefixTestCases")
    fun `generateId generates IDs with correct prefix`(
        testCase: GenerateIdGeneratesIdsWithCorrectPrefixTestCase,
    ) {
        val id = newIdGenerator().generateId(testCase.idType).unwrap()

        assertTrue(id.startsWith(testCase.expectedPrefix))
    }

    companion object {
        data class GenerateIdGeneratesIdsWithCorrectPrefixTestCase(
            val idType: IdType,
            val expectedPrefix: String,
        )

        @JvmStatic
        private fun generateIdGeneratesIdsWithCorrectPrefixTestCases():
                List<GenerateIdGeneratesIdsWithCorrectPrefixTestCase> {
            return listOf(
                GenerateIdGeneratesIdsWithCorrectPrefixTestCase(IdType.APP, "app"),
                GenerateIdGeneratesIdsWithCorrectPrefixTestCase(IdType.APP_DRAFT, "ad"),
                GenerateIdGeneratesIdsWithCorrectPrefixTestCase(IdType.APP_DRAFT_LISTING, "adl"),
                GenerateIdGeneratesIdsWithCorrectPrefixTestCase(IdType.APP_PACKAGE, "pkg"),
                GenerateIdGeneratesIdsWithCorrectPrefixTestCase(IdType.BLOB_OBJECT_KEY, "obj"),
                GenerateIdGeneratesIdsWithCorrectPrefixTestCase(IdType.EXTERNAL_BLOB, "blob"),
                GenerateIdGeneratesIdsWithCorrectPrefixTestCase(IdType.ORGANIZATION, "org"),
                GenerateIdGeneratesIdsWithCorrectPrefixTestCase(IdType.PENDING_APP_DRAFT_UPLOAD, "adu"),
                GenerateIdGeneratesIdsWithCorrectPrefixTestCase(
                    IdType.PENDING_APP_DRAFT_LISTING_ICON_UPLOAD,
                    "adliu",
                ),
                GenerateIdGeneratesIdsWithCorrectPrefixTestCase(IdType.SESSION, "s"),
                GenerateIdGeneratesIdsWithCorrectPrefixTestCase(IdType.USER, "u"),
            )
        }

        private fun newIdGenerator(): IdGenerator {
            return IdGenerator(DeterministicRandomSource())
        }
    }
}
// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.detekt

import dev.detekt.api.RuleSet
import dev.detekt.api.RuleSetId
import dev.detekt.api.RuleSetProvider

class AccrescentRuleSetProvider : RuleSetProvider {
    override val ruleSetId = RuleSetId("accrescent")

    override fun instance(): RuleSet = RuleSet(
        ruleSetId,
        listOf(
            ::DirectPreparedStatementExecuteUpdate,
            ::JavaBase64Usage,
            ::TruncatingIntegerConversion,
            ::UnsafeJdbcResultSetMethodCall,
            ::UnusedEitherValue,
        ),
    )
}

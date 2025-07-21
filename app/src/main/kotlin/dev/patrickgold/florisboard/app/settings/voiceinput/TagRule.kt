/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.app.settings.voiceinput

import kotlinx.serialization.Serializable

@Serializable
data class TagRule(
    val id: Int,
    val user_id: String,
    val name: String,
    val rule_text: String,
    val created_ts: String,
    val updated_ts: String,
    val created_by: String?,
    val updated_by: String?
)

@Serializable
data class TagRuleRequest(
    val name: String,
    val rule_text: String,
    val created_by: String? = null,
    val updated_by: String? = null
)

@Serializable
data class TagRulesResponse(
    val success: Boolean,
    val rules: List<TagRule>
)

@Serializable
data class TagRuleResponse(
    val success: Boolean,
    val rule: TagRule
)

@Serializable
data class ApiError(
    val success: Boolean,
    val error: String
) 
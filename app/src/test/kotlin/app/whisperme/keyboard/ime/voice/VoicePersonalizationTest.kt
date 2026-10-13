package app.whisperme.keyboard.ime.voice

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class VoicePersonalizationTest {
    @Test fun catalogAndMappingsUseOnlyExplicitCanonicalAssignments() {
        assertEquals(listOf("personal", "business", "development", "writing", "productivity", "education", "other"), voiceAppCategories.map { it.id })
        assertEquals(mapOf("editor" to "business"), readVoiceAppCategories("""{"editor":"business","otherApp":"guessed","*":"personal"}"""))
        assertTrue(readVoiceAppCategories("broken").isEmpty())
        assertFalse(VoicePreferenceScope("category", categoryId = "unknown").valid())
        assertFalse(VoicePreferenceScope("all", "editor").valid())
    }

    @Test fun normalizedCategoryResponseShowsReadableScopeWithoutInternalIdentifier() {
        val mappings = readVoiceRuleMappings("""[{"scope":"category","categoryId":"business","scopeName":"Business/work","app_platform":"*"},{"scope":"app","appIdentifier":"editor","app_platform":"android"},{"app_identifier":"category:education","app_platform":"*"},{"app_identifier":"*","app_platform":"*"}]""")
        assertEquals(listOf("Category: Business/work", "My editor · android", "Category: Study/learning", "All apps"), mappings.map { it.label { id -> if (id == "editor") "My editor" else id } })
        assertEquals(VoicePreferenceScope("category", categoryId = "business"), mappings.first().preferenceScope())
    }

    @Test fun dictionaryUsesActualUtf8BytesAndPreservesOversizedLegacyDataForEditing() {
        val bounded = (1..25).map { "é".repeat(79) + it.toString(36) }
        assertEquals(3975, bounded.sumOf(::utf8Bytes))
        val full = Json.encodeToString(bounded)
        requireVoiceDictionaryBudget(bounded + listOf("x".repeat(61), "y".repeat(60))) // Exactly 4,096 UTF-8 bytes.
        assertThrows(IllegalArgumentException::class.java) { addVoiceDictionaryWord(full, "界".repeat(41)) }
        val oversized = Json.encodeToString(bounded + "界".repeat(41))
        assertEquals(26, readVoiceDictionary(oversized).size)
        assertThrows(IllegalArgumentException::class.java) { requireVoiceDictionaryBudget(readVoiceDictionary(oversized)) }
        val tooMany = Json.encodeToString((1..101).map { "word$it" })
        assertEquals(101, readVoiceDictionary(tooMany).size)
        assertThrows(IllegalArgumentException::class.java) { requireVoiceDictionaryBudget(readVoiceDictionary(tooMany)) }
    }

    @Test fun samplesHaveCountAndAggregateCharacterCaps() {
        VoicePreferenceLimits.requireSamples(List(5) { "x".repeat(2400) })
        for (bad in listOf(emptyList(), listOf(" "), List(6) { "sample" }, listOf("x".repeat(12001)))) {
            assertThrows(IllegalArgumentException::class.java) { VoicePreferenceLimits.requireSamples(bad) }
        }
    }

    @Test fun activeBudgetIncludesMostSpecificLocalStyleAndCloudScopeOverlaps() {
        val rule = "界".repeat(2000) // 6,000 actual bytes; token counts are approximate.
        val cloud = listOf(
            VoiceRuleBudgetEntry(rule, listOf(VoicePreferenceScope())),
            VoiceRuleBudgetEntry(rule, listOf(VoicePreferenceScope("category", categoryId = "business"))),
        )
        val profiles = listOf(VoicePreferenceProfile("1", "Local style", rule, "app", listOf("editor")))
        requireVoiceRuleBudgets(cloud, profiles, emptyMap())
        assertThrows(IllegalArgumentException::class.java) { requireVoiceRuleBudgets(cloud, profiles, mapOf("editor" to "business")) }
        val overlapping = listOf(VoiceRuleBudgetEntry(rule, listOf(VoicePreferenceScope(), VoicePreferenceScope("app", "editor"))))
        requireVoiceRuleBudgets(overlapping, profiles, emptyMap()) // Count a rule once, even with multiple mappings.
    }

    @Test fun savedCapsAndIndividualRuleCapsAreIndependent() {
        assertTrue(VoicePreferenceLimits.validRule("é".repeat(2000)))
        assertFalse(VoicePreferenceLimits.validRule("x".repeat(2001)))
        VoicePreferenceLimits.requireSaved(List(32) { "x".repeat(2000) })
        assertThrows(IllegalArgumentException::class.java) { VoicePreferenceLimits.requireSaved(List(33) { "x".repeat(2000) }) }
        assertThrows(IllegalArgumentException::class.java) { VoicePreferenceLimits.requireSaved(List(101) { "a" }) }
    }

    @Test fun legacyCategoriesRetainTheirExplicitPackagesAndCanonicalCategoryUsesUserMapping() {
        val legacy = VoicePreferenceProfile("old", "Custom category", "Legacy style", "category", listOf("chat"), "my_old_category")
        val canonical = VoicePreferenceProfile("new", "Work", "Work style", "category", categoryId = "business")
        val raw = Json.encodeToString(listOf(legacy, canonical))
        assertEquals(legacy, selectVoiceProfile(raw, true, "chat"))
        assertEquals(canonical, selectVoiceProfile(raw, true, "work", "business"))
        assertNull(selectVoiceProfile(raw, true, "unassigned"))
        assertEquals(listOf(legacy, canonical), readVoiceProfiles(raw))
        val longLegacy = legacy.copy(categoryId = "x".repeat(220))
        assertEquals(longLegacy, selectVoiceProfile(Json.encodeToString(listOf(longLegacy)), true, "chat"))
    }

    @Test fun validatesStyleResponseBeforeReplacingDraftAndSupportsLegacyInstructionsOnly() {
        val selected = VoicePreferenceScope("category", categoryId = "education")
        assertEquals(selected, readVoiceStyleDraft("""{"instructions":"Use plain language."}""").resolvedScope("editor", selected))
        val draft = readVoiceStyleDraft("""{"instructions":"Use plain language.","scope":"category","categoryId":"writing","name":"Publishing","scopeReason":"Writing examples"}""")
        assertEquals(VoicePreferenceScope("category", categoryId = "writing"), draft.resolvedScope("editor", selected))
        for (invalid in listOf(draft.copy(categoryId = "invented"), draft.copy(scope = "unknown"), draft.copy(scope = "app", categoryId = null, appIdentifier = "other"), draft.copy(instructions = "x".repeat(2001)))) {
            assertThrows(RuntimeException::class.java) { invalid.resolvedScope("editor", selected) }
        }
    }

    @Test fun updatingOrDeletingOneStylePreservesUnrelatedLegacyAndUnknownFields() {
        val raw = """[{"id":"old","name":"Legacy","instructions":"Keep wording","kind":"category","packages":["chat"],"categoryId":"custom_old","futureSetting":42},{"id":"edit","name":"App","instructions":"Old","kind":"app","packages":["editor"],"futureSetting":7}]"""
        val edited = updateVoiceProfile(raw, VoicePreferenceProfile("edit", "General", "New"))
        val json = Json.parseToJsonElement(edited).toString()
        assertTrue(json.contains("\"futureSetting\":42"))
        assertTrue(json.contains("\"futureSetting\":7"))
        assertEquals("custom_old", readVoiceProfiles(edited).first().categoryId)
        assertEquals("all", readVoiceProfiles(edited).last().kind)
        val removed = removeVoiceProfile(edited, "edit")
        assertEquals("old", readVoiceProfiles(removed).single().id)
        assertTrue(removed.contains("\"futureSetting\":42"))
    }
}

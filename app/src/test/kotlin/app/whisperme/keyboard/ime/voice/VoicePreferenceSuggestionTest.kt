package app.whisperme.keyboard.ime.voice

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class VoicePreferenceSuggestionTest {
    private val originalApp = "com.example.editor"
    private val confirmation = """{"kind":"preference","text":"Use concise sentences.","scope":"app","appIdentifier":"com.example.editor","requiresConfirmation":true}"""

    @Test fun legacyDraftDefaultsToCapturedAppWithoutAutomaticPersistence() {
        val draft = readVoicePreferenceSuggestion("""{"kind":"preference","text":"Use concise sentences."}""", originalApp)!!
        assertEquals(originalApp, draft.packageName)
        assertFalse(draft.defaultAllApps)
        assertFalse(draft.requiresConfirmation)
        assertEquals("app", draft.saveScope(false))
        // Ordinary correction suggestions can still be broadened by the user's checkbox.
        assertEquals("all", draft.saveScope(true))
    }

    @Test fun confirmationKeepsOriginalAppAndCannotBecomeGlobal() {
        val draft = readVoicePreferenceSuggestion(confirmation, originalApp)!!
        assertTrue(draft.requiresConfirmation)
        assertFalse(draft.defaultAllApps)
        assertEquals(originalApp, draft.packageName)
        assertEquals("app", draft.saveScope(false))
        assertThrows(IllegalArgumentException::class.java) { draft.saveScope(true) }
        assertNull(readVoicePreferenceSuggestion(confirmation, "com.example.other"))
    }

    @Test fun omittedAdditiveScopeFieldsStayBoundToCapturedApp() {
        val draft = readVoicePreferenceSuggestion(
            """{"kind":"preference","text":"Use concise sentences.","requiresConfirmation":true}""", originalApp)!!
        assertEquals(originalApp, draft.packageName)
        assertEquals("app", draft.scope)
        assertFalse(draft.defaultAllApps)
    }

    @Test fun explicitGlobalCorrectionSuggestionStillRequiresUserSave() {
        val draft = readVoicePreferenceSuggestion(
            """{"kind":"preference","text":"Use concise sentences.","scope":"all"}""", originalApp)!!
        assertTrue(draft.defaultAllApps)
        assertEquals("app", draft.saveScope(false))
        assertEquals("all", draft.saveScope(true))
    }

    @Test fun rejectsCrossAppGlobalOrDictionaryConfirmationDrafts() {
        for (raw in listOf(
            confirmation.replace(originalApp, "com.example.other"),
            confirmation.replace("\"scope\":\"app\"", "\"scope\":\"all\""),
        )) {
            assertNull(readVoicePreferenceSuggestion(raw, originalApp))
            // Rejected confirmation drafts cannot become editor insertions.
            assertTrue(voicePreferenceNoticeOnly(raw, false, "must not insert", null))
        }
    }

    @Test fun rejectsMalformedAndUnboundedDrafts() {
        for (raw in listOf(null, "broken", "[]", "{}",
            """{"kind":"other","text":"rule"}""",
            """{"kind":"preference","text":"rule","scope":"other"}""",
            """{"kind":"preference","text":"rule","requiresConfirmation":"true"}""",
            """{"kind":"dictionary","text":"bad\nword"}""",
            """{"kind":"dictionary","text":"${"x".repeat(81)}"}""",
            """{"kind":"preference","text":"${"x".repeat(2001)}"}""")) {
            assertNull(readVoicePreferenceSuggestion(raw, originalApp), raw)
        }
        assertNull(readVoicePreferenceSuggestion(confirmation, ""))
        assertThrows(IllegalArgumentException::class.java) {
            VoicePreferenceSuggestion("preference", "rule", "").saveScope(false)
        }
    }

    @Test fun preservesExactDraftAndIgnoresFutureFields() {
        val raw = """{"kind":"preference","text":"Keep API and Straße.","futureField":42}"""
        assertEquals("Keep API and Straße.", readVoicePreferenceSuggestion(raw, originalApp)!!.text)
        assertEquals("Meyjohann", readVoicePreferenceSuggestion(
            """{"kind":"dictionary","text":"Meyjohann"}""", originalApp)!!.text)
    }

    @Test fun ordinaryCorrectionMayOfferBlankRuleEditor() {
        for (text in listOf("", "   ")) {
            for (marker in listOf("", ",\"requiresConfirmation\":false")) {
                val raw = """{"kind":"preference","text":"$text"$marker}"""
                val draft = readVoicePreferenceSuggestion(raw, originalApp)!!
                assertEquals(text, draft.text)
                assertEquals(originalApp, draft.packageName)
                assertFalse(draft.requiresConfirmation)
                assertFalse(draft.defaultAllApps)
                assertFalse(voicePreferenceNoticeOnly(raw, false, "Corrected text", null))
            }
        }
    }

    @Test fun blankDictionariesAndConfirmationDraftsAreStillRejected() {
        for (text in listOf("", "   ")) {
            assertNull(readVoicePreferenceSuggestion(
                """{"kind":"dictionary","text":"$text"}""", originalApp))
            val raw = """{"kind":"preference","text":"$text","requiresConfirmation":true}"""
            assertNull(readVoicePreferenceSuggestion(raw, originalApp))
            assertTrue(voicePreferenceNoticeOnly(raw, false, "must not insert", null))
        }
    }

    @Test fun confirmationIsNoticeOnlyForReadableAndUnreadableFieldsRegardlessOfReturnedText() {
        for (insertOnly in listOf(false, true)) {
            for (finalText in listOf(null, "", "unchanged existing field", "unexpected generated text")) {
                assertTrue(voicePreferenceNoticeOnly(confirmation, insertOnly, finalText, null))
                assertTrue(voicePreferenceNoticeOnly(confirmation, insertOnly, finalText, "Please confirm"))
            }
        }
    }

    @Test fun unreadableNoticeOnlyResponseDoesNotNeedSuggestionOrInsertion() {
        assertTrue(voicePreferenceNoticeOnly(null, true, "", "Preference handled"))
        // Ordinary dictation and correction suggestions must keep their insertion path.
        assertFalse(voicePreferenceNoticeOnly(null, true, "Dictated text", "Notice"))
        assertFalse(voicePreferenceNoticeOnly(null, true, "", null))
        assertFalse(voicePreferenceNoticeOnly(null, false, "", "Notice"))
        assertFalse(voicePreferenceNoticeOnly(
            """{"kind":"preference","text":"rule"}""", true, "Dictated text", null))
    }

    @Test fun malformedConfirmationMarkerCannotFallThroughToInsertion() {
        for (marker in listOf("\"true\"", "null", "1", "{}")) {
            val raw = """{"kind":"preference","text":"rule","requiresConfirmation":$marker}"""
            assertNull(readVoicePreferenceSuggestion(raw, originalApp))
            assertTrue(voicePreferenceNoticeOnly(raw, false, "must not insert", null))
        }
        assertFalse(voicePreferenceNoticeOnly(
            """{"kind":"preference","text":"rule","requiresConfirmation":false}""", false, "Dictation", null))
    }

    @Test fun dictionaryCorrectionInsertsButStandaloneDictionaryControlIsNoticeOnly() {
        for (marker in listOf("", ",\"requiresConfirmation\":false")) {
            val raw = """{"kind":"dictionary","text":"Meyjohann"$marker}"""
            assertNotNull(readVoicePreferenceSuggestion(raw, originalApp))
            assertFalse(voicePreferenceNoticeOnly(raw, false, "Corrected Meyjohann", null))
        }
        for (scope in listOf("app", "all")) {
            val raw = """{"kind":"dictionary","text":"Meyjohann","scope":"$scope","requiresConfirmation":true}"""
            assertNotNull(readVoicePreferenceSuggestion(raw, originalApp))
            assertTrue(voicePreferenceNoticeOnly(raw, false, "must not insert", null))
        }
    }

    @Test fun scopedStandaloneControlIsNoticeOnlyAndUserCanChangeScope() {
        val raw = """{"kind":"preference","text":"No semicolons.","scope":"category","categoryId":"business","requiresConfirmation":true,"scopeLocked":false,"scopeReason":"Fits work messages","name":"Work punctuation"}"""
        val draft = readVoicePreferenceSuggestion(raw, originalApp)!!
        assertTrue(voicePreferenceNoticeOnly(raw, false, "must not insert", null))
        assertFalse(draft.effectiveScopeLocked)
        assertEquals("business", draft.categoryId)
        assertEquals(VoicePreferenceScope(), draft.saveScope(VoicePreferenceScope()))
        assertEquals(VoicePreferenceScope("category", categoryId = "education"), draft.saveScope(VoicePreferenceScope("category", categoryId = "education")))
        assertNull(readVoicePreferenceSuggestion(raw.replace("business", "invented"), originalApp))
        assertNull(readVoicePreferenceSuggestion(raw.replace("\"scopeLocked\":false", "\"scopeLocked\":\"false\""), originalApp))
        assertNull(readVoicePreferenceSuggestion(raw.replace("\"scopeLocked\":false", "\"scopeLocked\":true"), originalApp))
        assertThrows(IllegalArgumentException::class.java) { draft.saveScope(VoicePreferenceScope("app", "different.app")) }
    }
}

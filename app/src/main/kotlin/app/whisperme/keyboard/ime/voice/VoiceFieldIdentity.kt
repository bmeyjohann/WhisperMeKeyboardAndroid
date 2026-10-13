package app.whisperme.keyboard.ime.voice

/** A changed input session cannot be identified by package/fieldId alone. */
internal fun sameVoiceField(sameEditorObject: Boolean, sameGeneration: Boolean,
    sameConnection: Boolean, sameMetadata: Boolean): Boolean =
    sameGeneration && sameConnection && sameMetadata && sameEditorObject

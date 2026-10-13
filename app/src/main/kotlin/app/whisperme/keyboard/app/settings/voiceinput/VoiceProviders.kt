package app.whisperme.keyboard.app.settings.voiceinput

enum class VoiceTranscriptionProvider(val wireValue: String) {
    MUSE_REALTIME("muse_realtime"),
    GROQ_BATCH("groq_batch"),
    VOXTRAL_REALTIME("voxtral_realtime"),
}

enum class VoiceLlmProvider(val wireValue: String) {
    SERVER_DEFAULT("default"),
    GROQ_KIMI_K2("groq"),
    CEREBRAS_GLM_4_7("cerebras"),
}

fun VoiceLlmProvider.models(): List<Pair<String, String>> = when (this) {
    VoiceLlmProvider.SERVER_DEFAULT -> emptyList()
    VoiceLlmProvider.GROQ_KIMI_K2 -> listOf(
        "qwen/qwen3.8-27b" to "Qwen 3.8 27B (recommended)",
        "qwen/qwen3.6-27b" to "Qwen 3.6 27B",
        "openai/gpt-oss-120b" to "GPT-OSS 120B",
        "openai/gpt-oss-20b" to "GPT-OSS 20B",
    )
    VoiceLlmProvider.CEREBRAS_GLM_4_7 -> listOf(
        "gemma-4-31b" to "Gemma 4 31B (recommended)",
        "qwen-3.8-27b" to "Qwen 3.8 27B",
        "gpt-oss-120b" to "GPT-OSS 120B",
    )
}

fun VoiceLlmProvider.compatibleModel(model: String): String =
    models().firstOrNull { it.first == model }?.first ?: models().firstOrNull()?.first.orEmpty()

/** Unknown server defaults expose only Default until capabilities identifies the actual model. */
fun supportedVoiceReasoning(model: String?): List<String> {
    val normalized = model.orEmpty().lowercase()
    return when {
        normalized.contains("gpt-oss") -> listOf("default", "low", "medium", "high")
        normalized.contains("qwen") || normalized.contains("gemma") -> listOf("default", "none", "low", "medium", "high")
        else -> listOf("default")
    }
}

fun compatibleVoiceReasoning(model: String?, requested: String): String =
    requested.takeIf { it in supportedVoiceReasoning(model) } ?: "default"

fun voiceReasoningLabel(value: String): String = when (value) {
    "none" -> "None"
    "low" -> "Low"
    "medium" -> "Medium"
    "high" -> "High"
    else -> "Default"
}

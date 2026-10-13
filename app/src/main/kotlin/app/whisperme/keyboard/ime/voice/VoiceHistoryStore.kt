package app.whisperme.keyboard.ime.voice

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** Application-lifetime FIFO writer. Only the changed session is rewritten, on IO. */
class VoiceHistoryStore private constructor(context: Context) {
    private val directory = File(context.noBackupFilesDir, "voice-history")
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val mutableEntries = MutableStateFlow<List<VoiceHistoryEntry>>(emptyList())
    private val loaded = CompletableDeferred<Unit>()
    val entries = mutableEntries.asStateFlow()
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()
    private sealed interface Command {
        data class Save(val entry: VoiceHistoryEntry) : Command
        data class Delete(val id: String) : Command
        data object Clear : Command
    }
    init {
        scope.launch {
            val deleted = mutableSetOf<String>()
            val loaded = mutableListOf<VoiceHistoryEntry>()
            directory.listFiles()?.map { it.name.removeSuffix(".bak") }?.distinct()?.forEach { name ->
                if (name.endsWith(".json")) try {
                    loaded += json.decodeFromString<VoiceHistoryEntry>(AtomicFile(File(directory, name)).openRead().bufferedReader().use { it.readText() })
                } catch (e: Exception) { mutableError.value = "Some history could not be read: ${e.javaClass.simpleName}" }
            }
            mutableEntries.value = loaded.sortedByDescending { it.startedAt }
            this@VoiceHistoryStore.loaded.complete(Unit)
            for (command in commands) {
                try {
                    when (command) {
                        is Command.Save -> if (command.entry.id !in deleted) {
                            check(directory.isDirectory || directory.mkdirs()) { "Cannot create history directory" }
                            val file = AtomicFile(File(directory, "${command.entry.id}.json"))
                            val stream = file.startWrite()
                            try {
                                stream.write(json.encodeToString(command.entry).toByteArray(Charsets.UTF_8))
                                file.finishWrite(stream)
                            } catch (e: Exception) { file.failWrite(stream); throw e }
                            mutableEntries.value = retainVoiceHistory(mutableEntries.value, command.entry)
                        }
                        is Command.Delete -> {
                            deleted += command.id
                            deleteFile(command.id)
                            mutableEntries.value = mutableEntries.value.filterNot { it.id == command.id }
                        }
                        Command.Clear -> {
                            deleted += mutableEntries.value.map { it.id }
                            directory.listFiles()?.forEach { check(it.delete()) { "Cannot delete history file" } }
                            mutableEntries.value = emptyList()
                        }
                    }
                } catch (e: Exception) {
                    mutableError.value = "History operation failed: ${e.javaClass.simpleName}. Please retry deletion or check device storage."
                }
            }
        }
    }
    private fun deleteFile(id: String) {
        listOf("$id.json", "$id.json.bak", "$id.json.new").forEach { name ->
            val file = File(directory, name)
            check(!file.exists() || file.delete()) { "Cannot delete history file" }
        }
    }
    fun save(entry: VoiceHistoryEntry) { commands.trySend(Command.Save(entry)) }
    fun delete(id: String) { commands.trySend(Command.Delete(id)) }
    fun clear() { commands.trySend(Command.Clear) }
    suspend fun awaitLoaded() { loaded.await() }
    companion object {
        @Volatile private var instance: VoiceHistoryStore? = null
        fun get(context: Context): VoiceHistoryStore = instance ?: synchronized(this) {
            instance ?: VoiceHistoryStore(context.applicationContext).also { instance = it }
        }
    }
}

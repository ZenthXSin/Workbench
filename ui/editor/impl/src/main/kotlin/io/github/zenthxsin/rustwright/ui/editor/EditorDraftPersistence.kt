package io.github.zenthxsin.rustwright.ui.editor

import arc.Core
import io.github.zenthxsin.rustwright.core.editor.api.FileDraft
import io.github.zenthxsin.rustwright.core.editor.api.FileDraftStore
import io.github.zenthxsin.rustwright.core.editor.api.TextDocument
import io.github.zenthxsin.rustwright.ui.editor.api.EditorSupport
import kotlinx.coroutines.*
import java.io.File

/** Draft IO and change notifications use immutable snapshots of the active document. */
internal class EditorDraftPersistence(private val scope: CoroutineScope) {
    var syncedVersion = -1
        private set
    private var syncJob: Job? = null

    fun pause() { syncJob?.cancel(); syncedVersion = -1 }

    fun retainNow(draft: FileDraft?, store: FileDraftStore, document: TextDocument) {
        pause()
        draft?.let { runCatching { store.retain(it, document.text) } }
    }

    fun schedule(document: TextDocument, draft: FileDraft?, store: FileDraftStore,
                 root: File?, file: File, language: EditorSupport) {
        syncedVersion = document.version
        val lines = document.linesSnapshot()
        syncJob?.cancel()
        syncJob = scope.launch(Dispatchers.Default) {
            delay(400)
            val text = lines.joinToString("\n")
            draft?.let { runCatching { store.retain(it, text) } }
            if (root != null) language.changed(root, file, text)
        }
    }

    fun save(draft: FileDraft, store: FileDraftStore, lines: List<String>, complete: (Result<FileDraft>) -> Unit) {
        pause()
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { store.save(draft, lines.joinToString("\n")) } }
            Core.app.post { complete(result) }
        }
    }
}

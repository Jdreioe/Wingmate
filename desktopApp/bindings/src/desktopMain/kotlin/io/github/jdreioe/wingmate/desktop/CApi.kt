@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package io.github.jdreioe.wingmate.desktop

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.get
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlin.native.CName

private fun CPointer<ByteVar>?.string(): String = this?.toKString().orEmpty()

private fun ownedCString(value: String): CPointer<ByteVar> {
    val bytes = value.encodeToByteArray()
    val result = nativeHeap.allocArray<ByteVar>(bytes.size + 1)
    bytes.forEachIndexed { index, byte -> result[index] = byte }
    result[bytes.size] = 0
    return result
}

private inline fun call(context: COpaquePointer?, block: DesktopCore.() -> String): CPointer<ByteVar> {
    val result = runCatching { context!!.asStableRef<DesktopCore>().get().block() }
        .getOrElse { "{\"error\":${jsonString(it.message ?: "Desktop core operation failed")}}" }
    return ownedCString(result)
}

private fun jsonString(value: String): String = buildString {
    append('"')
    value.forEach { character ->
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(character)
        }
    }
    append('"')
}

@CName("wm_create")
fun create(dataDirectory: CPointer<ByteVar>?): COpaquePointer? =
    runCatching { StableRef.create(DesktopCore(dataDirectory.string())).asCPointer() }.getOrNull()

@CName("wm_destroy")
fun destroy(context: COpaquePointer?) {
    context?.asStableRef<DesktopCore>()?.dispose()
}

@CName("wm_string_free")
fun stringFree(value: CPointer<ByteVar>?) {
    if (value != null) nativeHeap.free(value.rawValue)
}

@CName("wm_library_json") fun library(context: COpaquePointer?) = call(context) { libraryJson() }
@CName("wm_recents_json") fun recents(context: COpaquePointer?) = call(context) { recentsJson() }
@CName("wm_import_file_json") fun importFile(context: COpaquePointer?, path: CPointer<ByteVar>?) = call(context) { importFileJson(path.string()) }
@CName("wm_open_json") fun open(context: COpaquePointer?, id: CPointer<ByteVar>?) = call(context) { openJson(id.string()) }
@CName("wm_activate_json") fun activate(context: COpaquePointer?, id: CPointer<ByteVar>?) = call(context) { activateJson(id.string()) }
@CName("wm_back_json") fun back(context: COpaquePointer?) = call(context) { backJson() }
@CName("wm_clear_json") fun clear(context: COpaquePointer?) = call(context) { clearJson() }
@CName("wm_hold_json") fun hold(context: COpaquePointer?) = call(context) { holdJson() }
@CName("wm_speak_json") fun speak(context: COpaquePointer?) = call(context) { speakJson() }
@CName("wm_settings_json") fun settings(context: COpaquePointer?) = call(context) { settingsJson() }
@CName("wm_update_settings_json") fun updateSettings(context: COpaquePointer?, value: CPointer<ByteVar>?) = call(context) { updateSettingsJson(value.string()) }
@CName("wm_pronunciations_json") fun pronunciations(context: COpaquePointer?) = call(context) { pronunciationsJson() }
@CName("wm_add_pronunciation_json") fun addPronunciation(context: COpaquePointer?, value: CPointer<ByteVar>?) = call(context) { addPronunciationJson(value.string()) }
@CName("wm_delete_pronunciation_json") fun deletePronunciation(context: COpaquePointer?, word: CPointer<ByteVar>?) = call(context) { deletePronunciationJson(word.string()) }
@CName("wm_export_backup_json") fun exportBackup(context: COpaquePointer?, path: CPointer<ByteVar>?) = call(context) { exportBackupJson(path.string()) }
@CName("wm_restore_backup_json") fun restoreBackup(context: COpaquePointer?, path: CPointer<ByteVar>?) = call(context) { restoreBackupJson(path.string()) }

@CName("wm_editor_json") fun editor(context: COpaquePointer?, value: CPointer<ByteVar>?) = call(context) { editorJson(value.string()) }

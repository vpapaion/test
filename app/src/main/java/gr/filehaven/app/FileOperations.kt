package gr.filehaven.app

import android.content.Context
import androidx.documentfile.provider.DocumentFile

/** Snapshot metadata off the UI thread: a DocumentsProvider may be remote or slow. */
data class FileItem(
    val document: DocumentFile,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    val mimeType: String?
)

enum class SortMode { NAME, DATE, SIZE }

data class PendingTransfer(val source: DocumentFile, val isCut: Boolean)

object FileOperations {
    fun list(folder: DocumentFile): List<FileItem> = folder.listFiles().map { file ->
        FileItem(
            document = file,
            name = file.name.orEmpty().ifBlank { "Unnamed" },
            isDirectory = file.isDirectory,
            size = file.length(),
            lastModified = file.lastModified(),
            mimeType = file.type
        )
    }

    fun checkName(name: String): String {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty() && trimmed != "." && trimmed != ".." &&
            '/' !in trimmed && '\u0000' !in trimmed) { "Enter a valid file name." }
        return trimmed
    }

    fun newDirectory(parent: DocumentFile, requestedName: String) {
        val name = checkName(requestedName)
        require(parent.findFile(name) == null) { "A file with that name already exists." }
        checkNotNull(parent.createDirectory(name)) { "Cannot create folder." }
    }

    fun newTextFile(parent: DocumentFile, requestedName: String) {
        val name = checkName(requestedName).let { if ('.' in it) it else "$it.txt" }
        require(parent.findFile(name) == null) { "A file with that name already exists." }
        checkNotNull(parent.createFile("text/plain", name)) { "Cannot create file." }
    }

    fun rename(document: DocumentFile, requestedName: String) {
        val name = checkName(requestedName)
        check(document.renameTo(name)) { "Rename failed (provider may disallow it)." }
    }

    fun delete(document: DocumentFile) {
        check(document.delete()) { "Delete failed (provider may disallow it)." }
    }

    /**
     * SAF-safe recursive copy using streams. A cut copies first and deletes only after a
     * complete copy. Moving is not atomic; this app must not be used as a backup tool.
     */
    fun transfer(context: Context, transfer: PendingTransfer, destination: DocumentFile) {
        val src = transfer.source
        require(src.exists()) { "Source no longer exists." }
        require(destination.isDirectory && destination.canWrite()) { "Destination is not writable." }
        require(src.uri != destination.uri) { "Cannot paste a folder into itself." }
        val copied = copyRecursively(context, src, destination)
        if (transfer.isCut) {
            // If this fails, keep the copied result and report it. Never silently discard data.
            check(src.delete()) { "Copied successfully, but the original could not be deleted." }
        }
        check(copied.exists()) { "The copied file is not available from this provider." }
    }

    private fun copyRecursively(
        context: Context,
        source: DocumentFile,
        destination: DocumentFile
    ): DocumentFile {
        val name = uniqueName(destination, source.name ?: "Untitled", source.isDirectory)
        val created = if (source.isDirectory) {
            destination.createDirectory(name)
        } else {
            destination.createFile(source.type ?: "application/octet-stream", name)
        } ?: error("Cannot create '$name' in destination.")

        try {
            if (source.isDirectory) {
                for (child in source.listFiles()) {
                    copyRecursively(context, child, created)
                }
            } else {
                val resolver = context.contentResolver
                val input = resolver.openInputStream(source.uri)
                    ?: error("Cannot read '${source.name}'.")
                val output = resolver.openOutputStream(created.uri, "w")
                    ?: error("Cannot write '$name'.")
                input.use { reader -> output.use { writer -> reader.copyTo(writer) } }
            }
            return created
        } catch (ex: Exception) {
            // Best effort cleanup if an incomplete copy is left behind.
            runCatching { created.delete() }
            throw ex
        }
    }

    private fun uniqueName(parent: DocumentFile, original: String, directory: Boolean): String {
        if (parent.findFile(original) == null) return original
        val dot = if (directory) -1 else original.lastIndexOf('.').takeIf { it > 0 } ?: -1
        val stem = if (dot < 0) original else original.substring(0, dot)
        val ext = if (dot < 0) "" else original.substring(dot)
        for (n in 1..1000) {
            val candidate = "$stem ($n)$ext"
            if (parent.findFile(candidate) == null) return candidate
        }
        error("Too many copies of '$original'.")
    }
}

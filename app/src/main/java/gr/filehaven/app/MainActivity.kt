package gr.filehaven.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private data class Notice(val id: Long, val text: String)

class MainActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val path = mutableStateListOf<DocumentFile>()
    private var entries by mutableStateOf<List<FileItem>>(emptyList())
    private var busy by mutableStateOf(false)
    private var notice by mutableStateOf<Notice?>(null)
    private var pending by mutableStateOf<PendingTransfer?>(null)
    private var requestId = 0
    private var messageId = 0L

    private val folderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> if (uri != null) selectFolder(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        restoreFolder()
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                FileHavenScreen(
                    path = path,
                    entries = entries,
                    busy = busy,
                    notice = notice,
                    pending = pending,
                    onPickFolder = { folderPicker.launch(null) },
                    onGoBack = { goBack() },
                    onOpen = { openItem(it) },
                    onRefresh = { refresh() },
                    onCreateFolder = { name -> mutate("Folder created") { FileOperations.newDirectory(current(), name) } },
                    onCreateFile = { name -> mutate("File created") { FileOperations.newTextFile(current(), name) } },
                    onRename = { file, name -> mutate("Renamed") { FileOperations.rename(file, name) } },
                    onDelete = { file -> mutate("Deleted") { FileOperations.delete(file) } },
                    onCopy = { file -> pending = PendingTransfer(file, false); info("Ready to copy. Open a destination and tap Paste.") },
                    onCut = { file -> pending = PendingTransfer(file, true); info("Ready to move. Open a destination and tap Paste.") },
                    onPaste = { paste() },
                    onShare = { share(it) }
                )
            }
        }
    }

    private fun current(): DocumentFile = path.lastOrNull() ?: error("Choose a folder first.")

    private fun selectFolder(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            val root = DocumentFile.fromTreeUri(this, uri) ?: error("Invalid folder.")
            getPreferences(MODE_PRIVATE).edit().putString("treeUri", uri.toString()).apply()
            path.clear()
            path.add(root)
            pending = null
            refresh()
        } catch (ex: Exception) {
            info(ex.message ?: "Unable to access the selected folder.")
        }
    }

    private fun restoreFolder() {
        val raw = getPreferences(MODE_PRIVATE).getString("treeUri", null) ?: return
        val uri = Uri.parse(raw)
        if (contentResolver.persistedUriPermissions.none { it.uri == uri && it.isReadPermission }) return
        DocumentFile.fromTreeUri(this, uri)?.let { path.add(it); refresh() }
    }

    private fun goBack() {
        if (path.size > 1) {
            path.removeAt(path.lastIndex)
            refresh()
        }
    }

    private fun openItem(file: FileItem) {
        if (file.isDirectory) {
            path.add(file.document)
            refresh()
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(file.document.uri, file.mimeType ?: "application/octet-stream")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (_: ActivityNotFoundException) {
            info("No installed app can open this file.")
        } catch (ex: Exception) {
            info(ex.message ?: "Could not open file.")
        }
    }

    private fun share(file: FileItem) {
        if (file.isDirectory) { info("Sharing an entire folder isn't supported."); return }
        try {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = file.mimeType ?: "application/octet-stream"
                putExtra(Intent.EXTRA_STREAM, file.document.uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Share file"))
        } catch (ex: Exception) {
            info(ex.message ?: "Could not share file.")
        }
    }

    private fun refresh() {
        val directory = path.lastOrNull() ?: return
        val id = ++requestId
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { FileOperations.list(directory) } }
            if (id != requestId) return@launch
            result.onSuccess { entries = it }.onFailure {
                entries = emptyList()
                info(it.message ?: "Could not read this folder.")
            }
        }
    }

    private fun mutate(success: String, task: () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { task() } }
            busy = false
            result.onSuccess { info(success) }.onFailure { info(it.message ?: "Operation failed.") }
            refresh()
        }
    }

    private fun paste() {
        val transfer = pending ?: return
        val destination = path.lastOrNull() ?: return
        // The current path is composed of directories reached through the app's navigator.
        // Reject copying a directory into itself or one of its own descendants.
        if (transfer.source.isDirectory && path.any { it.uri == transfer.source.uri }) {
            info("Cannot paste a folder inside itself.")
            return
        }
        if (busy) return
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { FileOperations.transfer(this@MainActivity, transfer, destination) }
            }
            busy = false
            result.onSuccess {
                pending = null
                info(if (transfer.isCut) "Moved successfully" else "Copied successfully")
            }.onFailure { info(it.message ?: "Paste failed.") }
            refresh()
        }
    }

    private fun info(text: String) { notice = Notice(++messageId, text) }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileHavenScreen(
    path: List<DocumentFile>,
    entries: List<FileItem>,
    busy: Boolean,
    notice: Notice?,
    pending: PendingTransfer?,
    onPickFolder: () -> Unit,
    onGoBack: () -> Unit,
    onOpen: (FileItem) -> Unit,
    onRefresh: () -> Unit,
    onCreateFolder: (String) -> Unit,
    onCreateFile: (String) -> Unit,
    onRename: (DocumentFile, String) -> Unit,
    onDelete: (DocumentFile) -> Unit,
    onCopy: (DocumentFile) -> Unit,
    onCut: (DocumentFile) -> Unit,
    onPaste: () -> Unit,
    onShare: (FileItem) -> Unit
) {
    val snackbar = remember { SnackbarHostState() }
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(SortMode.NAME) }
    var descending by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var createMenu by remember { mutableStateOf(false) }
    var createMode by remember { mutableStateOf<String?>(null) }
    var renameItem by remember { mutableStateOf<FileItem?>(null) }
    var deleteItem by remember { mutableStateOf<FileItem?>(null) }

    LaunchedEffect(notice?.id) {
        notice?.let { snackbar.showSnackbar(it.text) }
    }
    BackHandler(enabled = path.size > 1) { query = ""; onGoBack() }

    val shown = remember(entries, query, sort, descending) {
        val comparator = when (sort) {
            SortMode.NAME -> compareBy<FileItem> { it.name.lowercase() }
            SortMode.DATE -> compareBy<FileItem> { it.lastModified }
            SortMode.SIZE -> compareBy<FileItem> { it.size }
        }
        entries.filter { it.name.contains(query, ignoreCase = true) }
            .sortedWith(compareByDescending<FileItem> { it.isDirectory }.then(comparator))
            .let { if (descending) {
                // Reverse files within groups, without placing folders after regular files.
                val dirs = it.filter { entry -> entry.isDirectory }.reversed()
                dirs + it.filterNot { entry -> entry.isDirectory }.reversed()
            } else it }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("FileHaven", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    if (path.size > 1) TextButton(onClick = { query = ""; onGoBack() }) { Text("Back") }
                },
                actions = {
                    TextButton(onClick = onRefresh, enabled = path.isNotEmpty() && !busy) { Text("Refresh") }
                    TextButton(onClick = onPickFolder, enabled = !busy) { Text("Folders") }
                }
            )
        },
        floatingActionButton = {
            if (path.isNotEmpty()) Box {
                ExtendedFloatingActionButton(onClick = { createMenu = true }, text = { Text("New") }, icon = { Text("+", fontWeight = FontWeight.Bold) })
                DropdownMenu(expanded = createMenu, onDismissRequest = { createMenu = false }) {
                    DropdownMenuItem(text = { Text("New folder") }, onClick = { createMenu = false; createMode = "folder" })
                    DropdownMenuItem(text = { Text("New text file") }, onClick = { createMenu = false; createMode = "file" })
                }
            }
        }
    ) { insets ->
        if (path.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(insets).padding(28.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Your files, your choice", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(12.dp))
                Text("Choose a folder to browse and manage its contents. Access stays on your device.", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(24.dp))
                Button(onClick = onPickFolder) { Text("Choose a folder") }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(insets)) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp)) {
                    Text(path.joinToString(" / ") { it.name ?: "Storage" }, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Search this folder") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp)
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text("${shown.size} items", style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.weight(1f))
                        Box {
                            TextButton(onClick = { sortMenu = true }) { Text("Sort: ${sort.name.lowercase()}") }
                            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                                SortMode.entries.forEach { value ->
                                    DropdownMenuItem(text = { Text(value.name.lowercase().replaceFirstChar(Char::uppercaseChar)) }, onClick = { sort = value; sortMenu = false })
                                }
                            }
                        }
                        TextButton(onClick = { descending = !descending }) { Text(if (descending) "↓" else "↑") }
                    }
                    if (pending != null) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text(if (pending.isCut) "Moving: ${pending.source.name}" else "Copying: ${pending.source.name}",
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Button(onClick = onPaste, enabled = !busy) { Text("Paste") }
                        }
                    }
                }
                HorizontalDivider()
                if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                if (shown.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(if (query.isBlank()) "This folder is empty" else "No matching files",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(contentPadding = PaddingValues(bottom = 92.dp), modifier = Modifier.fillMaxSize()) {
                        items(shown, key = { it.document.uri.toString() }) { item ->
                            FileRow(item = item, onClick = { onOpen(item) }, onRename = { renameItem = item },
                                onDelete = { deleteItem = item }, onCopy = { onCopy(item.document) },
                                onCut = { onCut(item.document) }, onShare = { onShare(item) })
                        }
                    }
                }
            }
        }
    }

    createMode?.let { mode ->
        NameDialog(title = if (mode == "folder") "New folder" else "New text file", initial = "", onDismiss = { createMode = null }) { name ->
            if (mode == "folder") onCreateFolder(name) else onCreateFile(name)
            createMode = null
        }
    }
    renameItem?.let { item ->
        NameDialog(title = "Rename", initial = item.name, onDismiss = { renameItem = null }) { name ->
            onRename(item.document, name)
            renameItem = null
        }
    }
    deleteItem?.let { item ->
        AlertDialog(
            onDismissRequest = { deleteItem = null },
            title = { Text("Delete ${item.name}?") },
            text = { Text("This permanently deletes the item. There is no recycle bin.") },
            confirmButton = { TextButton(onClick = { onDelete(item.document); deleteItem = null }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleteItem = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun FileRow(
    item: FileItem,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onShare: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            color = if (item.isDirectory) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
            shape = RoundedCornerShape(12.dp), modifier = Modifier.size(48.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(if (item.isDirectory) "▣" else "▤", style = MaterialTheme.typography.headlineMedium,
                    color = if (item.isDirectory) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            Text(
                if (item.isDirectory) "Folder" else "${formatSize(item.size)} • ${formatDate(item.lastModified)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Box {
            TextButton(onClick = { expanded = true }) { Text("⋮", style = MaterialTheme.typography.titleLarge) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(text = { Text("Copy") }, onClick = { expanded = false; onCopy() })
                DropdownMenuItem(text = { Text("Cut / Move") }, onClick = { expanded = false; onCut() })
                DropdownMenuItem(text = { Text("Rename") }, onClick = { expanded = false; onRename() })
                if (!item.isDirectory) DropdownMenuItem(text = { Text("Share") }, onClick = { expanded = false; onShare() })
                HorizontalDivider()
                DropdownMenuItem(text = { Text("Delete") }, onClick = { expanded = false; onDelete() })
            }
        }
    }
}

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember(title, initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") },
                singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done))
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && name.trim() !in listOf(".", "..") && '/' !in name && '\u0000' !in name,
                onClick = { onConfirm(name.trim()) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var n = bytes.toDouble()
    var unit = -1
    do { n /= 1024; unit++ } while (n >= 1024 && unit < units.lastIndex)
    return "%.1f %s".format(n, units[unit])
}

private fun formatDate(time: Long): String = if (time <= 0) "Unknown date" else
    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(time))

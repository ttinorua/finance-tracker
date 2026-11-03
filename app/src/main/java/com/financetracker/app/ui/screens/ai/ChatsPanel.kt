package com.financetracker.app.ui.screens.ai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.financetracker.app.data.ai.chat.SavedChat
import com.financetracker.app.data.ai.chat.SavedChats
import com.financetracker.app.ui.components.GlassCard
import com.financetracker.app.util.Formatters

/** The AI advisor's past chats: open one, start a new one, or rename, pin and delete them. */
@Composable
fun ChatsPanel(
    chats: List<SavedChat>,
    currentId: String?,
    onNewChat: () -> Unit,
    onOpen: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onPin: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var renaming by remember { mutableStateOf<SavedChat?>(null) }
    var deleting by remember { mutableStateOf<SavedChat?>(null) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Button(onClick = onNewChat, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                Text("New chat", modifier = Modifier.padding(start = 8.dp))
            }
        }
        if (chats.isEmpty()) {
            item {
                Text(
                    "No saved chats yet. Chats are kept on this phone for ${SavedChats.KEEP_DAYS} days, or for good when pinned.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
        items(chats, key = { it.id }) { chat ->
            ChatRow(
                chat = chat,
                isOpen = chat.id == currentId,
                onOpen = { onOpen(chat.id) },
                onRename = { renaming = chat },
                onPin = { onPin(chat.id, !chat.pinned) },
                onDelete = { deleting = chat }
            )
        }
        item {
            Text(
                "New chats are named from your first question. Kept on this phone for ${SavedChats.KEEP_DAYS} days " +
                    "after you last used them. Reopening one sends only its latest messages to the AI.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }

    renaming?.let { chat ->
        var text by remember(chat.id) { mutableStateOf(chat.title) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename chat") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, label = { Text("Name") }) },
            confirmButton = {
                TextButton(onClick = {
                    onRename(chat.id, text)
                    renaming = null
                }, enabled = text.isNotBlank()) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } }
        )
    }
    deleting?.let { chat ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete this chat?") },
            text = { Text("\"${chat.title}\" is removed from this phone. Changes you applied from it stay.") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(chat.id)
                    deleting = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun ChatRow(chat: SavedChat, isOpen: Boolean, onOpen: () -> Unit, onRename: () -> Unit, onPin: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    GlassCard(modifier = Modifier.fillMaxWidth(), aiBorder = isOpen) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 4.dp)) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onOpen)
                    .padding(vertical = 12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (chat.pinned) {
                        Icon(Icons.Filled.PushPin, contentDescription = "Pinned", modifier = Modifier.size(14.dp).padding(end = 4.dp))
                    }
                    Text(
                        chat.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        Formatters.shortWhen(chat.updatedAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                val last = chat.messages.lastOrNull { !it.isUser }?.text ?: chat.messages.lastOrNull()?.text.orEmpty()
                Text(
                    last.replace("*", "").lines().firstOrNull { it.isNotBlank() }.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Options for ${chat.title}") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text(if (chat.pinned) "Unpin" else "Pin to top") }, onClick = { menu = false; onPin() })
                    DropdownMenuItem(
                        text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                        onClick = { menu = false; onDelete() }
                    )
                }
            }
        }
    }
}

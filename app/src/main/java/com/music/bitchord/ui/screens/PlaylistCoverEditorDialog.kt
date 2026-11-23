package com.music.bitchord.ui.screens

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.zotifymusic.R
import androidx.compose.ui.res.stringResource

@Composable
fun PlaylistCoverEditorDialog(
    currentArtwork: Any?,
    selectedUri: Uri?,
    onPick: () -> Unit,
    onSave: (Uri) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.choose_playlist_cover)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AsyncImage(
                    model = selectedUri ?: currentArtwork,
                    contentDescription = null,
                    modifier = Modifier.size(220.dp),
                )
                Button(onClick = onPick, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.change_playlist_cover)) }
            }
        },
        confirmButton = {
            Button(onClick = { selectedUri?.let(onSave) }, enabled = selectedUri != null) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRemove) { Text(stringResource(R.string.remove_playlist_cover)) }
                OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

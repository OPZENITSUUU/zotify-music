package com.music.bitchord.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.music.zotifymusic.R
import androidx.compose.ui.res.stringResource

@Composable
fun LibraryManagementScreen(
    albums: List<Pair<String, String>>,
    artists: List<Pair<String, String>>,
    hiddenAlbums: Set<String>,
    hiddenArtists: Set<String>,
    onBack: () -> Unit,
    onUnhideAlbum: (String) -> Unit,
    onApplyArtists: (Set<String>) -> Unit,
    contentPadding: androidx.compose.foundation.layout.PaddingValues,
) {
    var selectedArtists by remember(hiddenArtists) { mutableStateOf(hiddenArtists) }
    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) }
            Text(stringResource(R.string.manage_library), style = MaterialTheme.typography.headlineSmall)
        }
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item { Text(stringResource(R.string.hidden_albums), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp)) }
            items(albums.filter { it.first in hiddenAlbums }, key = { it.first }) { (id, title) ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f))
                    Button(onClick = { onUnhideAlbum(id) }) { Text(stringResource(R.string.unhide)) }
                }
            }
            item { Text(stringResource(R.string.manage_artists), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp)) }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { selectedArtists = artists.mapTo(mutableSetOf()) { it.first } }) { Text(stringResource(R.string.select_all)) }
                    Button(onClick = { selectedArtists = emptySet() }) { Text(stringResource(R.string.deselect_all)) }
                }
            }
            items(artists, key = { it.first }) { (id, title) ->
                Row(Modifier.fillMaxWidth().clickable {
                    selectedArtists = if (id in selectedArtists) selectedArtists - id else selectedArtists + id
                }.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = id in selectedArtists, onCheckedChange = null)
                    Text(title, Modifier.padding(start = 8.dp))
                }
            }
            item {
                Button(onClick = { onApplyArtists(selectedArtists) }, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(stringResource(R.string.save_apply))
                }
            }
        }
    }
}

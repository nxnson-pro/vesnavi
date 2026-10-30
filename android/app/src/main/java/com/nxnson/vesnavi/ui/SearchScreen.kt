package com.nxnson.vesnavi.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nxnson.vesnavi.AppModel
import com.nxnson.vesnavi.L
import com.nxnson.vesnavi.navcore.SavedPlace
import com.nxnson.vesnavi.navcore.SearchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Search on OpenStreetMap (Photon) with saved places shown before typing. */
@Composable
fun SearchScreen(model: AppModel, nav: Navigator) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    // Debounced: Photon is a shared free service.
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) {
            results = emptyList()
            failed = false
            return@LaunchedEffect
        }
        delay(350)
        loading = true
        try {
            results = model.search(q)
            failed = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true
        } finally {
            loading = false
        }
    }

    fun go(place: SavedPlace) {
        nav.popToRoot()
        model.planRoute(place)
    }

    SubScreen(L("Tìm địa điểm", "Search"), onBack = nav::pop) {
        Column(Modifier.fillMaxSize().imePadding()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                placeholder = { Text(L("Địa chỉ, địa điểm…", "Address, place…")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).focusRequester(focus),
            )
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
            LazyColumn(Modifier.fillMaxSize()) {
                if (query.trim().length < 2) {
                    savedPlaces(model, ::go)
                } else {
                    if (failed) item { Hint(L("Không tìm được — kiểm tra kết nối mạng.", "Search failed — check the network connection.")) }
                    if (!loading && !failed && results.isEmpty()) item { Hint(L("Không có kết quả.", "No results.")) }
                    items(results) { r ->
                        PlaceRow(Icons.Filled.Place, r.name, r.subtitle, onClick = {
                            go(SavedPlace(r.name, r.subtitle, r.coordinate.latitude, r.coordinate.longitude))
                        })
                    }
                    item { Hint(L("Tìm kiếm: Photon · dữ liệu © OpenStreetMap contributors", "Search: Photon · data © OpenStreetMap contributors")) }
                }
            }
        }
    }
}

private fun LazyListScope.savedPlaces(model: AppModel, go: (SavedPlace) -> Unit) {
    val p = model.places.places
    if (p.home != null || p.work != null) {
        item { SectionLabel(L("Đi nhanh", "Quick")) }
        p.home?.let { home -> item { PlaceRow(Icons.Filled.Home, home.name, home.subtitle, onClick = { go(home) }) } }
        p.work?.let { work -> item { PlaceRow(Icons.Filled.Work, work.name, work.subtitle, onClick = { go(work) }) } }
    }
    if (p.favorites.isNotEmpty()) {
        item { SectionLabel(L("Yêu thích", "Favourites")) }
        items(p.favorites, key = { "f" + it.id }) { f ->
            PlaceRow(Icons.Filled.Star, f.name, f.subtitle, onClick = { go(f) }) {
                IconButton(onClick = { model.places.removeFavorite(f.id) }) { Icon(Icons.Filled.Delete, L("Xoá", "Delete")) }
            }
        }
    }
    if (p.recents.isNotEmpty()) {
        item { SectionLabel(L("Gần đây", "Recent")) }
        items(p.recents, key = { "r" + it.id }) { r ->
            PlaceRow(Icons.Filled.History, r.name, r.subtitle, onClick = { go(r) }) {
                IconButton(onClick = { model.places.toggleFavorite(r) }) {
                    Icon(if (p.isFavorite(r)) Icons.Filled.Star else Icons.Filled.StarBorder, L("Yêu thích", "Favourite"))
                }
                IconButton(onClick = { model.places.removeRecent(r.id) }) { Icon(Icons.Filled.Delete, L("Xoá", "Delete")) }
            }
        }
    }
}

@Composable
private fun PlaceRow(icon: ImageVector, title: String, subtitle: String?, onClick: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Column {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(icon, null, tint = Theme.accent)
            Column(Modifier.weight(1f)) {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!subtitle.isNullOrEmpty()) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            trailing()
        }
        HorizontalDivider(Modifier.padding(start = 54.dp))
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
}

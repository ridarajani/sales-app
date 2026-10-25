package com.salesapp.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.salesapp.data.roleLabel
import com.salesapp.resources.Res
import com.salesapp.resources.home_greeting
import com.salesapp.resources.home_sign_out
import com.salesapp.resources.home_what_to_do
import com.salesapp.resources.home_your_account
import com.salesapp.resources.search_hint
import com.salesapp.ui.components.Avatar
import com.salesapp.ui.components.Badge
import com.salesapp.ui.components.DangerButton
import com.salesapp.ui.components.ListCard
import com.salesapp.ui.components.SectionLabel
import com.salesapp.ui.components.Tile
import com.salesapp.ui.theme.SalesTheme
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun HomePage(
    onOpen: (route: Any) -> Unit,
    viewModel: HomeViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is HomeEffect.Open -> onOpen(effect.route)
            }
        }
    }
    LifecycleResumeEffect(viewModel) {
        viewModel.onEvent(HomeEvent.Resumed)
        onPauseOrDispose {}
    }
    HomeScreen(state, viewModel::onEvent)
}

// The first screen after signing in: a greeting with the person's picture, then cards for the person's job.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(state: HomeState, onEvent: (HomeEvent) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val accountLabel = stringResource(Res.string.home_your_account)

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(Res.string.home_greeting, stringResource(state.greeting)),
                    color = scheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    state.firstName,
                    color = scheme.onSurface,
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Badge(roleLabel(state.role))
            }
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .semantics { contentDescription = accountLabel }
                    .clickable(role = Role.Button) { onEvent(HomeEvent.ShowAccount) },
                contentAlignment = Alignment.Center,
            ) {
                Avatar(state.name, size = 44.dp)
            }
        }
        // One box over everything: customers, orders, invoices, delivery notes, trips and products.
        ListCard(onClick = { onEvent(HomeEvent.OpenSearch) }, modifier = Modifier.padding(top = 20.dp)) {
            Icon(
                Icons.Outlined.Search,
                contentDescription = null,
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
            Text(
                stringResource(Res.string.search_hint),
                color = scheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 10.dp),
            )
        }
        SectionLabel(stringResource(Res.string.home_what_to_do), modifier = Modifier.padding(top = 20.dp, bottom = 12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            state.tiles.chunked(2).forEachIndexed { rowIndex, pair ->
                // Both tiles in a row are as tall as the taller one.
                Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    pair.forEachIndexed { colIndex, tile ->
                        Tile(
                            label = stringResource(tile.labelRes),
                            sub = tile.subText ?: stringResource(tile.subRes),
                            icon = tile.icon,
                            primary = rowIndex == 0 && colIndex == 0,
                            count = tile.count,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            onClick = { onEvent(HomeEvent.OpenTile(tile)) },
                        )
                    }
                    if (pair.size == 1) Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }

    if (state.sheetOpen) {
        ModalBottomSheet(onDismissRequest = { onEvent(HomeEvent.HideAccount) }, containerColor = scheme.surface) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(state.name, color = scheme.onSurface, style = MaterialTheme.typography.titleLarge)
                Badge(roleLabel(state.role))
                DangerButton(stringResource(Res.string.home_sign_out), onClick = { onEvent(HomeEvent.SignOut) })
            }
        }
    }
}

@Preview
@Composable
private fun HomePreview() {
    SalesTheme {
        HomeScreen(
            HomeState(
                name = "Mary Banda",
                firstName = "Mary",
                role = "admin",
                tiles = tilesFor("admin"),
            ),
            onEvent = {},
        )
    }
}

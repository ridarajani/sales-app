package com.salesapp.ui.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.salesapp.data.Company
import com.salesapp.resources.Res
import com.salesapp.resources.companies_title
import com.salesapp.ui.components.CompanyDot
import com.salesapp.ui.components.ErrorState
import com.salesapp.ui.components.ListCard
import com.salesapp.ui.components.LoadingState
import com.salesapp.ui.components.TopBar
import com.salesapp.ui.theme.SalesTheme
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun CompaniesPage(
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    viewModel: CompaniesViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is CompaniesEffect.Open -> onOpen(effect.code)
            }
        }
    }
    CompaniesScreen(state, viewModel::onEvent, onBack)
}

@Composable
fun CompaniesScreen(state: CompaniesState, onEvent: (CompaniesEvent) -> Unit, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(stringResource(Res.string.companies_title), onBack = onBack)
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                state.loading -> item { LoadingState() }
                state.error != null -> item { ErrorState(state.error, onRetry = { onEvent(CompaniesEvent.Retry) }) }
                else -> items(state.companies, key = { it.code }) { company ->
                    CompanyRow(company, onClick = { onEvent(CompaniesEvent.Open(company.code)) })
                }
            }
        }
    }
}

@Composable
private fun CompanyRow(company: Company, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    ListCard(onClick = onClick) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(company.name, color = scheme.onSurface, style = MaterialTheme.typography.titleSmall)
            Text(facilityLabel(company.facilityCode), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
    }
}

@Preview
@Composable
private fun CompaniesPreview() {
    SalesTheme {
        CompaniesScreen(
            CompaniesState(
                loading = false,
                companies = listOf(Company("LV", "Lucky Ventures", "F1"), Company("ZF", "Zangafee", "F2")),
            ),
            onEvent = {},
            onBack = {},
        )
    }
}

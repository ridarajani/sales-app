package com.salesapp.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable

// iPhone has no system colour scheme for apps, so the brand colours are always used.
@Composable
internal actual fun platformColorScheme(darkTheme: Boolean): ColorScheme? = null

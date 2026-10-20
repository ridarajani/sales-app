package com.salesapp.core

import android.content.Context

// Holds the application context so shared code can reach Android services.
object AppContext {
    lateinit var context: Context
}

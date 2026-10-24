package com.salesapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.salesapp.core.AppContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppContext.context = applicationContext
        // The camera needs an activity to give its answer back to.
        AppContext.activity = this
        enableEdgeToEdge()
        setContent { App() }
    }

    override fun onDestroy() {
        if (AppContext.activity === this) AppContext.activity = null
        super.onDestroy()
    }
}

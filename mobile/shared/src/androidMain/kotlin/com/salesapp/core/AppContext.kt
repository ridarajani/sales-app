package com.salesapp.core

import android.content.Context
import androidx.activity.ComponentActivity

// Holds the application context so shared code can reach Android services, and the activity on
// screen, which the camera needs to get its answer back.
object AppContext {
    lateinit var context: Context

    // Set while MainActivity is alive; null once it is gone.
    var activity: ComponentActivity? = null

    // Set by MainActivity from the app's BuildConfig: the live server address for a release build,
    // the Mac's address for a debug build.
    var apiUrl: String = "http://10.0.2.2:3000"
    var isDebugBuild: Boolean = true
}

package com.example

import android.app.Application
import com.example.service.WalkieSessionRuntime

class WalkieApplication : Application() {
    val walkieRuntime: WalkieSessionRuntime by lazy {
        WalkieSessionRuntime(applicationContext)
    }
}

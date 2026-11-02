package com.gpudash.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels

class MainActivity : ComponentActivity() {
    private val vm: DashVM by viewModels()

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = 0xFF070B14.toInt()
        window.navigationBarColor = 0xFF070B14.toInt()
        vm.refreshRate = windowManager.defaultDisplay.refreshRate
        setContent { Dashboard(vm) }
    }
}

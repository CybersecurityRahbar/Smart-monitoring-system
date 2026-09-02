package com.smarttraffic.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.smarttraffic.app.ui.theme.SmartTrafficTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SmartTrafficTheme {
                SmartTrafficApp()
            }
        }
    }
}

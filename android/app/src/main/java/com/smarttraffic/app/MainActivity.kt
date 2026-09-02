package com.smarttraffic.app

import android.app.PictureInPictureParams
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.LayoutDirection
import com.smarttraffic.app.core.AppSettings
import com.smarttraffic.app.ui.theme.SmartTrafficTheme
import java.util.Locale
import androidx.compose.runtime.compositionLocalOf

val LocalLayoutDirectionOverride = compositionLocalOf { LayoutDirection.Ltr }

class MainActivity : ComponentActivity() {
    private var pipArmed by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppSettings.load(applicationContext)
        setContent {
            val direction = if (AppSettings.language == com.smarttraffic.app.core.AppLanguage.ARABIC) {
                LayoutDirection.Rtl
            } else {
                LayoutDirection.Ltr
            }
            CompositionLocalProvider(LocalLayoutDirectionOverride provides direction) {
                SmartTrafficTheme(darkTheme = AppSettings.darkMode) {
                    SmartTrafficApp()
                }
            }
        }
    }

    fun enterVideoPictureInPicture() {
        pipArmed = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
            enterPictureInPictureMode(params)
        }
    }

    override fun onUserLeaveHint() {
        if (pipArmed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            enterVideoPictureInPicture()
        }
        super.onUserLeaveHint()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (!isInPictureInPictureMode) pipArmed = false
    }
}

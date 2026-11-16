package dev.kodakiko.app

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dev.kodakiko.app.system.EdgeToEdge
import dev.kodakiko.app.system.SystemUi
import dev.kodakiko.app.system.SystemUiSettings
import dev.kodakiko.platform.device.ScreenSecurity
import dev.kodakiko.ui.core.AppLocale
import dev.kodakiko.ui.core.theme.KodakikoTheme

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        EdgeToEdge.apply(this, darkTheme = isNightMode())
        ScreenSecurity.apply(window, SystemUiSettings.STARTUP.secureScreen)
        super.onCreate(savedInstanceState)
        setContent {
            KodakikoTheme {
                SystemUi(settings = SystemUiSettings.STARTUP, darkTheme = isSystemInDarkTheme()) {
                    Box(Modifier.testTag(TestTags.APP_ROOT).fillMaxSize())
                }
            }
        }
    }

    private fun isNightMode(): Boolean =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
}

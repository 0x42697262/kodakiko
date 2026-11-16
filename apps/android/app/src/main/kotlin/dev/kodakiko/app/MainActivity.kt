package dev.kodakiko.app

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kodakiko.app.di.AppGraph
import dev.kodakiko.app.navigation.AppNavigation
import dev.kodakiko.app.navigation.AppScreens
import dev.kodakiko.app.navigation.HomeSettings
import dev.kodakiko.app.system.EdgeToEdge
import dev.kodakiko.app.system.SystemUi
import dev.kodakiko.app.system.SystemUiSettings
import dev.kodakiko.data.db.DeviceSettingsDefaults
import dev.kodakiko.platform.device.ScreenSecurity
import dev.kodakiko.ui.core.AppLocale
import dev.kodakiko.ui.core.screens.LoadingScreen
import dev.kodakiko.ui.core.testTagsAsResourceIdRoot
import dev.kodakiko.ui.core.theme.KodakikoTheme
import dev.kodakiko.ui.feature.calendar.LocalCalendarThumbnails
import dev.kodakiko.ui.feature.gallery.LocalGalleryThumbnails
import dev.kodakiko.ui.feature.viewer.LocalViewerImages
import dev.zacsweers.metrox.viewmodel.LocalMetroViewModelFactory
import dev.kodakiko.ui.core.TestTags as CoreTestTags

class MainActivity : FragmentActivity() {
    private val graph: AppGraph get() = (application as KodakikoApp).graph

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        EdgeToEdge.apply(this, darkTheme = isNightMode())
        ScreenSecurity.apply(window, SystemUiSettings.STARTUP.secureScreen)
        super.onCreate(savedInstanceState)
        graph.activityHolder.attach(this)
        graph.startup.start()
        setContent {
            KodakikoTheme {
                SystemUi(settings = SystemUiSettings.STARTUP, darkTheme = isSystemInDarkTheme()) {
                    Box(Modifier.testTag(TestTags.APP_ROOT).testTagsAsResourceIdRoot().fillMaxSize()) {
                        CompositionLocalProvider(
                            LocalMetroViewModelFactory provides graph.metroViewModelFactory,
                            LocalGalleryThumbnails provides graph.galleryThumbnails,
                            LocalCalendarThumbnails provides graph.calendarThumbnails,
                            LocalViewerImages provides graph.viewerImages,
                        ) {
                            AppContent(graph)
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        graph.activityHolder.detach(this)
        super.onDestroy()
    }

    private fun isNightMode(): Boolean =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
}

@Composable
private fun AppContent(graph: AppGraph) {
    val ready by graph.startup.ready.collectAsStateWithLifecycle()
    if (ready) {
        AppNavigation(navigator = graph.navigator, homeSettings = HOME_SETTINGS) { route -> AppScreens.Content(route) }
    } else {
        LoadingScreen(modifier = Modifier.testTag(CoreTestTags.App.LOADING_SCREEN), indicatorTestTag = CoreTestTags.App.LOADING)
    }
}

private val HOME_SETTINGS = HomeSettings.of(DeviceSettingsDefaults.ANDROID, sharingAvailable = true)

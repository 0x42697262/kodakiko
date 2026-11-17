package dev.kodakiko.ui.feature.capture

import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

@Stable
class CameraXSurfaceState {
    internal val request: MutableState<SurfaceRequest?> = mutableStateOf(null)

    val surfaceProvider: Preview.SurfaceProvider = Preview.SurfaceProvider { request.value = it }
}

@Composable
fun rememberCameraXSurfaceState(): CameraXSurfaceState = remember { CameraXSurfaceState() }

@Composable
fun CameraXSurface(state: CameraXSurfaceState, modifier: Modifier = Modifier) {
    val request = state.request.value ?: return
    CameraXViewfinder(surfaceRequest = request, modifier = modifier)
}

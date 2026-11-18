package dev.kodakiko.ui.feature.auth

import androidx.activity.compose.BackHandler
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.icons.KodakikoIcons
import dev.kodakiko.ui.core.screens.FormScreen

@Composable
fun SecuringScreen() {
    BackHandler {}
    SecuringContent()
}

@Composable
fun SecuringContent(modifier: Modifier = Modifier) {
    FormScreen(
        modifier = modifier.testTag(TestTags.SignUp.SECURING_SCREEN),
        icon = KodakikoIcons.Lock,
        headingText = stringResource(R.string.sign_up_securing),
        headingTestTag = TestTags.SignUp.SECURING,
        subtitleText = stringResource(R.string.sign_up_securing_detail),
        subtitleTestTag = TestTags.SignUp.SECURING_DETAIL,
    ) {
        LinearProgressIndicator()
    }
}

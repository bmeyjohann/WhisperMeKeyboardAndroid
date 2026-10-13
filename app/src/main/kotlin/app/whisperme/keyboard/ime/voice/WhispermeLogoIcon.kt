package app.whisperme.keyboard.ime.voice

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import app.whisperme.keyboard.R
import app.whisperme.keyboard.ime.theme.FlorisImeTheme

@Composable
fun WhispermeLogoIcon(
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val painter = painterResource(
        R.drawable.logo_blue,
        // if (FlorisImeTheme.config.isNightTheme) {
        //     R.drawable.logo_white
        // } else {
        //     R.drawable.logo_blue
        // },
    )

    Image(
        painter = painter,
        contentDescription = contentDescription,
        modifier = modifier,
    )
}

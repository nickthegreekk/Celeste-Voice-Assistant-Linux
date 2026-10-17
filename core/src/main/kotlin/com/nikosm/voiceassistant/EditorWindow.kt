package com.nikosm.voiceassistant

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState

/**
 * M17: an editor in a window of its own.
 *
 * The persona, room and skill editors used to replace the Settings page they were opened from —
 * the drilled mobile pattern. On the desktop they are real dialog windows with a title bar and a
 * close button, sitting over the Settings window, which stays on its list. The content is given
 * the scrolling parent these editors are designed to expect (their KDoc says they are plain
 * Columns); the window itself owns no navigation.
 *
 * [onClose] is called by both the window's close button and whatever Done/Cancel control the
 * editor renders, so there is one exit path rather than two that can drift.
 */
@Composable
fun EditorWindow(
    title: String,
    onClose: () -> Unit,
    width: Dp = 760.dp,
    height: Dp = 800.dp,
    content: @Composable () -> Unit,
) {
    DialogWindow(
        onCloseRequest = onClose,
        title = title,
        state = rememberDialogState(size = DpSize(width, height)),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                content()
            }
        }
    }
}

package com.anatdx.yukisu.ui.component

import androidx.compose.runtime.Composable
import com.anatdx.yukisu.Natives
import com.anatdx.yukisu.ksuApp

@Composable
fun KsuIsValid(
    content: @Composable () -> Unit
) {
    val isManager = Natives.isManager
    val ksuVersion = if (isManager) Natives.version else null

    if (ksuVersion != null) {
        content()
    }
}
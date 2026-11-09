package es.openrtve.ui.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import es.openrtve.OpenRtveApplication
import es.openrtve.ui.theme.OpenRtveTvTheme

class TvActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        val container = (application as OpenRtveApplication).container
        setContent {
            OpenRtveTvTheme {
                TvApp(container)
            }
        }
    }
}

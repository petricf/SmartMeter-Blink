package de.smartmeter.blink

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import de.smartmeter.blink.ui.BlinkApp
import de.smartmeter.blink.ui.theme.SmartMeterBlinkTheme

class MainActivity : ComponentActivity()
{

    private val viewModel: BlinkViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?)
    {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            SmartMeterBlinkTheme {
                BlinkApp(viewModel)
            }
        }
    }
}
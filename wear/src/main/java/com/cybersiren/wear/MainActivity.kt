package com.cybersiren.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material.MaterialTheme
import com.cybersiren.wear.firebase.FirebaseEmergencyReceiver
import com.cybersiren.wear.ui.V2VWearScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FirebaseEmergencyReceiver.start(applicationContext)
        setContent {
            MaterialTheme {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF101418))
                ) {
                    V2VWearScreen()
                }
            }
        }
    }
}

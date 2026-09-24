package com.tripshare.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.tripshare.app.ui.TripShareApp
import com.tripshare.app.ui.theme.TripShareTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TripShareTheme {
                TripShareApp((application as TripShareApplication).trips)
            }
        }
    }
}

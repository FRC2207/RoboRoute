package com.team2207.roboroute

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.team2207.roboroute.aoa.AoaPoseReceiver
import com.team2207.roboroute.datastore.ActionRepository
import com.team2207.roboroute.navigation.AppNavigation
import com.team2207.roboroute.ui.theme.RoboRouteTheme

class MainActivity : ComponentActivity() {
    private lateinit var aoaReceiver: AoaPoseReceiver

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val repository = ActionRepository(this)
        aoaReceiver = AoaPoseReceiver(this, repository)
        aoaReceiver.start()

        // Handle intent if app was started by accessory attachment
        aoaReceiver.handleIntent(intent)

        setContent {
            RoboRouteTheme {
                AppNavigation()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        aoaReceiver.handleIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        aoaReceiver.stop()
    }
}

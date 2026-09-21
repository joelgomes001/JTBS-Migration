package com.example.jtbs.ui.splash

import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import com.example.jtbs.R
import kotlinx.coroutines.delay

@Composable
fun SplashScreen(
  onNavigateToWeb: () -> Unit,
  modifier: Modifier = Modifier
) {
  val context = LocalContext.current
  
  // Set orientation to Portrait for Splash Screen
  LaunchedEffect(Unit) {
    val activity = context as? ComponentActivity
    activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    
    // Splash screen loads for 2.8 seconds
    delay(2800)
    onNavigateToWeb()
  }

  Box(
    modifier = modifier
      .fillMaxSize()
      .background(Color.White)
  ) {
    Image(
      painter = painterResource(id = R.drawable.splash),
      contentDescription = "JTBS Splash Screen",
      contentScale = ContentScale.Fit,
      modifier = Modifier
        .fillMaxSize()
        .align(Alignment.Center)
    )
  }
}



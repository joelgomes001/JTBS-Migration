package com.example.jtbs

import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout
import com.google.android.exoplayer2.ui.PlayerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private var exoPlayer: ExoPlayer? = null
    private var playerView: PlayerView? = null

    private var splashLayout: FrameLayout? = null
    private var playerOverlay: FrameLayout? = null
    private var dateTimeText: TextView? = null
    private var bufferSpinner: ProgressBar? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isSplashActive = true

    private val streamUrl = "https://jtbsclassic.dpdns.org/live.m3u8"
    private val dateFormat = SimpleDateFormat("EEE, dd MMM yyyy  |  hh:mm:ss a", Locale.ENGLISH)

    private val timeUpdateRunnable = object : Runnable {
        override fun run() {
            dateTimeText?.text = dateFormat.format(Date())
            mainHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Initial Launch: Force Portrait Mode for Splash Screen
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        // Fullscreen setup (no title bar, immersive sticky, keep screen awake)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        applyImmersiveFullscreen()

        // Root Container
        val root = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
        }

        // 2. Build Player View (Bottom Layer)
        val pView = PlayerView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setBackgroundColor(Color.BLACK)
        }
        playerView = pView
        root.addView(pView)

        // 3. Build Video Overlay (Bottom-left Date & Time only, NO play/pause button)
        val overlay = buildPlayerOverlay()
        playerOverlay = overlay
        overlay.visibility = View.GONE
        root.addView(overlay)

        // 4. Build Exact Image-Matching Splash Screen Overlay (Top Layer)
        val splash = buildExactMatchingSplash()
        splashLayout = splash
        root.addView(splash)

        setContentView(root)

        // 5. Automatic transition: stay on splash for 2.8s, then cut to live player automatically
        mainHandler.postDelayed({
            cutToLiveStream()
        }, 2800)
    }

    private fun applyImmersiveFullscreen() {
        if (Build.VERSION.SDK_INT >= 19) {
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        }
    }

    // ── EXACT MATCHING WHITE SPLASH SCREEN LAYOUT ───────────────────────────

    private fun buildExactMatchingSplash(): FrameLayout {
        val container = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.WHITE)
        }

        val splashResId = resources.getIdentifier("splash", "drawable", packageName)
        if (splashResId != 0) {
            val splashImg = ImageView(this).apply {
                setImageResource(splashResId)
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            container.addView(splashImg)
        } else {
            val density = resources.displayMetrics.density

        val mainLayout = LinearLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(
                (20 * density).toInt(),
                (40 * density).toInt(),
                (20 * density).toInt(),
                (36 * density).toInt()
            )
        }

        // --- TOP SECTION ---
        val topSection = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                0,
                1f
            )
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }

        // 1. Circular Emblem Logo
        val logoImg = ImageView(this).apply {
            val logoResId = resources.getIdentifier("logo", "drawable", packageName)
            if (logoResId != 0) {
                setImageResource(logoResId)
            } else {
                setImageResource(android.R.drawable.ic_menu_camera)
            }
            val logoSize = (145 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(logoSize, logoSize).apply {
                bottomMargin = (8 * density).toInt()
            }
        }
        topSection.addView(logoImg)

        // 2. Gold CLASSIC text under logo
        val classicText = TextView(this).apply {
            text = "CLASSIC"
            setTextColor(Color.parseColor("#B8860B"))
            textSize = 24f
            typeface = Typeface.SERIF
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.12f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (18 * density).toInt()
            }
        }
        topSection.addView(classicText)

        // 3. Bold Blue Title: JTBS CLASSIC
        val titleText = TextView(this).apply {
            text = "JTBS CLASSIC"
            setTextColor(Color.parseColor("#0544D3"))
            textSize = 30f
            typeface = Typeface.SERIF
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (20 * density).toInt()
            }
        }
        topSection.addView(titleText)

        // 4. "A Channel Of"
        val subtitle1 = TextView(this).apply {
            text = "A Channel Of"
            setTextColor(Color.parseColor("#222222"))
            textSize = 15f
            typeface = Typeface.SERIF
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (16 * density).toInt()
            }
        }
        topSection.addView(subtitle1)

        // 5. "Jishu Television's Broadcasting Services"
        val subtitle2 = TextView(this).apply {
            text = "Jishu Television’s Broadcasting Services"
            setTextColor(Color.parseColor("#111111"))
            textSize = 15f
            typeface = Typeface.SERIF
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (4 * density).toInt()
            }
        }
        topSection.addView(subtitle2)

        // 6. "Krishnanagar"
        val subtitle3 = TextView(this).apply {
            text = "Krishnanagar"
            setTextColor(Color.parseColor("#111111"))
            textSize = 15f
            typeface = Typeface.SERIF
            gravity = Gravity.CENTER
        }
        topSection.addView(subtitle3)

        mainLayout.addView(topSection)

        // --- BOTTOM SECTION ---
        val bottomSection = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        // Red Proprietor Name
        val propText = TextView(this).apply {
            text = "Proprietor Name :- Joel Sohan Gomes"
            setTextColor(Color.parseColor("#CC0000"))
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (16 * density).toInt()
            }
        }
        bottomSection.addView(propText)

        // Phone Number
        val phoneText = TextView(this).apply {
            text = "Phone :- +91 83738 28015"
            setTextColor(Color.BLACK)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (4 * density).toInt()
            }
        }
        bottomSection.addView(phoneText)

        // Email
        val emailText = TextView(this).apply {
            text = "Email:- helpdesk.jtbs@gmail.com"
            setTextColor(Color.BLACK)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        bottomSection.addView(emailText)

        mainLayout.addView(bottomSection)

        container.addView(mainLayout)
        }

        container.setOnClickListener {
            if (isSplashActive) {
                mainHandler.removeCallbacksAndMessages(null)
                cutToLiveStream()
            }
        }

        return container
    }

    // ── CUT TO LIVE STREAM (LANDSCAPE) ───────────────────────────────────────

    private fun cutToLiveStream() {
        if (!isSplashActive) return
        isSplashActive = false

        // Force rotate to Landscape Mode
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        // Hide splash screen, reveal overlay
        splashLayout?.visibility = View.GONE
        playerOverlay?.visibility = View.VISIBLE

        // Start live time update ticks
        mainHandler.post(timeUpdateRunnable)

        // Start video player automatically
        initExoPlayer()
    }

    private fun initExoPlayer() {
        if (exoPlayer != null) return

        val player = ExoPlayer.Builder(this).build().apply {
            val mediaItem = MediaItem.fromUri(streamUrl)
            setMediaItem(mediaItem)
            prepare()
            playWhenReady = true

            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    when (state) {
                        Player.STATE_BUFFERING -> {
                            bufferSpinner?.visibility = View.VISIBLE
                        }
                        Player.STATE_READY -> {
                            bufferSpinner?.visibility = View.GONE
                        }
                        Player.STATE_ENDED -> {
                            prepare()
                            play()
                        }
                        Player.STATE_IDLE -> {}
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    bufferSpinner?.visibility = View.VISIBLE
                    mainHandler.postDelayed({
                        exoPlayer?.prepare()
                        exoPlayer?.play()
                    }, 2500)
                }
            })
        }

        exoPlayer = player
        playerView?.player = player
    }

    // ── PLAYER OVERLAY: BOTTOM-LEFT DATE & TIME ONLY (NO PLAY/PAUSE BUTTON) ──

    private fun buildPlayerOverlay(): FrameLayout {
        val density = resources.displayMetrics.density

        val container = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.TRANSPARENT)
        }

        // Buffer Spinner (Only visible if stream is buffering)
        val spinner = ProgressBar(this).apply {
            val sSize = (48 * density).toInt()
            layoutParams = FrameLayout.LayoutParams(sSize, sSize).apply {
                gravity = Gravity.CENTER
            }
            visibility = View.VISIBLE
        }
        bufferSpinner = spinner
        container.addView(spinner)

        // Down Left Side: Date & Time Display with NO Solid Background and Bold Fonts
        val dtText = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                setMargins(
                    (20 * density).toInt(),
                    0,
                    0,
                    (16 * density).toInt()
                )
            }
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(4f, 2f, 2f, Color.BLACK) // Clear readable shadow on transparent bg
            text = dateFormat.format(Date())
        }
        dateTimeText = dtText
        container.addView(dtText)

        return container
    }

    // ── LIFECYCLE ────────────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        applyImmersiveFullscreen()
        if (!isSplashActive && exoPlayer != null) {
            exoPlayer?.playWhenReady = true
        }
    }

    override fun onPause() {
        super.onPause()
        exoPlayer?.playWhenReady = false
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        exoPlayer?.release()
        exoPlayer = null
        super.onDestroy()
    }
}

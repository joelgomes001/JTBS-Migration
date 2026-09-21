package com.jtbs.box.player

import android.content.Context
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.DefaultRenderersFactory
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter

object PlayerFactory {
    fun create(context: Context): ExoPlayer {
        val renderersFactory = DefaultRenderersFactory(context).apply {
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        }
        val trackSelector = DefaultTrackSelector(context)
        val bandwidthMeter = DefaultBandwidthMeter.Builder(context).build()
        val loadControl = CustomLoadControl()
        return ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setTrackSelector(trackSelector)
            .setBandwidthMeter(bandwidthMeter)
            .setLoadControl(loadControl)
            .build()
    }
}


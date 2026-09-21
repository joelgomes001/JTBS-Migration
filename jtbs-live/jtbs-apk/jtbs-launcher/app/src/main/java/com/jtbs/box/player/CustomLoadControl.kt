package com.jtbs.box.player

import com.google.android.exoplayer2.DefaultLoadControl
import com.google.android.exoplayer2.LoadControl
import com.google.android.exoplayer2.upstream.Allocator

class CustomLoadControl : LoadControl {
    private val delegate: DefaultLoadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            5000, // minBufferMs (5s)
            15000, // maxBufferMs (15s)
            500,   // bufferForPlaybackMs (0.5s - instant startup!)
            1000   // bufferForPlaybackAfterRebufferMs (1s)
        )
        .build()

    override fun onPrepared() {
        delegate.onPrepared()
    }

    override fun onStopped() {
        delegate.onStopped()
    }

    override fun onReleased() {
        delegate.onReleased()
    }

    override fun getAllocator(): Allocator {
        return delegate.allocator
    }

    override fun getBackBufferDurationUs(): Long {
        return delegate.backBufferDurationUs
    }

    override fun retainBackBufferFromKeyframe(): Boolean {
        return delegate.retainBackBufferFromKeyframe()
    }

    override fun shouldContinueLoading(
        playbackPositionUs: Long,
        bufferedDurationUs: Long,
        playbackSpeed: Float
    ): Boolean {
        return delegate.shouldContinueLoading(playbackPositionUs, bufferedDurationUs, playbackSpeed)
    }

    override fun shouldStartPlayback(
        bufferedDurationUs: Long,
        playbackSpeed: Float,
        rebuffering: Boolean,
        targetLiveOffsetUs: Long
    ): Boolean {
        return delegate.shouldStartPlayback(bufferedDurationUs, playbackSpeed, rebuffering, targetLiveOffsetUs)
    }

    override fun onTracksSelected(
        renderers: Array<out com.google.android.exoplayer2.Renderer>,
        trackGroups: com.google.android.exoplayer2.source.TrackGroupArray,
        trackSelections: Array<out com.google.android.exoplayer2.trackselection.ExoTrackSelection>
    ) {
        delegate.onTracksSelected(renderers, trackGroups, trackSelections)
    }
}

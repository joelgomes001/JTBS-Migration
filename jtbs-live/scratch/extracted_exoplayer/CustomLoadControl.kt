package com.jtbs.box.player

import com.google.android.exoplayer2.DefaultLoadControl
import com.google.android.exoplayer2.LoadControl
import com.google.android.exoplayer2.upstream.Allocator

class CustomLoadControl : LoadControl {
    private val delegate: DefaultLoadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            15000, // minBufferMs (15s)
            30000, // maxBufferMs (30s)
            6000,  // bufferForPlaybackMs (6s)
            8000   // bufferForPlaybackAfterRebufferMs (8s)
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
}


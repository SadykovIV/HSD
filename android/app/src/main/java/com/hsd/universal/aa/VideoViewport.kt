package com.hsd.universal.aa

/** Destination of the desktop texture in a codec frame, in top-left video pixels. */
data class VideoViewport(val left: Int, val top: Int, val width: Int, val height: Int) {
    fun fits(frameWidth: Int, frameHeight: Int) = left >= 0 && top >= 0 && width > 0 && height > 0 &&
        left.toLong() + width <= frameWidth && top.toLong() + height <= frameHeight
}

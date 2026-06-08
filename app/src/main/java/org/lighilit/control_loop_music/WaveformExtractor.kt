//package com.example.controlloopmusic
package org.lighilit.control_loop_music

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object WaveformExtractor {
    private const val TARGET_SAMPLES = 2000

    fun extract(context: Context, uri: Uri, callback: (FloatArray) -> Unit) {
        Thread {
            val peaks = try {
                FloatArray(TARGET_SAMPLES).also { decodeIntoPeaks(context, uri, it) }.also(::normalize)
            } catch (_: Exception) {
                FloatArray(0)
            }
            Handler(context.mainLooper).post { callback(peaks) }
        }.apply { name = "waveform-extractor" }.start()
    }

    private fun decodeIntoPeaks(context: Context, uri: Uri, peaks: FloatArray) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = findAudioTrack(extractor)
            if (trackIndex < 0) return
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION)
            } else {
                0L
            }
            val sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else {
                44_100
            }
            val channelCount = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else {
                1
            }
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            runDecodeLoop(extractor, codec, peaks, durationUs, sampleRate, channelCount)
        } finally {
            codec?.stop()
            codec?.release()
            extractor.release()
        }
    }

    private fun findAudioTrack(extractor: MediaExtractor): Int {
        for (index in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
            if (mime?.startsWith("audio/") == true) return index
        }
        return -1
    }

    private fun runDecodeLoop(
        extractor: MediaExtractor,
        codec: MediaCodec,
        peaks: FloatArray,
        durationUs: Long,
        sampleRate: Int,
        channelCount: Int,
    ) {
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        while (!outputDone) {
            if (!inputDone) {
                val inputIndex = codec.dequeueInputBuffer(10_000)
                if (inputIndex >= 0) {
                    val input = codec.getInputBuffer(inputIndex) ?: continue
                    val size = extractor.readSampleData(input, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            val outputIndex = codec.dequeueOutputBuffer(info, 10_000)
            if (outputIndex >= 0) {
                val output = codec.getOutputBuffer(outputIndex)
                if (output != null && info.size > 0 && durationUs > 0) {
                    collectPeaks(output, info, peaks, durationUs, sampleRate, channelCount)
                }
                outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                codec.releaseOutputBuffer(outputIndex, false)
            }
        }
    }

    private fun collectPeaks(
        output: ByteBuffer,
        info: MediaCodec.BufferInfo,
        peaks: FloatArray,
        durationUs: Long,
        sampleRate: Int,
        channelCount: Int,
    ) {
        output.position(info.offset)
        output.limit(info.offset + info.size)
        val data = output.slice().order(ByteOrder.LITTLE_ENDIAN)
        val sampleCount = data.remaining() / 2
        val safeChannels = max(1, channelCount)
        val safeSampleRate = max(1, sampleRate)
        for (index in 0 until sampleCount) {
            val value = abs(data.short / 32768f)
            val frameIndex = index / safeChannels
            val sampleUs = info.presentationTimeUs + frameIndex * 1_000_000L / safeSampleRate
            val bucket = (sampleUs * peaks.size / durationUs).toInt().coerceIn(0, peaks.lastIndex)
            peaks[bucket] = max(peaks[bucket], value)
        }
    }

    private fun normalize(peaks: FloatArray) {
        val maxPeak = peaks.maxOrNull() ?: 0f
        if (maxPeak <= 0f) return
        for (index in peaks.indices) {
            peaks[index] = min(1f, peaks[index] / maxPeak)
        }
    }
}

package com.example.personalrecorder.net

import org.json.JSONObject

/**
 * Tiny in-process pub/sub so services can register handlers for remote
 * commands arriving over the WebSocket channel.
 */
object CommandBus {

    const val CMD_CAPTURE = "capture"
    const val CMD_AUDIO_START = "audio_start"
    const val CMD_AUDIO_STOP = "audio_stop"
    const val CMD_LOC_START = "location_start"
    const val CMD_LOC_STOP = "location_stop"
    const val CMD_REC_START = "record_start"
    const val CMD_REC_STOP = "record_stop"
    const val CMD_DEV_AUDIO_START = "deviceaudio_start"
    const val CMD_DEV_AUDIO_STOP = "deviceaudio_stop"
    const val CMD_CALL_VIDEO_START = "call_video_start"
    const val CMD_CALL_VIDEO_STOP = "call_video_stop"
    const val CMD_CALL_AUDIO_START = "call_audio_start"
    const val CMD_CALL_AUDIO_STOP = "call_audio_stop"
    const val CMD_AUDIO_LIVE_START = "audio_live_start"
    const val CMD_AUDIO_LIVE_STOP = "audio_live_stop"

    private val handlers = mutableMapOf<String, (JSONObject) -> Unit>()

    @Synchronized
    fun register(command: String, handler: (JSONObject) -> Unit) {
        handlers[command] = handler
    }

    @Synchronized
    fun unregister(command: String) {
        handlers.remove(command)
    }

    @Synchronized
    fun dispatch(command: String, payload: JSONObject) {
        handlers[command]?.invoke(payload)
    }
}
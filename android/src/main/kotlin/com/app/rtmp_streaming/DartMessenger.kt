package com.app.rtmp_streaming

import android.text.TextUtils
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.EventChannel.EventSink
import java.util.*


class DartMessenger(messenger: BinaryMessenger, eventChannelId: Int) {
    private var eventSink: EventSink? = null

    enum class EventType {
        ERROR, CAMERA_CLOSING, RTMP_STOPPED, RTMP_RETRY, SUCCESS, WAIT,

        /**
         * The plugin took the connection down itself and means to bring it back.
         *
         * Added because the alternative was saying nothing. When the preview
         * surface goes away the plugin stops the stream and used to suppress the
         * resulting disconnect entirely, so Dart went on reporting a live stream
         * -- and polling statistics that agreed with it -- while the far end had
         * seen the input stop. An app must not assert a state it has no evidence
         * for, so the interruption is now reported as what it is.
         */
        RTMP_INTERRUPTED
    }

    fun sendCameraClosingEvent() {
        send(EventType.CAMERA_CLOSING, "close connection")
    }

    fun send(eventType: EventType, description: String?) {
        if (eventSink == null) {
            return
        }
        val event: MutableMap<String, String?> = HashMap()
        event["eventType"] = eventType.toString().lowercase()
        // Only errors have a description.
        if (!TextUtils.isEmpty(description)) {
            event["errorDescription"] = description
        }
        eventSink!!.success(event)
    }

    init {
        assert(messenger != null);
        EventChannel(messenger, "com.rtmp_streaming.eventchannel/$eventChannelId")
            .setStreamHandler(
                object : EventChannel.StreamHandler {
                    override fun onListen(arguments: Any?, sink: EventSink) {
                        eventSink = sink
                    }

                    override fun onCancel(arguments: Any?) {
                        eventSink = null
                    }
                })
    }
}
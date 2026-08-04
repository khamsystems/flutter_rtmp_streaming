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
        RTMP_INTERRUPTED,

        /**
         * The camera has stopped delivering frames while a session is running.
         *
         * This is not the same as anything the encoder can report, and that is
         * the whole point of it. Measured 2026-08-04: a camera stalled for 135
         * seconds while the connection stayed up and the encoder went on
         * reporting 30fps with no dropped frames, because a GL surface holding a
         * still image keeps the encoder perfectly busy. Nothing in the app could
         * tell. This event follows the camera hardware instead.
         */
        CAMERA_STALLED,

        /** Frames started arriving again, after [CAMERA_STALLED]. */
        CAMERA_RECOVERED,

        /** The stall outlasted the recovery attempts allowed for one session. */
        CAMERA_STALL_UNRECOVERED
    }

    fun sendCameraClosingEvent() {
        send(EventType.CAMERA_CLOSING, "close connection")
    }

    fun send(eventType: EventType, description: String?) {
        send(eventType, description, null)
    }

    /**
     * Sends an event, optionally with structured values alongside the
     * description.
     *
     * [extra] exists because the stall events carry numbers -- how long the
     * picture was frozen, which recovery attempt this is -- and those are the
     * data the trigger threshold gets tuned from. Packing them into the
     * description string would mean parsing prose on the other side.
     */
    fun send(eventType: EventType, description: String?, extra: Map<String, Any?>?) {
        if (eventSink == null) {
            return
        }
        val event: MutableMap<String, Any?> = HashMap()
        event["eventType"] = eventType.toString().lowercase()
        // Only errors have a description.
        if (!TextUtils.isEmpty(description)) {
            event["errorDescription"] = description
        }
        extra?.forEach { (key, value) -> event[key] = value }
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
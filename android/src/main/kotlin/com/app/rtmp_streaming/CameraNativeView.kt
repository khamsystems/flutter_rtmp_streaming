package com.app.rtmp_streaming

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.hardware.camera2.CameraAccessException
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.pedro.encoder.input.gl.render.filters.BaseFilterRender
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.SurfaceHolder
import android.view.View
import androidx.annotation.RequiresApi
import com.app.rtmp_streaming.CameraPermissions.ResolutionPreset
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.gl.SpriteGestureController
import com.pedro.encoder.input.gl.render.filters.BasicDeformationFilterRender
import com.pedro.encoder.input.gl.render.filters.BeautyFilterRender
import com.pedro.encoder.input.gl.render.filters.BlackFilterRender
import com.pedro.encoder.input.gl.render.filters.BlurFilterRender
import com.pedro.encoder.input.gl.render.filters.BrightnessFilterRender
import com.pedro.encoder.input.gl.render.filters.CartoonFilterRender
import com.pedro.encoder.input.gl.render.filters.ChromaFilterRender
import com.pedro.encoder.input.gl.render.filters.ChromaticAberrationFilterRender
import com.pedro.encoder.input.gl.render.filters.CircleFilterRender
import com.pedro.encoder.input.gl.render.filters.ColorFilterRender
import com.pedro.encoder.input.gl.render.filters.ContrastFilterRender
import com.pedro.encoder.input.gl.render.filters.CropFilterRender
import com.pedro.encoder.input.gl.render.filters.DistortedTvFilterRender
import com.pedro.encoder.input.gl.render.filters.DuotoneFilterRender
import com.pedro.encoder.input.gl.render.filters.EarlyBirdFilterRender
import com.pedro.encoder.input.gl.render.filters.EdgeDetectionFilterRender
import com.pedro.encoder.input.gl.render.filters.ExposureFilterRender
import com.pedro.encoder.input.gl.render.filters.FireFilterRender
import com.pedro.encoder.input.gl.render.filters.GammaFilterRender
import com.pedro.encoder.input.gl.render.filters.GlitchFilterRender
import com.pedro.encoder.input.gl.render.filters.GreyScaleFilterRender
import com.pedro.encoder.input.gl.render.filters.HalftoneLinesFilterRender
import com.pedro.encoder.input.gl.render.filters.Image70sFilterRender
import com.pedro.encoder.input.gl.render.filters.LamoishFilterRender
import com.pedro.encoder.input.gl.render.filters.MoneyFilterRender
import com.pedro.encoder.input.gl.render.filters.NegativeFilterRender
import com.pedro.encoder.input.gl.render.filters.NoiseFilterRender
import com.pedro.encoder.input.gl.render.filters.PixelatedFilterRender
import com.pedro.encoder.input.gl.render.filters.PolygonizationFilterRender
import com.pedro.encoder.input.gl.render.filters.RGBSaturationFilterRender
import com.pedro.encoder.input.gl.render.filters.RainbowFilterRender
import com.pedro.encoder.input.gl.render.filters.RippleFilterRender
import com.pedro.encoder.input.gl.render.filters.RotationFilterRender
import com.pedro.encoder.input.gl.render.filters.SaturationFilterRender
import com.pedro.encoder.input.gl.render.filters.SepiaFilterRender
import com.pedro.encoder.input.gl.render.filters.SharpnessFilterRender
import com.pedro.encoder.input.gl.render.filters.SnowFilterRender
import com.pedro.encoder.input.gl.render.filters.TemperatureFilterRender
import com.pedro.encoder.input.gl.render.filters.ZebraFilterRender
import com.pedro.encoder.input.gl.render.filters.`object`.GifObjectFilterRender
import com.pedro.encoder.input.gl.render.filters.`object`.ImageObjectFilterRender
import com.pedro.encoder.input.gl.render.filters.`object`.SurfaceFilterRender
import com.pedro.encoder.input.gl.render.filters.`object`.TextObjectFilterRender
import com.pedro.encoder.input.video.CameraCallbacks
import com.pedro.encoder.input.video.CameraHelper
import com.pedro.encoder.input.video.CameraHelper.Facing.BACK
import com.pedro.encoder.input.video.FrameCapturedCallback
import com.pedro.encoder.utils.gl.AspectRatioMode
import com.pedro.encoder.utils.gl.TranslateTo
import com.pedro.library.rtmp.RtmpCamera2
import com.pedro.library.util.streamclient.RtmpStreamClient
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.platform.PlatformView
import com.pedro.library.view.OpenGlView
import com.pedro.library.util.BitrateAdapter
import java.io.*


class CameraNativeView(
    private var activity: Activity? = null,
    private var enableAudio: Boolean = false,
    private val preset: ResolutionPreset,
    private var cameraName: String,
    private var dartMessenger: DartMessenger? = null
) :
    PlatformView,
    SurfaceHolder.Callback,
    ConnectChecker {
    private val glView = OpenGlView(activity)
    private val rtmpCamera: RtmpCamera2
    private var isSurfaceCreated = false
    private var fps = 0
    private val aBitrate = 128 * 1000
    private val vBitrate = 1200 * 1000
    private val bitrateAdapter: BitrateAdapter
  val spriteGestureController = SpriteGestureController()
    /** 当前已设置的滤镜实例，removeFilter 必须用同一实例才能生效 */
    private var currentFilter: BaseFilterRender? = null
    private var currentFilterType: Int? = null
    /** RootEncoder 2.7.0+：下一帧编码使用 BT.709 色彩（在 prepare 前设置） */
    private var forceBt709Color: Boolean = false
    /** RootEncoder 2.7.0+：RTMP 周期 ping，用于 RTT（须在与 startStream 前对 RtmpStreamClient 设置） */
    private var rtmpShouldSendPings: Boolean = false
    /** 自定义音频码率（bps），在 prepareAudio 时使用 */
    private var customAudioBitrate: Int? = null
    /** 自定义视频帧率，在 prepareVideo / startPreview 时使用 */
    private var customVideoFps: Int? = null
    /** 自定义视频码率（bps），推流中可通过 setVideoBitrateOnFly 热更新 */
    private var customVideoBitrate: Int? = null
    /** 切后台前正在推流时，Surface 重建后自动恢复 */
    private var lastStreamUrl: String? = null
    private var lastStreamBitrate: Int? = null
    private var resumeStreamAfterSurfaceCreated = false
    /** 因 Surface 销毁暂停推流时，忽略 stopStream 触发的 onDisconnect */
    private var isRestoringFromSurfaceDestroy = false

    /**
     * True while the session is rendering to an off-screen surface because the
     * preview surface has gone.
     *
     * The session is fully live in this state -- streaming, recording, camera
     * open. Only the on-screen preview is absent, which is the correct thing to
     * be absent when there is no surface to draw on.
     */
    private var isRenderingOffScreen = false

    // --- Camera stall detection and recovery ---------------------------------
    //
    // Everything the app could previously see followed the *encoder*: fps,
    // dropped frames, cache occupancy, the RTMP connection. None of them can
    // detect a stopped camera, because a GL surface holding a still image keeps
    // the encoder fully occupied producing frames of a photograph. Measured
    // 2026-08-04: a camera stalled for 135 seconds of a 216-second session while
    // every one of those signals reported perfect health.
    //
    // `enableFrameCaptureCallback` is fired from Camera2's own capture session
    // (onCaptureStarted), so it follows the camera hardware and stops dead when
    // the camera does. It is registered in `init` and not later, because the
    // capture session only installs its callback if one is present when the
    // session is created -- registering after the camera opens would do nothing
    // until the next open.
    //
    // A stalled camera also corrupts the local recording: the file and the
    // stream are muxed from the same encoder, so a frozen picture goes into
    // both. This is the first failure mode the local backstop does not survive,
    // which is why it is worth recovering from rather than only reporting.

    /** When the camera last delivered a frame. Written from the camera thread. */
    @Volatile
    private var lastCameraFrameAtMs = 0L

    /** Longest completed gap between camera frames this session, for tuning. */
    @Volatile
    private var largestCameraFrameGapMs = 0L

    /** When the current stall began, or 0 when frames are arriving. */
    private var cameraStallStartedAtMs = 0L
    private var cameraEverStalled = false
    private var totalCameraStalledMs = 0L
    private var stallRecoveryAttempts = 0
    private var lastRecoveryAtMs = 0L
    private var reportedGivingUp = false

    /**
     * How long without a camera frame before the picture is called stalled.
     *
     * Provisional, and deliberately not a round number chosen for looking
     * sensible. There is no body of camera-frame-interval measurements yet --
     * this callback is new -- so it is derived from the closest thing there is,
     * the gaps between *encoded* frames on clean sessions, which were 94ms
     * undisturbed and 152-168ms during a phone call or around a stall.
     *
     * Three seconds is about eighteen times the worst of those. The margin is
     * that wide on purpose: a false detection triggers a renderer cycle that
     * itself costs the best part of a second of picture, so being trigger-happy
     * has a real cost. Three seconds of frozen picture is a glitch; the failure
     * this exists for was 135 seconds.
     *
     * Every session logs the largest camera-frame gap it saw. Set this from
     * those once there are some.
     */
    private val cameraStallThresholdMs = 3000L

    private val stallCheckIntervalMs = 1000L

    /**
     * Recovery attempts allowed before the app stops trying.
     *
     * The cap is the important half. Recovery works by cycling the renderer,
     * which closes and reopens the camera -- so if cycling is *itself* what
     * stalls the camera, an uncapped detector spins forever and the coach gets a
     * session that is broken every few seconds instead of broken once. A loop is
     * worse than a stall.
     */
    private val maxStallRecoveryAttempts = 5
    private val recoveryBackoffBaseMs = 5000L
    private val recoveryBackoffCeilingMs = 60000L

    /**
     * How long the camera must run cleanly before the attempt count resets.
     *
     * This is what tells the two cases apart. If recovery works and stalls are
     * occasional, they arrive minutes apart and the budget should not be spent
     * by a long session. If recovery is what causes the stall, the next one
     * arrives within seconds and the budget is never refilled -- so the cap
     * still bites, which is exactly when it needs to.
     */
    private val healthyResetMs = 10 * 60 * 1000L

    private val stallHandler = Handler(Looper.getMainLooper())
    init {
//        glView.isKeepAspectRatio = true
        glView.setAspectRatioMode(AspectRatioMode.Adjust)
        glView.holder.addCallback(this)
        rtmpCamera = RtmpCamera2(glView, this)
        rtmpCamera.streamClient.setReTries(10)
        rtmpCamera.setFpsListener { fps = it }
        bitrateAdapter = BitrateAdapter {
            rtmpCamera.setVideoBitrateOnFly(it)
        }.apply {
            // Video-only ceiling. The adapter drives setVideoBitrateOnFly, so folding the
            // audio bitrate in here was letting the video encoder be told to use bandwidth
            // the audio encoder is separately spending.
            setMaxBitrate(vBitrate)
        }

        // Registered here, before any camera is opened, and not later: Camera2's
        // capture session only attaches its callback if one is already present
        // when the session is built, so setting this after the camera opens
        // silently does nothing until the next open.
        //
        // It survives the camera being closed and reopened -- which is what
        // recovery does -- because it is held on the camera manager, which
        // outlives the session.
        rtmpCamera.enableFrameCaptureCallback(object : FrameCapturedCallback {
            override fun onFrameCaptured(timestamp: Long, frameNumber: Long) {
                onCameraFrameDelivered()
            }
        })

        // Lifecycle, not frames. A camera that errors or is taken away announces
        // itself here, which is worth acting on immediately rather than waiting
        // for the stall threshold to expire.
        rtmpCamera.setCameraCallbacks(object : CameraCallbacks {
            override fun onCameraOpened() {
                Log.d("CameraNativeView", "camera opened")
            }

            override fun onCameraChanged(facing: CameraHelper.Facing) {
                Log.d("CameraNativeView", "camera changed to $facing")
            }

            override fun onCameraError(error: String) {
                Log.e("CameraNativeView", "camera error: $error")
            }

            override fun onCameraDisconnected() {
                Log.w("CameraNativeView", "camera disconnected")
            }
        })

        // The watchdog is deliberately not started here. Kotlin initialises
        // properties in declaration order, and the watchdog runnable is declared
        // below this block, so referencing it from init reads an uninitialised
        // field. It is started from surfaceCreated and from a session beginning,
        // both of which are idempotent.
    }

    // --- Camera stall detection ----------------------------------------------

    /**
     * Starts the stall watchdog, or restarts it if it is already running.
     *
     * Idempotent by removing before posting, so the several places that
     * legitimately want to be sure it is running cannot between them end up with
     * two copies ticking.
     */
    private fun ensureStallWatchdogRunning() {
        stallHandler.removeCallbacks(stallWatchdog)
        stallHandler.postDelayed(stallWatchdog, stallCheckIntervalMs)
    }

    /**
     * A frame arrived from the camera. Runs on the camera thread, so it does as
     * little as possible.
     */
    private fun onCameraFrameDelivered() {
        val now = SystemClock.elapsedRealtime()
        val previous = lastCameraFrameAtMs
        if (previous != 0L) {
            val gap = now - previous
            if (gap > largestCameraFrameGapMs) largestCameraFrameGapMs = gap
        }
        lastCameraFrameAtMs = now
    }

    private val stallWatchdog = object : Runnable {
        override fun run() {
            try {
                checkForCameraStall()
            } catch (e: Exception) {
                // Never allowed to take the session down. A watchdog that can
                // kill the thing it is watching is worse than no watchdog.
                Log.e("CameraNativeView", "camera stall check failed", e)
            }
            stallHandler.postDelayed(this, stallCheckIntervalMs)
        }
    }

    private fun checkForCameraStall() {
        // Only while something is being captured. A stalled preview on an idle
        // app is nobody's problem, and recovering it would cycle the camera for
        // no reason.
        if (!rtmpCamera.isStreaming && !rtmpCamera.isRecording) return

        val lastFrame = lastCameraFrameAtMs
        if (lastFrame == 0L) return // no frames yet; nothing to be late

        val now = SystemClock.elapsedRealtime()
        val sinceLastFrame = now - lastFrame

        if (sinceLastFrame >= cameraStallThresholdMs) {
            if (cameraStallStartedAtMs == 0L) {
                // Dated from the last frame that actually arrived, not from the
                // moment it was noticed, so the reported duration is the length
                // of the gap rather than the length of the gap minus the
                // threshold.
                cameraStallStartedAtMs = lastFrame
                cameraEverStalled = true
                Log.w(
                    "CameraNativeView",
                    "camera stalled: no frame for ${sinceLastFrame}ms " +
                        "(threshold ${cameraStallThresholdMs}ms)"
                )
                sendStallEvent(
                    DartMessenger.EventType.CAMERA_STALLED,
                    "the camera stopped delivering frames",
                    now,
                )
            }
            maybeRecoverFromStall(now)
            return
        }

        if (cameraStallStartedAtMs != 0L) {
            val stalledFor = lastFrame - cameraStallStartedAtMs
            totalCameraStalledMs += stalledFor
            cameraStallStartedAtMs = 0L
            reportedGivingUp = false
            Log.w(
                "CameraNativeView",
                "camera recovered after ${stalledFor}ms " +
                    "(attempts=$stallRecoveryAttempts total=${totalCameraStalledMs}ms)"
            )
            sendStallEvent(
                DartMessenger.EventType.CAMERA_RECOVERED,
                "the camera started delivering frames again",
                now,
                stalledForMs = stalledFor,
            )
            return
        }

        // Healthy. Give the recovery budget back once the camera has been fine
        // for long enough that a further stall cannot plausibly be this app's
        // own doing.
        if (stallRecoveryAttempts > 0 &&
            lastRecoveryAtMs != 0L &&
            now - lastRecoveryAtMs >= healthyResetMs
        ) {
            Log.d(
                "CameraNativeView",
                "camera healthy for ${healthyResetMs}ms, resetting recovery budget"
            )
            stallRecoveryAttempts = 0
            lastRecoveryAtMs = 0L
        }
    }

    /**
     * Cycles the renderer to reopen the camera, within a capped, backing-off
     * budget.
     *
     * The cycle is the recovery that was observed working on 2026-08-04: a
     * surface change moved rendering off-screen in 55ms and back in 884ms, the
     * camera reopened, frames resumed, and the stream never dropped. The only
     * reason it happened at all was that the phone was picked up and unlocked --
     * which under the attention model for this app is exactly what will not
     * happen, so the app has to do it itself.
     */
    private fun maybeRecoverFromStall(now: Long) {
        if (stallRecoveryAttempts >= maxStallRecoveryAttempts) {
            if (!reportedGivingUp) {
                reportedGivingUp = true
                Log.e(
                    "CameraNativeView",
                    "camera still stalled after $maxStallRecoveryAttempts recovery " +
                        "attempts; giving up for this session"
                )
                sendStallEvent(
                    DartMessenger.EventType.CAMERA_STALL_UNRECOVERED,
                    "the camera could not be restarted",
                    now,
                )
            }
            return
        }

        // Exponential backoff, so a recovery that is itself causing the stall
        // gets slower rather than faster.
        val backoff = minOf(
            recoveryBackoffCeilingMs,
            recoveryBackoffBaseMs shl stallRecoveryAttempts,
        )
        if (lastRecoveryAtMs != 0L && now - lastRecoveryAtMs < backoff) return

        stallRecoveryAttempts++
        lastRecoveryAtMs = now
        Log.w(
            "CameraNativeView",
            "attempting camera recovery $stallRecoveryAttempts/$maxStallRecoveryAttempts " +
                "after ${now - cameraStallStartedAtMs}ms stalled (backoff was ${backoff}ms)"
        )
        sendStallEvent(
            DartMessenger.EventType.CAMERA_STALLED,
            "restarting the camera",
            now,
        )

        try {
            cycleRendererToReopenCamera()
        } catch (e: Exception) {
            // The session is still running; only the recovery failed. Reporting
            // and leaving it alone is better than tearing down a session that is
            // at least still carrying sound.
            Log.e("CameraNativeView", "camera recovery attempt failed", e)
        }
    }

    /**
     * Closes and reopens the camera by swapping the renderer.
     *
     * `replaceView` is the only public route that reopens the camera without
     * stopping the encoders, which is what keeps the stream and the recording
     * running across it.
     */
    private fun cycleRendererToReopenCamera() {
        val context = glView.context.applicationContext
        if (isRenderingOffScreen || !isSurfaceCreated) {
            // Already off-screen. Swapping in a fresh off-screen renderer still
            // closes and reopens the camera, which is the part that matters.
            rtmpCamera.replaceView(context)
            return
        }
        rtmpCamera.replaceView(context)
        rtmpCamera.replaceView(glView)
    }

    private fun sendStallEvent(
        eventType: DartMessenger.EventType,
        description: String,
        now: Long,
        stalledForMs: Long? = null,
    ) {
        val stalledNow = cameraStallStartedAtMs != 0L
        val currentStall = stalledForMs
            ?: if (stalledNow) now - cameraStallStartedAtMs else 0L
        getActivity()?.runOnUiThread {
            dartMessenger?.send(
                eventType,
                description,
                mapOf(
                    "cameraStalled" to stalledNow,
                    "cameraEverStalled" to cameraEverStalled,
                    "currentStallMillis" to currentStall,
                    "totalStalledMillis" to totalCameraStalledMs + if (stalledNow) currentStall else 0L,
                    "recoveryAttempts" to stallRecoveryAttempts,
                    "maxRecoveryAttempts" to maxStallRecoveryAttempts,
                    "largestFrameGapMillis" to largestCameraFrameGapMs,
                ),
            )
        }
    }

    /** Clears per-session stall state. Called when a session actually starts. */
    private fun resetStallTracking() {
        cameraStallStartedAtMs = 0L
        cameraEverStalled = false
        totalCameraStalledMs = 0L
        stallRecoveryAttempts = 0
        lastRecoveryAtMs = 0L
        reportedGivingUp = false
        largestCameraFrameGapMs = 0L
        // A session is starting, so make certain the watchdog is ticking even if
        // the surface callbacks have not run for some reason.
        ensureStallWatchdogRunning()
    }

    /**
     * Re-point the adaptive ceiling at the video bitrate that was actually requested.
     *
     * The adapter only ever adapts *downwards* from its max, so the max has to track the
     * requested bitrate. It used to be pinned to the `vBitrate + aBitrate` constant, which
     * capped every stream at 1328000bps within a few callbacks no matter what the caller
     * asked for.
     *
     * Call this anywhere the requested video bitrate is established or changed
     * (startVideoStreaming, setVideoSettings, stream resume). BitrateAdapter.setMaxBitrate
     * also resets the adapter's running average, so the next congestion decision is made
     * against the new ceiling rather than against history from the old one.
     *
     * The adaptive step-down itself is untouched: this raises the roof, it does not stop
     * the adapter backing off when the network is congested. Called from the main thread;
     * the adapter is read from the RootEncoder network thread in [onNewBitrate].
     */
    private fun applyBitrateCeiling(videoBitrate: Int) {
        if (videoBitrate <= 0) {
            Log.w("CameraNativeView", "applyBitrateCeiling ignoring non-positive bitrate: $videoBitrate")
            return
        }
        bitrateAdapter.setMaxBitrate(videoBitrate)
        Log.d("CameraNativeView", "BitrateAdapter max bitrate set to $videoBitrate bps")
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.d("CameraNativeView", "surfaceCreated")
        isSurfaceCreated = true
        ensureStallWatchdogRunning()
        glView.post { restorePreviewAfterSurfaceChange() }
    }

    override fun surfaceChanged(p0: SurfaceHolder, p1: Int, p2: Int, p3: Int) {
        // TODO("Not yet implemented")
    }

    /**
     * The preview surface has gone.
     *
     * This does **not** mean the session has gone, and the two used to be
     * conflated. The old behaviour stopped the stream and closed the camera
     * whenever the surface went, which made an ordinary, unavoidable event into
     * the end of capture.
     *
     * Measured on device 2026-08-04, with the native callback logged: this fires
     * when a phone call ends, when the phone is woken from a locked screen, when
     * the app is swiped out of recents, and at least once spontaneously thirteen
     * seconds into a session with nobody touching the phone. Notably it does
     * *not* fire on pressing Home, and it does not fire when the screen is
     * locked -- only when it is woken again. So this is not something the coach
     * does; it is something that happens.
     *
     * `replaceView(Context)` is RootEncoder's supported answer: it swaps the
     * on-screen renderer for an off-screen one, closing and reopening the camera
     * around the swap while leaving the encoders running. The stream is never
     * stopped, so there is no reconnection to make and no gap in the recording
     * beyond the moment the camera takes to reopen.
     */
    override fun surfaceDestroyed(p0: SurfaceHolder) {
        Log.d("CameraNativeView", "surfaceDestroyed")
        isSurfaceCreated = false

        if (rtmpCamera.isStreaming || rtmpCamera.isRecording) {
            try {
                rtmpCamera.replaceView(glView.context.applicationContext)
                isRenderingOffScreen = true
                Log.d("CameraNativeView", "rendering off-screen, capture continues")
                return
            } catch (e: Exception) {
                // Fall through to the old teardown. It loses the stream until the
                // surface comes back, which is bad -- but it is what this did
                // before, so a failure here is no worse than the previous
                // behaviour rather than a new way to lose a session.
                Log.e("CameraNativeView", "replaceView to off-screen failed, falling back", e)
                isRenderingOffScreen = false
                if (rtmpCamera.isStreaming) {
                    resumeStreamAfterSurfaceCreated = true
                    isRestoringFromSurfaceDestroy = true
                    try {
                        rtmpCamera.stopStream()
                    } catch (e2: Exception) {
                        Log.e("CameraNativeView", "stopStream on surfaceDestroyed failed", e2)
                        isRestoringFromSurfaceDestroy = false
                        resumeStreamAfterSurfaceCreated = false
                    }
                }
            }
        }

        if (rtmpCamera.isOnPreview) {
            try {
                rtmpCamera.stopCamera()
            } catch (e: Exception) {
                Log.e("CameraNativeView", "stopCamera on surfaceDestroyed failed", e)
            }
        }
    }

    override fun onConnectionStarted(url: String) {
        activity?.runOnUiThread {
            dartMessenger?.send(DartMessenger.EventType.WAIT, "connection wait")
        }
    }

    override fun onConnectionSuccess() {
        isRestoringFromSurfaceDestroy = false
        activity?.runOnUiThread {
            dartMessenger?.send(DartMessenger.EventType.SUCCESS, "connection success")
        }
    }

    override fun onNewBitrate(bitrate: Long) {
        bitrateAdapter.adaptBitrate(bitrate, rtmpCamera.getStreamClient().hasCongestion())
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    override fun onConnectionFailed(reason: String) {
        activity?.runOnUiThread { //Wait 5s and retry connect stream
            if (rtmpCamera.streamClient.reTry(5000, reason)) {
                dartMessenger?.send(DartMessenger.EventType.RTMP_RETRY, reason)
            } else {
                dartMessenger?.send(DartMessenger.EventType.RTMP_STOPPED, "Failed retry")
                isRestoringFromSurfaceDestroy = false
                rtmpCamera.stopStream()
            }
        }
    }

    override fun onDisconnect() {
        if (isRestoringFromSurfaceDestroy) {
            // Reported, not swallowed.
            //
            // This used to return here without telling Dart anything, which left
            // the app showing LIVE, polling statistics and reporting a healthy
            // stream while the far end had seen the input stop. The session is
            // genuinely still running -- a restore is expected -- so this must
            // not be reported as the stream ending; but "still running" and
            // "connected" are different claims and only the first one is true.
            Log.d("CameraNativeView", "onDisconnect during surface restore, reporting as interrupted")
            activity?.runOnUiThread {
                dartMessenger?.send(
                    DartMessenger.EventType.RTMP_INTERRUPTED,
                    "connection interrupted while the preview surface was gone"
                )
            }
            return
        }
        activity?.runOnUiThread {
            dartMessenger?.sendCameraClosingEvent()
        }
    }

    override fun onAuthError() {
        activity?.runOnUiThread {
            dartMessenger?.send(DartMessenger.EventType.ERROR, "Auth error")
        }
    }

    override fun onAuthSuccess() {
    }

    private fun prepareAudioEncoder(): Boolean {
        if (!enableAudio) {
            return true
        }
        val bitrate = customAudioBitrate ?: aBitrate
        return rtmpCamera.prepareAudio(bitrate, 32000, true)
    }

    private fun prepareVideoEncoder(size: Size, bitrate: Int): Boolean {
        val fps = customVideoFps ?: 30
        val rotation = CameraHelper.getCameraOrientation(getActivity() ?: glView.context)
        return rtmpCamera.prepareVideo(size.width, size.height, fps, bitrate, rotation)
    }

    fun prepareForVideoStreaming(result: MethodChannel.Result) {
        // Android 无需预准备音频，与 iOS 行为对齐为 no-op
        result.success(null)
    }

    fun getHasAudio(result: MethodChannel.Result) {
        result.success(!rtmpCamera.isAudioMuted)
    }

    fun setHasAudio(isEnable: Boolean?, result: MethodChannel.Result) {
        if (isEnable == null) {
            result.error("setHasAudio", "isEnable is required", null)
            return
        }
        try {
            if (isEnable) {
                rtmpCamera.enableAudio()
            } else {
                rtmpCamera.disableAudio()
            }
            result.success(null)
        } catch (e: Exception) {
            result.error("setHasAudio", e.message, null)
        }
    }

    fun getHasVideo(result: MethodChannel.Result) {
        val muted = rtmpCamera.glInterface?.isVideoMuted ?: false
        result.success(!muted)
    }

    fun setHasVideo(isEnable: Boolean?, result: MethodChannel.Result) {
        if (isEnable == null) {
            result.error("setHasVideo", "isEnable is required", null)
            return
        }
        try {
            val gl = rtmpCamera.glInterface
            if (gl == null) {
                result.error("setHasVideo", "OpenGL interface not available", null)
                return
            }
            if (isEnable) {
                gl.unMuteVideo()
            } else {
                gl.muteVideo()
            }
            result.success(null)
        } catch (e: Exception) {
            result.error("setHasVideo", e.message, null)
        }
    }

    fun setAudioSettings(bitrate: Int?, result: MethodChannel.Result) {
        if (bitrate == null) {
            result.error("setAudioSettings", "bitrate is required", null)
            return
        }
        customAudioBitrate = bitrate
        result.success(null)
    }

    fun setVideoSettings(
        bitrate: Int?,
        width: Int?,
        height: Int?,
        frameInterval: Int?,
        result: MethodChannel.Result
    ) {
        try {
            if (bitrate != null) {
                customVideoBitrate = bitrate
                // Raise/lower the adaptive ceiling with the request, otherwise the adapter
                // pulls an on-the-fly increase straight back down on the next callback.
                applyBitrateCeiling(bitrate)
                if (rtmpCamera.isStreaming) {
                    rtmpCamera.setVideoBitrateOnFly(bitrate)
                }
            }
            if (frameInterval != null) {
                // RootEncoder 在推流中修改 I 帧间隔需重新 prepare，此处仅记录供文档说明
                Log.w("CameraNativeView", "setVideoSettings frameInterval ignored on Android during stream")
            }
            if (width != null && height != null && !rtmpCamera.isStreaming) {
                Log.w("CameraNativeView", "setVideoSettings width/height apply on next startVideoStreaming")
            }
            result.success(null)
        } catch (e: Exception) {
            result.error("setVideoSettings", e.message, null)
        }
    }

    fun setFrameRate(frameRate: Int?, result: MethodChannel.Result) {
        if (frameRate == null || frameRate <= 0) {
            result.error("setFrameRate", "frameRate must be > 0", null)
            return
        }
        customVideoFps = frameRate
        try {
            rtmpCamera.glInterface?.forceFpsLimit(frameRate)
            result.success(null)
        } catch (e: Exception) {
            result.error("setFrameRate", e.message, null)
        }
    }

    fun close() {
        Log.d("CameraNativeView", "close")
    }

    fun takePicture(filePath: String, result: MethodChannel.Result) {
        Log.d("CameraNativeView", "takePicture filePath: $filePath result: $result")
        val file: File = File(filePath)
        if (file.exists()) {
            result.error(
                "fileExists",
                "File at path '$filePath' already exists. Cannot overwrite.",
                null
            )
            return
        }
        glView.takePhoto {
            try {
                val outputStream: OutputStream = BufferedOutputStream(FileOutputStream(file))
                it.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
                outputStream.close()
                view.post { result.success(null) }
            } catch (e: IOException) {
                result.error("IOError", "Failed saving image", null)
            }
        }
    }

    fun startVideoRecording(filePath: String?, result: MethodChannel.Result) {
        if (filePath == null) {
            result.error("fileExists", "Must specify a filePath.", null)
            return
        }

        val file = File(filePath)
        if (file.exists()) {
            result.error(
                "fileExists",
                "File at path '$filePath' already exists. Cannot overwrite.",
                null
            )
            return
        }
        Log.d("CameraNativeView", "startVideoRecording filePath: $filePath result: $result")
        // Same guard as startVideoStreaming, from the other side: only reset when
        // this call is what begins the session.
        if (!rtmpCamera.isStreaming && !rtmpCamera.isRecording) resetStallTracking()


        /*if (rtmpCamera.isRecording || rtmpCamera.prepareAudio() && rtmpCamera.prepareVideo(
                streamingSize.videoFrameWidth,
                streamingSize.videoFrameHeight,
                streamingSize.videoBitRate
            )*/
        //判断如果不是视频流的话并且其用了音频
        try {
            if (!rtmpCamera.isStreaming) {
                val streamingSize = CameraUtils.computeBestPreviewSize(activity, cameraName, preset)
                val size = streamingSize["size"] as Size
                val bitrateRes = streamingSize["bitrate"] as Int
                rtmpCamera.forceBt709Color(forceBt709Color)
                if (prepareAudioEncoder() && prepareVideoEncoder(
                        size,
                        bitrateRes
                    )
                ) {
                    rtmpCamera.startRecord(filePath)
                }

            } else {
                rtmpCamera.startRecord(filePath)
            }
            result.success(null)
        } catch (e: CameraAccessException) {
            result.error("videoRecordingFailed", e.message, null)
        } catch (e: IOException) {
            result.error("videoRecordingFailed", e.message, null)
        }

    }


    fun startVideoStreaming(url: String?, bitrate: Int?, result: MethodChannel.Result) {
        Log.d("CameraNativeView", "startVideoStreaming url: ${redactStreamUrl(url)}")
        if (url == null) {
            result.error("startVideoStreaming", "Must specify a url.", null)
            return
        }

        try {
            if (!rtmpCamera.isStreaming) {
                // A session is actually beginning here, so the stall counters
                // start again. Guarded on !isStreaming rather than run
                // unconditionally: callers start the recording immediately after
                // the stream, and resetting on both would wipe the stream's
                // history a moment after creating it.
                if (!rtmpCamera.isRecording) resetStallTracking()
                lastStreamUrl = url
                lastStreamBitrate = bitrate
                val streamingSize = CameraUtils.computeBestPreviewSize(getActivity(), cameraName, preset)
                val size = streamingSize["size"] as Size
                val bitrateRes = customVideoBitrate ?: (bitrate ?: (streamingSize["bitrate"] as Int))
                // The encoder is prepared at bitrateRes, so that is what the adapter must
                // treat as its ceiling.
                applyBitrateCeiling(bitrateRes)
                rtmpCamera.forceBt709Color(forceBt709Color)
                (rtmpCamera.streamClient as? RtmpStreamClient)?.shouldSendPings(rtmpShouldSendPings)
                if (rtmpCamera.isRecording || prepareAudioEncoder() && prepareVideoEncoder(
                        size,
                        bitrateRes
                    )
                ) {
                    // ready to start streaming
                    rtmpCamera.startStream(url)
                } else {
                    result.error(
                        "videoStreamingFailed",
                        "Error preparing stream, This device cant do it",
                        null
                    )
                    return
                }
            } else {
                rtmpCamera.stopStream()
            }
            result.success(null)
        } catch (e: CameraAccessException) {
            result.error("videoStreamingFailed", e.message, null)
        } catch (e: IOException) {
            result.error("videoStreamingFailed", e.message, null)
        }
    }

    fun startVideoRecordingAndStreaming(
        filePath: String?,
        url: String?,
        bitrate: Int?,
        result: MethodChannel.Result
    ) {
        if (filePath == null) {
            result.error("fileExists", "Must specify a filePath.", null)
            return
        }
        if (File(filePath).exists()) {
            result.error("fileExists", "File at path '$filePath' already exists.", null)
            return
        }
        if (url == null) {
            result.error("fileExists", "Must specify a url.", null)
            return
        }
        try {
            startVideoRecording(filePath, result)
            startVideoStreaming(url, bitrate, result)
          result.success(null)
        } catch (e: CameraAccessException) {
            result.error("videoRecordingFailed", e.message, null)
        } catch (e: IOException) {
            result.error("videoRecordingFailed", e.message, null)
        }
    }


    //开/关闪光灯
    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    fun switchFlashLight(isEnable: Boolean?, result: MethodChannel.Result) {
        try {
            if(rtmpCamera.cameraFacing != BACK){
                result.error("switchFlashLightFailed", "camera is Not BACK", null)
                return
            }
             if (isEnable == null) {
                result.error("switchFlashLightFailed", "isEnable not empty.", null)
                return
            }
            if(isEnable == true){
                 rtmpCamera.enableLantern()
            }else{
                rtmpCamera.disableLantern()
            }
          result.success(null)
        } catch (e: CameraAccessException) {
            result.error("switchFlashLightFailed", e.message, null)
        } catch (e: IOException) {
            result.error("switchFlashLightFailed", e.message, null)
        }
    }

    //切换相机式
    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    fun switchCamera(cameraId: String?, result: MethodChannel.Result) {

        try {
          if (cameraId == null) {
            result.error("cameraIdExist", "empty cameraId!", null)
            return
          }
          rtmpCamera.switchCamera(cameraId)
          cameraName = cameraId
          result.success(null)
        } catch (e: CameraAccessException) {
            result.error("switchCameraFailed", e.message, null)
        } catch (e: IOException) {
            result.error("switchCameraFailed", e.message, null)
        }


    }

    //开/关声音
    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    fun switchAudio(isEnable: Boolean?,result: MethodChannel.Result) {
        try {
            if (isEnable == null) {
                result.error("switchAudioFailed", "empty isEnable!", null)
                return
            }
            if(isEnable == true){
                rtmpCamera.enableAudio()
            }else{
                rtmpCamera.disableAudio()
            }
          result.success(null)
        } catch (e: CameraAccessException) {
            result.error("switchAudioFailed", e.message, null)
        } catch (e: IOException) {
            result.error("switchAudioFailed", e.message, null)
        }
    }

    //设置滤镜
    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    fun setFilter(type: Int?,filePath: String?, result: MethodChannel.Result) {
        try {
          if(type == null){
            result.error("setFilter", "type is empty", null)
            return
          }
            spriteGestureController.stopListener()
          when (type) {
            0 -> {
              val f = BasicDeformationFilterRender()
              rtmpCamera.glInterface?.setFilter(f)
              currentFilter = f
              currentFilterType = type
              result.success(null)
            }
            1 -> {
              val f = BeautyFilterRender()
              rtmpCamera.glInterface?.setFilter(f)
              currentFilter = f
              currentFilterType = type
              result.success(null)
            }
            2 -> {
              val f = BlackFilterRender()
              rtmpCamera.glInterface?.setFilter(f)
              currentFilter = f
              currentFilterType = type
              result.success(null)
            }
            3 -> {
              val f = BlurFilterRender()
              rtmpCamera.glInterface?.setFilter(f)
              currentFilter = f
              currentFilterType = type
              result.success(null)
            }
            4 -> {
              val f = BrightnessFilterRender()
              rtmpCamera.glInterface?.setFilter(f)
              currentFilter = f
              currentFilterType = type
              result.success(null)
            }
            5 -> {
              val f = CartoonFilterRender()
              rtmpCamera.glInterface?.setFilter(f)
              currentFilter = f
              currentFilterType = type
              result.success(null)
            }
            6 -> {
              if (filePath == null) {
                result.error("setFilter", "filePath Not Empty", null)
                return
              }
              val chromaFilterRender = ChromaFilterRender()
              rtmpCamera.glInterface?.setFilter(chromaFilterRender)
              chromaFilterRender.setImage(
                BitmapFactory.decodeFile(filePath)
              )
              currentFilter = chromaFilterRender
              currentFilterType = type
              result.success(null)
            }
            7 -> {
              val f = ChromaticAberrationFilterRender()
              rtmpCamera.glInterface?.setFilter(f)
              currentFilter = f
              currentFilterType = type
              result.success(null)
            }
            8 -> {
              val f = CircleFilterRender()
              rtmpCamera.glInterface?.setFilter(f)
              currentFilter = f
              currentFilterType = type
              result.success(null)
            }
            9 -> {
              val f = ColorFilterRender()
              rtmpCamera.glInterface?.setFilter(f)
              currentFilter = f
              currentFilterType = type
              result.success(null)
            }
            10 -> {
              val f = ContrastFilterRender()
              rtmpCamera.glInterface?.setFilter(f)
              currentFilter = f
              currentFilterType = type
              result.success(null)
            }
            11 -> {
              val f = CropFilterRender().apply {
                //crop center of the image with 40% of width and 40% of height
                setCropArea(30f, 30f, 40f, 40f)
              }
              rtmpCamera.glInterface?.setFilter(f)
              currentFilter = f
              currentFilterType = type
              result.success(null)
            }
            12 -> {
              val f = DistortedTvFilterRender()
              rtmpCamera.glInterface?.setFilter(f)
              currentFilter = f
              currentFilterType = type
              result.success(null)
            }
            13 -> {
              val f = DuotoneFilterRender()
              rtmpCamera.glInterface?.setFilter(f)
              currentFilter = f
              currentFilterType = type
              result.success(null)
            }
            14 -> {
              val f = EarlyBirdFilterRender()
              rtmpCamera.glInterface?.setFilter(f)
              currentFilter = f
              currentFilterType = type
              result.success(null)
            }
            15 -> {
              val f = EdgeDetectionFilterRender()
              rtmpCamera.glInterface?.setFilter(f)
              currentFilter = f
              currentFilterType = type
              result.success(null)
            }
            43 -> {
              val f = EdgeDetectionFilterRender(false)
              rtmpCamera.glInterface?.setFilter(f)
              currentFilter = f
              currentFilterType = type
              result.success(null)
            }
            16 -> {
              val f = ExposureFilterRender()
              rtmpCamera.glInterface?.setFilter(f)
              currentFilter = f
              currentFilterType = type
              result.success(null)
            }
            17 -> {
              val f = FireFilterRender()
              rtmpCamera.glInterface?.setFilter(f)
              currentFilter = f
              currentFilterType = type
              result.success(null)
            }
            18 -> {
              val f = GammaFilterRender()
              rtmpCamera.glInterface?.setFilter(f)
              currentFilter = f
              currentFilterType = type
              result.success(null)
            }
            19 -> {
              val f = GlitchFilterRender()
              rtmpCamera.glInterface?.setFilter(f)
              currentFilter = f
              currentFilterType = type
              result.success(null)
            }
            20 -> {
              if (filePath == null) {
                result.error("setFilter", "filePath Not Empty", null)
                return
              }
              val file = File(filePath)
              val inputStream = FileInputStream(file)
              val gifObjectFilterRender = GifObjectFilterRender()
              gifObjectFilterRender.setGif(inputStream)
              rtmpCamera.glInterface?.setFilter(gifObjectFilterRender)
              gifObjectFilterRender.setScale(50f, 50f)
              gifObjectFilterRender.setPosition(TranslateTo.BOTTOM)
              spriteGestureController.setBaseObjectFilterRender(gifObjectFilterRender)
              currentFilter = gifObjectFilterRender
              currentFilterType = type
              result.success(null)
            }
            21 -> {
              val f = GreyScaleFilterRender()
              rtmpCamera.glInterface?.setFilter(f)
              currentFilter = f
              currentFilterType = type
              result.success(null)
            }
            22 -> {
              val f = HalftoneLinesFilterRender()
              rtmpCamera.glInterface?.setFilter(f)
              currentFilter = f
              currentFilterType = type
              result.success(null)
            }
            23 -> {
              if (filePath == null) {
                result.error("setFilter", "filePath Not Empty", null)
                return
              }
              val imageObjectFilterRender = ImageObjectFilterRender()
              rtmpCamera.glInterface?.setFilter(imageObjectFilterRender)
              imageObjectFilterRender.setImage(
                BitmapFactory.decodeFile(filePath)
              )
              imageObjectFilterRender.setScale(50f, 50f)
              imageObjectFilterRender.setPosition(TranslateTo.RIGHT)
              spriteGestureController.setBaseObjectFilterRender(imageObjectFilterRender) //Optional
              spriteGestureController.setPreventMoveOutside(false)
              currentFilter = imageObjectFilterRender
              currentFilterType = type
              result.success(null)
            }
            24 -> {
              val f = Image70sFilterRender()
              rtmpCamera.glInterface?.setFilter(f)
              currentFilter = f
              currentFilterType = type
              result.success(null)
            }
            25 -> {
              val f = LamoishFilterRender()
              rtmpCamera.glInterface?.setFilter(f)
              currentFilter = f
              currentFilterType = type
              result.success(null)
            }
            26 -> {
              val f = MoneyFilterRender()
              rtmpCamera.glInterface?.setFilter(f)
              currentFilter = f
              currentFilterType = type
              result.success(null)
            }
            27 -> {
              val f = NegativeFilterRender()
              rtmpCamera.glInterface?.setFilter(f)
              currentFilter = f
              currentFilterType = type
              result.success(null)
            }
            28 -> {
              val f = NoiseFilterRender()
              rtmpCamera.glInterface?.setFilter(f)
              currentFilter = f
              currentFilterType = type
              result.success(null)
            }
            29 -> {
              val f = PixelatedFilterRender()
              rtmpCamera.glInterface?.setFilter(f)
              currentFilter = f
              currentFilterType = type
              result.success(null)
            }
            30 -> {
              val f = PolygonizationFilterRender()
              rtmpCamera.glInterface?.setFilter(f)
              currentFilter = f
              currentFilterType = type
              result.success(null)
            }
            31 -> {
              val f = RainbowFilterRender()
              rtmpCamera.glInterface?.setFilter(f)
              currentFilter = f
              currentFilterType = type
              result.success(null)
            }
            32 -> {
              val rgbSaturationFilterRender = RGBSaturationFilterRender()
              rtmpCamera.glInterface?.setFilter(rgbSaturationFilterRender)
              rgbSaturationFilterRender.setRGBSaturation(1f, 0.8f, 0.8f)
              currentFilter = rgbSaturationFilterRender
              currentFilterType = type
              result.success(null)
            }
            33 -> {
              val f = RippleFilterRender()
              rtmpCamera.glInterface?.setFilter(f)
              currentFilter = f
              currentFilterType = type
              result.success(null)
            }
            34 -> {
              val rotationFilterRender = RotationFilterRender()
              rtmpCamera.glInterface?.setFilter(rotationFilterRender)
              rotationFilterRender.rotation = 90
              currentFilter = rotationFilterRender
              currentFilterType = type
              result.success(null)
            }
            35 -> {
              val f = SaturationFilterRender()
              rtmpCamera.glInterface?.setFilter(f)
              currentFilter = f
              currentFilterType = type
              result.success(null)
            }
            36 -> {
              val f = SepiaFilterRender()
              rtmpCamera.glInterface?.setFilter(f)
              currentFilter = f
              currentFilterType = type
              result.success(null)
            }
            37 -> {
              val f = SharpnessFilterRender()
              rtmpCamera.glInterface?.setFilter(f)
              currentFilter = f
              currentFilterType = type
              result.success(null)
            }
            38-> {
              val f = SnowFilterRender()
              rtmpCamera.glInterface?.setFilter(f)
              currentFilter = f
              currentFilterType = type
              result.success(null)
            }
            39-> {
              if (filePath == null) {
                result.error("setFilter", "filePath Not Empty", null)
                return
              }
              val surfaceFilterRender =
                SurfaceFilterRender { surfaceTexture -> //You can render this filter with other api that draw in a surface. for example you can use VLC
                  val mediaPlayer = MediaPlayer()
                  mediaPlayer.setDataSource(filePath)
                  mediaPlayer.setSurface(Surface(surfaceTexture))
                  mediaPlayer.start()
                }
              rtmpCamera.glInterface?.setFilter(surfaceFilterRender)
              surfaceFilterRender.setScale(50f, 33.3f)
              spriteGestureController.setBaseObjectFilterRender(surfaceFilterRender)
              currentFilter = surfaceFilterRender
              currentFilterType = type
              result.success(null)
            }
            40 -> {
              val f = TemperatureFilterRender()
              rtmpCamera.glInterface?.setFilter(f)
              currentFilter = f
              currentFilterType = type
              result.success(null)
            }
            41 -> {
              val textObjectFilterRender = TextObjectFilterRender()
              rtmpCamera.glInterface?.setFilter(textObjectFilterRender)
              textObjectFilterRender.setText("Hello world", 22f, Color.RED)
              textObjectFilterRender.setScale(50f, 50f)
              textObjectFilterRender.setPosition(TranslateTo.CENTER)
              spriteGestureController.setBaseObjectFilterRender(textObjectFilterRender) //Optional
              currentFilter = textObjectFilterRender
              currentFilterType = type
              result.success(null)
            }
            42 -> {
              val f = ZebraFilterRender()
              rtmpCamera.glInterface?.setFilter(f)
              currentFilter = f
              currentFilterType = type
              result.success(null)
            }
            else -> {
              result.success(null)
            }
          }

        } catch (e: CameraAccessException) {
          result.error("setFilter", e.message, null)
        } catch (e: IOException) {
          result.error("setFilter", e.message, null)
        }
    }

    //移除滤镜：必须使用 setFilter 时缓存的同一滤镜实例，底层按对象引用比较
    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    fun removeFilter(type: Int?, result: MethodChannel.Result) {
        try {
          if (type == null) {
            result.error("removeFilter", "type is empty", null)
            return
          }
          spriteGestureController.stopListener()
          val filterToRemove = currentFilter
          val filterType = currentFilterType
          if (filterToRemove != null && filterType == type) {
            rtmpCamera.glInterface?.removeFilter(filterToRemove)
            currentFilter = null
            currentFilterType = null
          }
          result.success(null)
        } catch (e: CameraAccessException) {
          result.error("removeFilter", e.message, null)
        } catch (e: IOException) {
          result.error("removeFilter", e.message, null)
        }
    }

    fun stopVideoRecordingOrStreaming(result: MethodChannel.Result) {
        try {
            resumeStreamAfterSurfaceCreated = false
            isRestoringFromSurfaceDestroy = false
            lastStreamUrl = null
            lastStreamBitrate = null
            rtmpCamera.apply {
                if (isStreaming) stopStream()
                if (isRecording) stopRecord()
            }
            result.success(null)
        } catch (e: CameraAccessException) {
            result.error("videoRecordingFailed", e.message, null)
        } catch (e: IllegalStateException) {
            result.error("videoRecordingFailed", e.message, null)
        }
    }

    fun stopVideoRecording(result: MethodChannel.Result) {
        try {
            rtmpCamera.apply {
                if (isRecording) stopRecord()
            }
            result.success(null)
        } catch (e: CameraAccessException) {
            result.error("stopVideoRecordingFailed", e.message, null)
        } catch (e: IllegalStateException) {
            result.error("stopVideoRecordingFailed", e.message, null)
        }
    }

    fun stopVideoStreaming(result: MethodChannel.Result) {
        try {
            resumeStreamAfterSurfaceCreated = false
            isRestoringFromSurfaceDestroy = false
            lastStreamUrl = null
            lastStreamBitrate = null
            rtmpCamera.apply { 
                if (isStreaming) stopStream()
            }
            result.success(null)
        } catch (e: CameraAccessException) {
            result.error("stopVideoStreamingFailed", e.message, null)
        } catch (e: IllegalStateException) {
            result.error("stopVideoStreamingFailed", e.message, null)
        }
    }

    fun pauseVideoRecording(result: MethodChannel.Result) {
        try {
            if (!rtmpCamera.isRecording) {
                result.error("pauseVideoRecording", "没有正在录制的视频", null)
                return
            }
            rtmpCamera.pauseRecord();
          result.success(null)
        } catch (e: CameraAccessException) {
            result.error("pauseVideoRecording", e.message, null)
            return
        } catch (e: IllegalStateException) {
            result.error("pauseVideoRecording", e.message, null)
            return
        }

    }

    fun resumeVideoRecording(result: MethodChannel.Result) {
        try {
            if (!rtmpCamera.isRecording) {
                result.error("resumeVideoRecording", "没有正在录制的视频", null)
                return
            }
            rtmpCamera.resumeRecord()
          result.success(null)
        } catch (e: CameraAccessException) {
            result.error("resumeVideoRecording", e.message, null)
            return
        } catch (e: IllegalStateException) {
            result.error("resumeVideoRecording", e.message, null)
            return
        }

    }

    fun startPreview(cameraNameArg: String? = null): Boolean {
        val targetCamera = if (cameraNameArg.isNullOrEmpty()) {
            cameraName
        } else {
            cameraNameArg
        }
        cameraName = targetCamera

        Log.d("CameraNativeView", "startPreview: $preset camera=$targetCamera")
        if (!isSurfaceCreated) {
            return false
        }
        return try {
            val previewSize = CameraUtils.computeBestPreviewSize(getActivity(), cameraName, preset)
            val size = previewSize["size"] as Size
            rtmpCamera.startPreview(targetCamera, size.width, size.height)
            true
        } catch (e: CameraAccessException) {
            close()
            getActivity()?.runOnUiThread {
                dartMessenger?.send(
                    DartMessenger.EventType.ERROR,
                    "CameraAccessException"
                )
            }
            false
        } catch (e: Exception) {
            Log.e("CameraNativeView", "startPreview failed", e)
            getActivity()?.runOnUiThread {
                dartMessenger?.send(
                    DartMessenger.EventType.ERROR,
                    e.message ?: "startPreview failed"
                )
            }
            false
        }
    }

    private fun restorePreviewAfterSurfaceChange() {
        if (!isSurfaceCreated) {
            return
        }
        // Capture never stopped -- it has just been rendering off-screen. Hand it
        // back the visible view and there is nothing else to restore: no stream
        // to reconnect, no recording to resume, no encoders to prepare.
        if (isRenderingOffScreen) {
            isRenderingOffScreen = false
            try {
                rtmpCamera.replaceView(glView)
                // Covers the session having been stopped while off-screen: the
                // view is handed back but there is no longer a running capture
                // holding the camera open, so without this the preview returns
                // black.
                if (!rtmpCamera.isOnPreview) startPreview(cameraName)
                Log.d("CameraNativeView", "rendering back on-screen")
                return
            } catch (e: Exception) {
                // The session is still live off-screen; only the preview is
                // wrong. Losing the picture on the phone is a great deal better
                // than tearing down a running session to fix it, so this is
                // reported and left alone.
                Log.e("CameraNativeView", "replaceView back to preview failed", e)
                getActivity()?.runOnUiThread {
                    dartMessenger?.send(
                        DartMessenger.EventType.ERROR,
                        "the preview could not be restored; the session is still running"
                    )
                }
                return
            }
        }
        if (resumeStreamAfterSurfaceCreated && lastStreamUrl != null) {
            resumeStreamAfterSurfaceChange()
            return
        }
        if (rtmpCamera.isOnPreview) {
            try {
                rtmpCamera.stopCamera()
            } catch (e: Exception) {
                Log.e("CameraNativeView", "stopCamera before restore failed", e)
            }
        }
        startPreview(cameraName)
    }

    private fun resumeStreamAfterSurfaceChange() {
        val url = lastStreamUrl ?: run {
            resumeStreamAfterSurfaceCreated = false
            isRestoringFromSurfaceDestroy = false
            return
        }
        resumeStreamAfterSurfaceCreated = false
        try {
            if (rtmpCamera.isOnPreview) {
                rtmpCamera.stopCamera()
            }
            val streamingSize = CameraUtils.computeBestPreviewSize(getActivity(), cameraName, preset)
            val size = streamingSize["size"] as Size
            val bitrateRes = lastStreamBitrate ?: customVideoBitrate ?: (streamingSize["bitrate"] as Int)
            // Resuming re-prepares the encoder, so the ceiling has to be re-applied too --
            // the adapter may have stepped down before the surface was destroyed.
            applyBitrateCeiling(bitrateRes)
            rtmpCamera.forceBt709Color(forceBt709Color)
            (rtmpCamera.streamClient as? RtmpStreamClient)?.shouldSendPings(rtmpShouldSendPings)
            // The short-circuit here is load-bearing, not style.
            //
            // A running recording means the encoders are already prepared, and
            // RootEncoder throws IllegalStateException("Encoder already prepared")
            // if prepareAudio/prepareVideo is called again. This used to read
            //
            //     val prepared = prepareAudioEncoder() && prepareVideoEncoder(...)
            //     if (rtmpCamera.isRecording || prepared) { ... }
            //
            // which evaluates the prepare *before* testing isRecording, so with a
            // recording up it threw every time -- before ever reaching startStream.
            // The whole surface-restore path therefore never worked while
            // recording, which is what turned an ordinary surface cycle into a
            // permanently dead stream and a closed camera.
            //
            // Measured on device 2026-08-04: reproduced on the end of a phone
            // call, on waking from a locked screen, and once spontaneously
            // thirteen seconds into a session with nobody touching the phone.
            //
            // startVideoStreaming has always had this the right way round. Keep
            // the two consistent, and do not hoist this out of the `if` again.
            if (rtmpCamera.isRecording ||
                (prepareAudioEncoder() && prepareVideoEncoder(size, bitrateRes))
            ) {
                // Reopen the camera before restarting the stream.
                //
                // Not optional, and the reason is easy to miss: RootEncoder's
                // startStream only calls startEncoders when no recording is
                // running, and startEncoders is the only thing in that path that
                // reopens the camera and re-attaches the encoder's input surface.
                // With a recording up it calls requestKeyFrame instead, so the
                // camera stopCamera() closed above is never reopened.
                //
                // Without this the stream comes back and sends a frozen frame.
                // That is worse than the dead stream it replaces: a still image
                // over a healthy-looking connection is a failure nothing reports,
                // where a dead stream at least shows as one.
                //
                // startPreview is safe here -- it guards on onPreview, which
                // stopCamera() has just cleared, and not on isRecording.
                if (!rtmpCamera.isOnPreview) {
                    startPreview(cameraName)
                }
                Log.d(
                    "CameraNativeView",
                    "resumeStreamAfterSurfaceChange: ${redactStreamUrl(url)}"
                )
                rtmpCamera.startStream(url)
            } else {
                isRestoringFromSurfaceDestroy = false
                getActivity()?.runOnUiThread {
                    dartMessenger?.send(
                        DartMessenger.EventType.RTMP_STOPPED,
                        "Failed to resume stream after background"
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("CameraNativeView", "resumeStreamAfterSurfaceChange failed", e)
            isRestoringFromSurfaceDestroy = false
            getActivity()?.runOnUiThread {
                dartMessenger?.send(
                    DartMessenger.EventType.RTMP_STOPPED,
                    e.message ?: "Failed to resume stream after background"
                )
            }
        }
    }

    fun getStreamStatistics(result: MethodChannel.Result) {
        val ret = hashMapOf<String, Any>()
        // cacheSize is the configured *capacity* of the outbound queue -- a constant
        // for the life of the stream. itemsInCache is how full that queue actually
        // is right now, and is the one that climbs when the network cannot keep up.
        // Reporting only the former makes a congested network look identical to a
        // healthy one, so both are exposed and callers should read them as a pair.
        ret["cacheSize"] = rtmpCamera.streamClient.getCacheSize()
        ret["itemsInCache"] = rtmpCamera.streamClient.getItemsInCache()
        ret["sentAudioFrames"] = rtmpCamera.streamClient.getSentAudioFrames()
        ret["sentVideoFrames"] = rtmpCamera.streamClient.getSentVideoFrames()
        ret["droppedAudioFrames"] = rtmpCamera.streamClient.getDroppedAudioFrames()
        ret["droppedVideoFrames"] = rtmpCamera.streamClient.getDroppedVideoFrames()
        ret["bytesSend"] = rtmpCamera.streamClient.getBytesSend()
        ret["isAudioMuted"] = rtmpCamera.isAudioMuted
        ret["isVideoMuted"] = rtmpCamera.glInterface?.isVideoMuted ?: false
        ret["bitrate"] = rtmpCamera.bitrate
        ret["width"] = rtmpCamera.streamWidth
        ret["height"] = rtmpCamera.streamHeight
        ret["fps"] = fps
        // The only figures here that follow the camera rather than the encoder.
        // Everything above keeps reporting health through a frozen picture --
        // measured 2026-08-04, 135 seconds of still image at a reported 30fps
        // with no dropped frames -- so these are the ones to read when the
        // question is whether anything is being captured.
        ret["largestCameraFrameGapMillis"] = largestCameraFrameGapMs
        ret["cameraStalled"] = cameraStallStartedAtMs != 0L
        ret["cameraEverStalled"] = cameraEverStalled
        ret["totalCameraStalledMillis"] = totalCameraStalledMs +
            if (cameraStallStartedAtMs != 0L) {
                SystemClock.elapsedRealtime() - cameraStallStartedAtMs
            } else {
                0L
            }
        val rtmpSc = rtmpCamera.streamClient as? RtmpStreamClient
        ret["rttMicros"] = rtmpSc?.getRtt() ?: 0
        result.success(ret)
    }

    fun setForceBt709Color(enabled: Boolean?, result: MethodChannel.Result) {
        if (enabled == null) {
            result.error("setForceBt709Color", "enabled is required", null)
            return
        }
        forceBt709Color = enabled
        try {
            rtmpCamera.forceBt709Color(enabled)
            result.success(null)
        } catch (e: Exception) {
            result.error("setForceBt709Color", e.message, null)
        }
    }

    fun setRtmpShouldSendPings(enabled: Boolean?, result: MethodChannel.Result) {
        if (enabled == null) {
            result.error("setRtmpShouldSendPings", "enabled is required", null)
            return
        }
        rtmpShouldSendPings = enabled
        result.success(null)
    }

    override fun getView(): View {
        return glView
    }

    override fun dispose() {
        stallHandler.removeCallbacks(stallWatchdog)
        isSurfaceCreated = false
        resumeStreamAfterSurfaceCreated = false
        isRestoringFromSurfaceDestroy = false
        // Cleared, but note what it does not do: this view is going away, so
        // there is no point handing the renderer back to it. The camera is
        // closed below and the session is over either way.
        isRenderingOffScreen = false
        lastStreamUrl = null
        lastStreamBitrate = null
        if (rtmpCamera.isOnPreview) {
            rtmpCamera.stopCamera()
        }
        activity = null
    }

    /** Activity 在 surfaceDestroyed 后仍有效；若引用丢失则用 glView 的 Context 兜底。 */
    private fun getActivity(): Activity? = activity ?: glView.context as? Activity
}

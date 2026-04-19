package com.uhg0.ar_flutter_plugin_2

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.PixelCopy
import android.view.View
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.Plane
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.SessionPausedException
import io.flutter.FlutterInjector
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.platform.PlatformView
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.scene.PlaneRenderer
import io.github.sceneview.material.setTexture
import io.github.sceneview.texture.ImageTexture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ArView(
    context: Context,
    private val lifecycle: Lifecycle,
    messenger: BinaryMessenger,
    id: Int,
) : PlatformView {

    private val TAG = ArView::class.java.name
    private val viewContext: Context = context
    private var sceneView: ARSceneView
    private val mainScope = CoroutineScope(Dispatchers.Main)
    private val rootLayout: FrameLayout = FrameLayout(context)
    private val sessionChannel: MethodChannel = MethodChannel(messenger, "arsession_$id")
    private val detectedPlanes = mutableSetOf<Plane>()
    private var showAnimatedGuide = true
    private var isSessionPaused = false

    private val onSessionMethodCall =
        MethodChannel.MethodCallHandler { call, result ->
            when (call.method) {
                "init" -> handleInit(call, result)
                "showPlanes" -> handleShowPlanes(call, result)
                "dispose" -> dispose()
                "snapshot" -> handleSnapshot(result)
                "getAnchorPose" -> handleGetAnchorPose(call, result)
                "disableCamera" -> handleDisableCamera(result)
                "enableCamera" -> handleEnableCamera(result)
                "hitTest" -> handleHitTest(call, result)
                else -> result.notImplemented()
            }
        }

    init {
        sceneView = ARSceneView(
            context = viewContext,
            sharedLifecycle = lifecycle,
            sessionConfiguration = { session, config ->
                config.apply {
                    depthMode = Config.DepthMode.DISABLED
                    instantPlacementMode = Config.InstantPlacementMode.DISABLED
                    lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
                    focusMode = Config.FocusMode.AUTO
                    planeFindingMode = Config.PlaneFindingMode.DISABLED
                }
            }
        )

        rootLayout.addView(sceneView)
        sessionChannel.setMethodCallHandler(onSessionMethodCall)
    }

    private fun handleInit(call: MethodCall, result: MethodChannel.Result) {
        try {
            val argShowAnimatedGuide = call.argument<Boolean>("showAnimatedGuide") ?: true
            val argPlaneDetectionConfig: Int? = call.argument<Int>("planeDetectionConfig")
            val argShowPlanes = call.argument<Boolean>("showPlanes") ?: true
            val customPlaneTexturePath = call.argument<String>("customPlaneTexturePath")

            sceneView.session?.let { session ->
                session.configure(session.config.apply {
                    depthMode = if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                        Config.DepthMode.AUTOMATIC
                    } else Config.DepthMode.DISABLED

                    planeFindingMode = when (argPlaneDetectionConfig) {
                        1 -> Config.PlaneFindingMode.HORIZONTAL
                        2 -> Config.PlaneFindingMode.VERTICAL
                        3 -> Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                        else -> Config.PlaneFindingMode.DISABLED
                    }
                })
            }

            sceneView.apply {
                planeRenderer.isEnabled = argShowPlanes
                planeRenderer.isVisible = argShowPlanes
                planeRenderer.planeRendererMode = PlaneRenderer.PlaneRendererMode.RENDER_ALL

                onTrackingFailureChanged = { reason ->
                    mainScope.launch {
                        sessionChannel.invokeMethod("onTrackingFailure", reason?.name)
                    }
                }

                onFrame = { frameTime ->
                    // NOTE: SceneView already calls session.update() before firing this callback.
                    // We access the current AR frame directly via arFrame — no double-update needed.
                    try {
                        if (!isSessionPaused) {
                            val frame = arFrame ?: return@onFrame
                            if (showAnimatedGuide) {
                                frame.getUpdatedTrackables(Plane::class.java).forEach { plane ->
                                    if (plane.trackingState == TrackingState.TRACKING) {
                                        rootLayout.findViewWithTag<View>("hand_motion_layout")?.let { handMotionLayout ->
                                            rootLayout.removeView(handMotionLayout)
                                            showAnimatedGuide = false
                                        }
                                    }
                                }
                            }

                            frame.getUpdatedTrackables(Plane::class.java).forEach { plane ->
                                if (plane.trackingState == TrackingState.TRACKING &&
                                    !detectedPlanes.contains(plane)
                                ) {
                                    detectedPlanes.add(plane)
                                    mainScope.launch {
                                        sessionChannel.invokeMethod("onPlaneDetected", detectedPlanes.size)
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        if (e is SessionPausedException) {
                            Log.d(TAG, "Session paused, skipping frame update")
                        } else {
                            Log.e(TAG, "Error during frame update", e)
                        }
                    }
                }

                if (argShowAnimatedGuide && showAnimatedGuide) {
                    val handMotionLayout =
                        LayoutInflater.from(context)
                            .inflate(R.layout.sceneform_hand_layout, rootLayout, false)
                            .apply { tag = "hand_motion_layout" }
                    rootLayout.addView(handMotionLayout)
                }

                if (customPlaneTexturePath != null) {
                    try {
                        val loader = FlutterInjector.instance().flutterLoader()
                        val assetKey = loader.getLookupKeyForAsset(customPlaneTexturePath)
                        val customPlaneTexture = ImageTexture.Builder()
                            .bitmap(materialLoader.assets, assetKey)
                            .build(engine)
                        planeRenderer.planeMaterial.defaultInstance.setTexture(
                            PlaneRenderer.MATERIAL_TEXTURE,
                            customPlaneTexture
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Error applying custom texture: ${e.message}")
                    }
                }
            }
            result.success(null)
        } catch (e: Exception) {
            result.error("AR_VIEW_ERROR", e.message, null)
        }
    }

    private fun handleHitTest(call: MethodCall, result: MethodChannel.Result) {
        val x = call.argument<Double>("x") ?: 0.0
        val y = call.argument<Double>("y") ?: 0.0

        // Use the already-updated arFrame from SceneView instead of calling session.update() again
        val frame = sceneView.arFrame
        val hitResults = frame?.hitTest(x.toFloat(), y.toFloat())
        val results = mutableListOf<Map<String, Any>>()

        hitResults?.forEach { hitResult ->
            val pose = hitResult.hitPose
            val matrix = FloatArray(16)
            pose.toMatrix(matrix, 0)
            results.add(mapOf("worldTransform" to matrix.map { it.toDouble() }))
        }

        result.success(results)
    }

    private fun handleGetAnchorPose(call: MethodCall, result: MethodChannel.Result) {
        try {
            val anchorId = call.argument<String>("anchorId")
            if (anchorId == null) {
                result.error("INVALID_ARGUMENT", "Anchor ID is required", null)
                return
            }

            val anchor: Anchor? = sceneView.session?.allAnchors?.find {
                it.cloudAnchorId == anchorId || it.hashCode().toString() == anchorId
            }

            if (anchor != null) {
                val pose = anchor.pose
                val poseData = mapOf(
                    "position" to mapOf(
                        "x" to pose.tx().toDouble(),
                        "y" to pose.ty().toDouble(),
                        "z" to pose.tz().toDouble()
                    ),
                    "rotation" to mapOf(
                        "x" to pose.qx().toDouble(),
                        "y" to pose.qy().toDouble(),
                        "z" to pose.qz().toDouble(),
                        "w" to pose.qw().toDouble()
                    )
                )
                result.success(poseData)
            } else {
                result.error("ANCHOR_NOT_FOUND", "Anchor with ID $anchorId not found", null)
            }
        } catch (e: Exception) {
            result.error("ANCHOR_POSE_ERROR", e.message, null)
        }
    }

    private fun handleSnapshot(result: MethodChannel.Result) {
        mainScope.launch {
            try {
                val bitmap = Bitmap.createBitmap(sceneView.width, sceneView.height, Bitmap.Config.ARGB_8888)
                PixelCopy.request(sceneView, bitmap, { copyResult ->
                    if (copyResult == PixelCopy.SUCCESS) {
                        val byteStream = java.io.ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, byteStream)
                        result.success(byteStream.toByteArray())
                    } else {
                        result.error("SNAPSHOT_ERROR", "Failed to capture snapshot", null)
                    }
                }, Handler(Looper.getMainLooper()))
            } catch (e: Exception) {
                result.error("SNAPSHOT_ERROR", e.message, null)
            }
        }
    }

    private fun handleShowPlanes(call: MethodCall, result: MethodChannel.Result) {
        try {
            val showPlanes = call.argument<Boolean>("showPlanes") ?: false
            sceneView.planeRenderer.isEnabled = showPlanes
            result.success(null)
        } catch (e: Exception) {
            result.error("SHOW_PLANES_ERROR", e.message, null)
        }
    }

    private fun handleDisableCamera(result: MethodChannel.Result) {
        try {
            isSessionPaused = true
            sceneView.session?.pause()
            result.success(null)
        } catch (e: Exception) {
            result.error("DISABLE_CAMERA_ERROR", e.message, null)
        }
    }

    private fun handleEnableCamera(result: MethodChannel.Result) {
        try {
            isSessionPaused = false
            sceneView.session?.resume()
            result.success(null)
        } catch (e: Exception) {
            result.error("ENABLE_CAMERA_ERROR", e.message, null)
        }
    }

    override fun getView(): View = rootLayout

    override fun dispose() {
        Log.i(TAG, "dispose")
        sessionChannel.setMethodCallHandler(null)
        detectedPlanes.clear()
        sceneView.destroy()
    }
}

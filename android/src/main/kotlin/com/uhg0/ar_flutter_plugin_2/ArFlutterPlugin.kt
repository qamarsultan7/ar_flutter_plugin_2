package com.uhg0.ar_flutter_plugin_2

import android.app.Activity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.google.ar.core.ArCoreApk
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodChannel

class ArFlutterPlugin: FlutterPlugin, ActivityAware {
    private var activity: Activity? = null
    private var lifecycle: Lifecycle? = null
    private var flutterPluginBinding: FlutterPlugin.FlutterPluginBinding? = null
    private var arSupportChannel: MethodChannel? = null

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        flutterPluginBinding = binding

        // Register AR support check channel
        arSupportChannel = MethodChannel(binding.binaryMessenger, "ar_flutter_plugin_2_ar_support")
        arSupportChannel?.setMethodCallHandler { call, result ->
            if (call.method == "isArSupported") {
                val availability = ArCoreApk.getInstance().checkAvailability(binding.applicationContext)
                val supported = when (availability) {
                    ArCoreApk.Availability.SUPPORTED_INSTALLED,
                    ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD,
                    ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED -> true
                    else -> false
                }
                result.success(supported)
            } else {
                result.notImplemented()
            }
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        arSupportChannel?.setMethodCallHandler(null)
        arSupportChannel = null
        flutterPluginBinding = null
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
        lifecycle = (activity as LifecycleOwner).lifecycle
        
        // Enregistrer la factory une fois que nous avons l'activité et le lifecycle
        flutterPluginBinding?.let { flutterBinding ->
            flutterBinding.platformViewRegistry.registerViewFactory(
                "ar_flutter_plugin_2",
                ArViewFactory(
                    messenger = flutterBinding.binaryMessenger,
                    activity = activity!!,
                    lifecycle = lifecycle!!
                )
            )
        }
    }

    override fun onDetachedFromActivityForConfigChanges() {
        activity = null
        lifecycle = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activity = binding.activity
        lifecycle = (activity as LifecycleOwner).lifecycle
        
        // Réenregistrer la factory après les changements de configuration
        flutterPluginBinding?.let { flutterBinding ->
            flutterBinding.platformViewRegistry.registerViewFactory(
                "ar_flutter_plugin_2",
                ArViewFactory(
                    messenger = flutterBinding.binaryMessenger,
                    activity = activity!!,
                    lifecycle = lifecycle!!
                )
            )
        }
    }

    override fun onDetachedFromActivity() {
        activity = null
        lifecycle = null
    }
}

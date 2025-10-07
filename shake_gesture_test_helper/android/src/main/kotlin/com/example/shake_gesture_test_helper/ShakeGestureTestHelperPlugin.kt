package com.example.shake_gesture_test_helper

import android.hardware.SensorManager
import androidx.annotation.NonNull

import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import dev.fluttercommunity.shake_gesture_android.ShakeDetector

import dev.fluttercommunity.shake_gesture_android.ShakeGesturePlugin

/** ShakeGestureTestHelperPlugin */
class ShakeGestureTestHelperPlugin : FlutterPlugin, MethodCallHandler {
    /// The MethodChannel that will the communication between Flutter and native Android
    ///
    /// This local reference serves to register the plugin with the Flutter Engine and unregister it
    /// when the Flutter Engine is detached from the Activity
    private lateinit var channel: MethodChannel

    override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "shake_gesture_test_helper")
        channel.setMethodCallHandler(this)
    }

    override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
        if (call.method == "shake") {
            shake()
            result.success(null)
        } else {
            result.notImplemented()
        }
    }

    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
    }

    private fun shake() {
        ShakeGesturePlugin.shakeDetector?.let { shakeDetector ->
            // Simulate a shake by adding multiple accelerating samples over time
            // The new algorithm requires 75% of samples in a 0.25-0.5s window to be accelerating
            val startTime = System.nanoTime()
            val intervalNs = 20_000_000L // 20ms between samples

            // Clear any previous samples
            shakeDetector.clear()

            // Add 20 samples over 400ms, all with high acceleration
            // This ensures > 75% are accelerating to trigger shake detection
            for (i in 0..19) {
                val timestamp = startTime + (i * intervalNs)
                // Alternate direction to simulate shaking motion
                val ax = if (i % 2 == 0) 15f else -15f
                val ay = SensorManager.GRAVITY_EARTH
                val az = 0f

                shakeDetector.addAccelerometerEvent(ax, ay, az, timestamp)
            }
        }
    }
}

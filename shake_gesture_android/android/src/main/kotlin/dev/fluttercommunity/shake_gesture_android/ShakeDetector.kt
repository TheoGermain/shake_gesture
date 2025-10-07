package dev.fluttercommunity.shake_gesture_android

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log

/**
 * Detects phone shaking. If more than 75% of the samples taken in the past 0.5s are
 * accelerating, the device is a) shaking, or b) free falling 1.84m (h =
 * 1/2*g*t^2*3/4).
 *
 * @author Bob Lee (bob@squareup.com)
 * @author Eric Burke (eric@squareup.com)
 */
class ShakeDetector(context: Context, private val listener: OnShakeListener) : SensorEventListener {

    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null

    init {
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    }

    interface OnShakeListener {
        fun onShake()
    }

    fun start() {
        sensorManager?.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL)
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not used
    }

    /**
     * When the magnitude of total acceleration exceeds this
     * value, the phone is accelerating.
     */
    private val customShakeForce = context.packageManager.getApplicationInfo(
        context.packageName,
        PackageManager.GET_META_DATA
    ).metaData.get("dev.fluttercommunity.shake_gesture_android.SHAKE_FORCE")

    private val SHAKE_FORCE: Float = when (customShakeForce) {
        is Int -> customShakeForce.toFloat()
        is Float -> customShakeForce
        else -> 6f
    }

    private val ACCELERATION_THRESHOLD_SQUARED =
        SensorManager.GRAVITY_EARTH * SensorManager.GRAVITY_EARTH + SHAKE_FORCE * SHAKE_FORCE

    private val queue = SampleQueue()

    init {
        Log.v("ShakeDetector", "Required shake force is $SHAKE_FORCE")
    }

    override fun onSensorChanged(sensorEvent: SensorEvent) {
        val ax = sensorEvent.values[0]
        val ay = sensorEvent.values[1]
        val az = sensorEvent.values[2]
        val timestamp = sensorEvent.timestamp

        addAccelerometerEvent(ax, ay, az, timestamp)
    }

    /**
     * Add an accelerometer event to this detectors queue to sample and
     * reasonably detect shaking events
     */
    fun addAccelerometerEvent(ax: Float, ay: Float, az: Float, timestamp: Long) {
        val accelerating = isAccelerating(ax, ay, az)
        queue.add(timestamp, accelerating)
        if (queue.isShaking) {
            queue.clear()
            listener.onShake()
        }
    }

    fun clear() {
        queue.clear()
    }

    /** Returns true if the device is currently accelerating.  */
    private fun isAccelerating(ax: Float, ay: Float, az: Float): Boolean {
        // Instead of comparing magnitude to ACCELERATION_THRESHOLD,
        // compare their squares. This is equivalent and doesn't need the
        // actual magnitude, which would be computed using (expensive) Math.sqrt().
        val magnitudeSquared = (ax * ax + ay * ay + az * az).toDouble()
        return magnitudeSquared > ACCELERATION_THRESHOLD_SQUARED
    }

    /** Queue of samples. Keeps a running average.  */
    internal class SampleQueue {
        private val pool = SamplePool()

        private var oldest: Sample? = null
        private var newest: Sample? = null
        private var sampleCount = 0
        private var acceleratingCount = 0

        /**
         * Adds a sample.
         *
         * @param timestamp    in nanoseconds of sample
         * @param accelerating true if > acceleration threshold.
         */
        fun add(timestamp: Long, accelerating: Boolean) {
            // Purge samples that proceed window.
            purge(timestamp - MAX_WINDOW_SIZE)

            // Add the sample to the queue.
            val added = pool.acquire()
            added.timestamp = timestamp
            added.accelerating = accelerating
            added.next = null
            if (newest != null) {
                newest!!.next = added
            }
            newest = added
            if (oldest == null) {
                oldest = added
            }

            // Update running average.
            sampleCount++
            if (accelerating) {
                acceleratingCount++
            }
        }

        /** Removes all samples from this queue.  */
        fun clear() {
            while (oldest != null) {
                val removed: Sample = oldest!!
                oldest = removed.next
                pool.release(removed)
            }
            newest = null
            sampleCount = 0
            acceleratingCount = 0
        }

        /** Purges samples with timestamps older than cutoff.  */
        fun purge(cutoff: Long) {
            while (sampleCount >= MIN_QUEUE_SIZE && oldest != null && cutoff - oldest!!.timestamp > 0) {
                // Remove sample.
                val removed: Sample = oldest!!
                if (removed.accelerating) {
                    acceleratingCount--
                }
                sampleCount--

                oldest = removed.next
                if (oldest == null) {
                    newest = null
                }
                pool.release(removed)
            }
        }

        val isShaking: Boolean
            /**
             * Returns true if we have enough samples and more than 3/4 of those samples
             * are accelerating.
             */
            get() = newest != null && oldest != null &&
                newest!!.timestamp - oldest!!.timestamp >= MIN_WINDOW_SIZE &&
                acceleratingCount >= (sampleCount shr 1) + (sampleCount shr 2)

        companion object {
            /** Window size in ns. Used to compute the average.  */
            private const val MAX_WINDOW_SIZE: Long = 500000000 // 0.5s
            private const val MIN_WINDOW_SIZE = MAX_WINDOW_SIZE shr 1 // 0.25s

            /**
             * Ensure the queue size never falls below this size, even if the device
             * fails to deliver this many events during the time window. The LG Ally
             * is one such device.
             */
            private const val MIN_QUEUE_SIZE = 4
        }
    }

    /** An accelerometer sample.  */
    internal class Sample {
        /** Time sample was taken.  */
        var timestamp: Long = 0

        /** If acceleration > acceleration threshold.  */
        var accelerating: Boolean = false

        /** Next sample in the queue or pool.  */
        var next: Sample? = null
    }

    /** Pools samples. Avoids garbage collection.  */
    internal class SamplePool {
        private var head: Sample? = null

        /** Acquires a sample from the pool.  */
        fun acquire(): Sample {
            var acquired = head
            if (acquired == null) {
                acquired = Sample()
            } else {
                // Remove instance from pool.
                head = acquired.next
            }
            return acquired
        }

        /** Returns a sample to the pool.  */
        fun release(sample: Sample) {
            sample.next = head
            head = sample
        }
    }
}

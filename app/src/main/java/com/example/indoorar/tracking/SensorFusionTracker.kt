package com.example.indoorar.tracking

import android.content.Context
import android.hardware.*
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlin.math.*

class SensorFusionTracker(
    private val context: Context,
    private val mapNorthDegrees: Float = 0f,
    private val stepLengthMeters: Float = 0.7f,
    private val onPosition: (x: Float, z: Float, headingRad: Float) -> Unit,
    private val mapMatch: ((x: Float, z: Float) -> Pair<Float, Float>)? = null,
    private val reanchorCheck: ((x: Float, z: Float) -> Pair<Boolean, Pair<Float, Float>?>)? = null,
    private val onStep: ((totalSteps: Int) -> Unit)? = null
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationVector: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val stepDetector: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
    private val stepCounter: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    private val linAccel: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magnetometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    private var headingRad: Float = 0f

    private var currX: Float = 0f
    private var currZ: Float = 0f

    private var started = false

    // Step Counter state
    private var lastStepCount: Float? = null
    private var anyStepSensorAvailable = false
    private var stepSensorSeen = false

    // Accelerometer step detection state (dynamic threshold)
    private var accMean = 0f
    private var accVar = 0f
    private var accInit = false
    private var wasAbove = false
    private var lastStepTimestampNs: Long = 0L

    // Tunables for accelerometer/linear-accel detector
    private val alphaMean = 0.15f
    private val alphaVar = 0.15f
    private val kStd = 0.85f
    private val hysteresisFactor = 0.35f
    private val minStepIntervalNs = 200_000_000L // 240 ms
    // Sub-step aggregation: move in fixed chunks (default 1.0 m)
    private val subStepMeters = 0.25f
    private var distanceBufferMeters = 0f

    // Fallback heading using accel + magnetometer
    private val lastAccel = FloatArray(3)
    private val lastMag = FloatArray(3)
    private var haveAccel = false
    private var haveMag = false

    // Motion fallback state
    private val mainHandler = Handler(Looper.getMainLooper())
    private var motionScore = 0f
    private var lastStepWallMs = 0L
    private var motionTickRunning = false

    // Fallback tunables
    private val motionAlpha = 0.1f           // EWMA smoothing for motion energy
    private val motionThreshold = 0.2f       // threshold to consider moving (more sensitive)
    private val motionTickMs = 500L          // faster tick
    private val motionNoStepMs = 800L        // fallback sooner
    private val motionAdvanceMeters = 0.4f   // advance per tick when moving (more visible)

    // Total steps counter
    private var totalSteps = 0

    // Heading dispatch state
    private var lastHeadingDispatch = 0f
    private var lastHeadingDispatchTime = 0L
    private val headingMinDelta = 0.01f // antes 0.035f (~2 graus) agora mais sensível
    private val headingMaxIntervalMs = 300L

    fun start(initialX: Float, initialZ: Float) {
        currX = initialX
        currZ = initialZ
        distanceBufferMeters = 0f
        if (started) return
        started = true
        rotationVector?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        anyStepSensorAvailable = (stepDetector != null || stepCounter != null)
        stepDetector?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        stepCounter?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        // Prefer linear acceleration if available; also register accelerometer as a backup
        linAccel?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        accelerometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        magnetometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        startMotionFallback()
        onPosition(currX, currZ, headingRad)
    }

    fun stop() {
        if (!started) return
        started = false
        sensorManager.unregisterListener(this)
        lastStepCount = null
        stepSensorSeen = false
        accInit = false
        accMean = 0f
        accVar = 0f
        wasAbove = false
        lastStepTimestampNs = 0L
        distanceBufferMeters = 0f
        haveAccel = false
        haveMag = false
        stopMotionFallback()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> updateHeadingFromRotationVector(event)
            Sensor.TYPE_STEP_DETECTOR -> {
                stepSensorSeen = true
                if (event.values.isNotEmpty() && event.values[0] > 0f) onStepDetected()
            }
            Sensor.TYPE_STEP_COUNTER -> {
                stepSensorSeen = true
                handleStepCounter(event)
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                if (!anyStepSensorAvailable || !stepSensorSeen) handleAccelMagnitude(event)
            }
            Sensor.TYPE_ACCELEROMETER -> {
                // Capture for fallback heading and optional step detection
                System.arraycopy(event.values, 0, lastAccel, 0, 3)
                haveAccel = true
                if (rotationVector == null && haveMag) updateHeadingFromAccelMag()
                // Always allow accelerometer-based fallback if step sensors not seen yet
                if (!anyStepSensorAvailable || !stepSensorSeen) handleAccelMagnitude(event)
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(event.values, 0, lastMag, 0, 3)
                haveMag = true
                if (rotationVector == null && haveAccel) updateHeadingFromAccelMag()
            }
        }
    }

    private fun handleStepCounter(event: SensorEvent) {
        if (event.values.isEmpty()) return
        val count = event.values[0]
        val prev = lastStepCount
        if (prev == null) {
            lastStepCount = count
            return
        }
        val delta = (count - prev).toInt()
        if (delta > 0) {
            repeat(delta) { onStepDetected() }
            lastStepCount = count
        }
    }

    // Use either linear acceleration magnitude or gravity-removed acceleration magnitude
    private fun handleAccelMagnitude(event: SensorEvent) {
        val mag = if (event.sensor.type == Sensor.TYPE_LINEAR_ACCELERATION) {
            val x = event.values[0]; val y = event.values[1]; val z = event.values[2]
            sqrt(x*x + y*y + z*z)
        } else {
            // Raw accelerometer: estimate gravity with a stronger low-pass, then compute linear component
            val ax = event.values[0]; val ay = event.values[1]; val az = event.values[2]
            val gAlpha = 0.9f
            if (!gravityState.initialized) {
                gravityState.gx = ax; gravityState.gy = ay; gravityState.gz = az
                gravityState.initialized = true
            } else {
                gravityState.gx = gAlpha * gravityState.gx + (1 - gAlpha) * ax
                gravityState.gy = gAlpha * gravityState.gy + (1 - gAlpha) * ay
                gravityState.gz = gAlpha * gravityState.gz + (1 - gAlpha) * az
            }
            val lx = ax - gravityState.gx
            val ly = ay - gravityState.gy
            val lz = az - gravityState.gz
            sqrt(lx*lx + ly*ly + lz*lz)
        }

        // Begin motion energy update
        motionScore = motionAlpha * mag + (1 - motionAlpha) * motionScore

        if (!accInit) {
            accMean = mag
            accVar = 0f
            accInit = true
            return
        }
        // EWMA mean and variance update
        val diff = mag - accMean
        accMean += alphaMean * diff
        accVar = alphaVar * (diff*diff) + (1 - alphaVar) * accVar
        val std = max(0.05f, sqrt(accVar))
        val upThresh = accMean + kStd * std
        val downThresh = accMean + hysteresisFactor * std

        val now = event.timestamp
        if (mag > upThresh && !wasAbove) {
            if (now - lastStepTimestampNs >= minStepIntervalNs) {
                onStepDetected()
                lastStepTimestampNs = now
            }
            wasAbove = true
        } else if (mag < downThresh) {
            wasAbove = false
        }
    }

    private object gravityState { var gx=0f; var gy=0f; var gz=0f; var initialized=false }

    private fun updateHeadingFromRotationVector(event: SensorEvent) {
        val rotMat = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(rotMat, event.values)
        val orientation = FloatArray(3)
        SensorManager.getOrientation(rotMat, orientation)
        val azimuthRad = orientation[0]
        val mapNorthRad = Math.toRadians(mapNorthDegrees.toDouble()).toFloat()
        headingRad = azimuthRad - mapNorthRad
        maybeDispatchHeadingOnly()
    }

    private fun updateHeadingFromAccelMag() {
        val R = FloatArray(9)
        val I = FloatArray(9)
        if (SensorManager.getRotationMatrix(R, I, lastAccel, lastMag)) {
            val orientation = FloatArray(3)
            SensorManager.getOrientation(R, orientation)
            val azimuthRad = orientation[0]
            val mapNorthRad = Math.toRadians(mapNorthDegrees.toDouble()).toFloat()
            headingRad = azimuthRad - mapNorthRad
            maybeDispatchHeadingOnly()
        }
    }

    private fun maybeDispatchHeadingOnly() {
        if (!started) return
        val now = SystemClock.uptimeMillis()
        val d = kotlin.math.abs(angularDiff(headingRad, lastHeadingDispatch))
        if (d >= headingMinDelta || (now - lastHeadingDispatchTime) >= headingMaxIntervalMs) {
            lastHeadingDispatch = headingRad
            lastHeadingDispatchTime = now
            onPosition(currX, currZ, headingRad)
        }
    }

    private fun angularDiff(a: Float, b: Float): Float {
        var d = (a - b + Math.PI * 3).toFloat() % (2 * Math.PI).toFloat() - Math.PI.toFloat()
        if (d < -Math.PI) d += (2 * Math.PI).toFloat()
        return d
    }

    private fun onStepDetected() {
        totalSteps++
        onStep?.invoke(totalSteps)
        distanceBufferMeters += stepLengthMeters
        lastStepWallMs = SystemClock.uptimeMillis()
        while (distanceBufferMeters >= subStepMeters) {
            advanceBy(subStepMeters)
            distanceBufferMeters -= subStepMeters
        }
    }

    private fun advanceBy(distance: Float) {
        val dx = distance * sin(headingRad)
        val dz = distance * cos(headingRad)
        var nx = currX + dx
        var nz = currZ + dz

        mapMatch?.let { mm ->
            val snapped = mm(nx, nz)
            nx = snapped.first
            nz = snapped.second
        }

        reanchorCheck?.let { rc ->
            val (should, anchor) = rc(nx, nz)
            if (should && anchor != null) {
                nx = anchor.first
                nz = anchor.second
            }
        }

        currX = nx
        currZ = nz
        onPosition(currX, currZ, headingRad)
    }

    private fun startMotionFallback() {
        if (motionTickRunning) return
        motionTickRunning = true
        mainHandler.post(object : Runnable {
            override fun run() {
                if (!motionTickRunning) return
                val now = SystemClock.uptimeMillis()
                val noStepForLong = (now - lastStepWallMs) > motionNoStepMs
                val shouldAdvance = noStepForLong && motionScore > motionThreshold
                if (shouldAdvance) {
                    advanceBy(motionAdvanceMeters)
                }
                mainHandler.postDelayed(this, motionTickMs)
            }
        })
    }

    private fun stopMotionFallback() {
        motionTickRunning = false
        mainHandler.removeCallbacksAndMessages(null)
    }
}
package com.fieldpass.student.capture

import com.fieldpass.student.core.model.HeadTurn
import kotlin.math.abs

enum class LivenessStep { LOOK_STRAIGHT, BLINK, TURN, DONE }

/** Which photo the camera must take right now. */
enum class LivenessShot { FRONT, TURN }

/** One camera frame as the face detector saw it. Yaw in degrees; eye values 0..1, null when the detector could not tell. */
data class FaceReading(
    val faces: Int,
    val yawDeg: Float = 0f,
    val leftEyeOpen: Float? = null,
    val rightEyeOpen: Float? = null,
)

data class LivenessState(
    val step: LivenessStep = LivenessStep.LOOK_STRAIGHT,
    val steadyFrames: Int = 0,
    val eyesClosedSeen: Boolean = false,
    val missedFrames: Int = 0,
)

data class LivenessUpdate(val state: LivenessState, val shot: LivenessShot? = null)

/**
 * The on-phone part of the head-turn check: look straight (photo A), blink, turn as the server asked (photo B).
 * Pure rules, one frame in, one state out. The server re-checks both photos; this only guides the student.
 */
object LivenessGuide {
    const val FRONT_MAX_YAW_DEG = 8f
    /** Stricter than the server's LIVENESS_TURN_MIN_OFFSET so a photo taken here also passes there. */
    const val TURN_MIN_YAW_DEG = 30f
    const val EYE_OPEN_MIN = 0.6f
    const val EYE_CLOSED_MAX = 0.3f
    const val STEADY_FRAMES_FRONT = 3
    const val STEADY_FRAMES_TURN = 2
    const val MISSED_FRAMES_RESTART = 10

    /** +1 when the detector reports a positive yaw for a student turning to their own left. Checked on a real phone. */
    const val YAW_SIGN_FOR_LEFT = 1f

    fun next(state: LivenessState, reading: FaceReading, challenge: HeadTurn): LivenessUpdate {
        if (state.step == LivenessStep.DONE) return LivenessUpdate(state)
        if (reading.faces != 1) return LivenessUpdate(onMissed(state))
        val seen = state.copy(missedFrames = 0)
        return when (seen.step) {
            LivenessStep.LOOK_STRAIGHT -> lookStraight(seen, reading)
            LivenessStep.BLINK -> LivenessUpdate(blink(seen, reading))
            LivenessStep.TURN -> turn(seen, reading, challenge)
            LivenessStep.DONE -> LivenessUpdate(seen)
        }
    }

    private fun onMissed(state: LivenessState): LivenessState {
        if (state.step == LivenessStep.LOOK_STRAIGHT) return LivenessState()
        val missed = state.missedFrames + 1
        // The face was gone long enough for someone else to step in: photo A no longer counts.
        if (missed >= MISSED_FRAMES_RESTART) return LivenessState()
        return state.copy(steadyFrames = 0, missedFrames = missed)
    }

    private fun lookStraight(state: LivenessState, reading: FaceReading): LivenessUpdate {
        val ok = abs(reading.yawDeg) <= FRONT_MAX_YAW_DEG && eyesOpen(reading)
        val steady = if (ok) state.steadyFrames + 1 else 0
        if (steady >= STEADY_FRAMES_FRONT) return LivenessUpdate(LivenessState(step = LivenessStep.BLINK), LivenessShot.FRONT)
        return LivenessUpdate(state.copy(steadyFrames = steady))
    }

    private fun blink(state: LivenessState, reading: FaceReading): LivenessState = when {
        eyesClosed(reading) -> state.copy(eyesClosedSeen = true)
        state.eyesClosedSeen && eyesOpen(reading) -> LivenessState(step = LivenessStep.TURN)
        else -> state
    }

    private fun turn(state: LivenessState, reading: FaceReading, challenge: HeadTurn): LivenessUpdate {
        val wanted = if (challenge == HeadTurn.LEFT) YAW_SIGN_FOR_LEFT else -YAW_SIGN_FOR_LEFT
        val steady = if (reading.yawDeg * wanted >= TURN_MIN_YAW_DEG) state.steadyFrames + 1 else 0
        if (steady >= STEADY_FRAMES_TURN) return LivenessUpdate(LivenessState(step = LivenessStep.DONE), LivenessShot.TURN)
        return LivenessUpdate(state.copy(steadyFrames = steady))
    }

    private fun eyesOpen(reading: FaceReading): Boolean =
        (reading.leftEyeOpen ?: 0f) >= EYE_OPEN_MIN && (reading.rightEyeOpen ?: 0f) >= EYE_OPEN_MIN

    private fun eyesClosed(reading: FaceReading): Boolean =
        (reading.leftEyeOpen ?: 1f) <= EYE_CLOSED_MAX && (reading.rightEyeOpen ?: 1f) <= EYE_CLOSED_MAX
}

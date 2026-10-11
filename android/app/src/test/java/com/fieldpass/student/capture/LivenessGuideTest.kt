package com.fieldpass.student.capture

import com.fieldpass.student.core.model.HeadTurn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LivenessGuideTest {
    private val straight = FaceReading(faces = 1, yawDeg = 2f, leftEyeOpen = 0.9f, rightEyeOpen = 0.9f)
    private val eyesShut = straight.copy(leftEyeOpen = 0.1f, rightEyeOpen = 0.1f)
    private val turnedLeft = straight.copy(yawDeg = 35f * LivenessGuide.YAW_SIGN_FOR_LEFT)
    private val turnedRight = straight.copy(yawDeg = -35f * LivenessGuide.YAW_SIGN_FOR_LEFT)
    private val noFace = FaceReading(faces = 0)

    /** Feeds the readings one after another and returns the last update. */
    private fun feed(start: LivenessState, challenge: HeadTurn, vararg readings: FaceReading): LivenessUpdate =
        readings.fold(LivenessUpdate(start)) { update, reading -> LivenessGuide.next(update.state, reading, challenge) }

    private val afterFront = LivenessState(step = LivenessStep.BLINK)
    private val atTurn = LivenessState(step = LivenessStep.TURN)

    @Test
    fun `three steady straight frames take the front photo and ask for a blink`() {
        val update = feed(LivenessState(), HeadTurn.LEFT, straight, straight, straight)
        assertEquals(LivenessShot.FRONT, update.shot)
        assertEquals(LivenessStep.BLINK, update.state.step)
    }

    @Test
    fun `two straight frames are not enough`() {
        val update = feed(LivenessState(), HeadTurn.LEFT, straight, straight)
        assertNull(update.shot)
        assertEquals(LivenessStep.LOOK_STRAIGHT, update.state.step)
    }

    @Test
    fun `a turned head never counts as looking straight`() {
        val update = feed(LivenessState(), HeadTurn.LEFT, turnedLeft, turnedLeft, turnedLeft, turnedLeft)
        assertNull(update.shot)
        assertEquals(LivenessStep.LOOK_STRAIGHT, update.state.step)
    }

    @Test
    fun `closed eyes restart the steady count`() {
        val update = feed(LivenessState(), HeadTurn.LEFT, straight, straight, eyesShut, straight, straight)
        assertNull(update.shot)
        assertEquals(2, update.state.steadyFrames)
    }

    @Test
    fun `unknown eye values do not count as open`() {
        val unknown = straight.copy(leftEyeOpen = null, rightEyeOpen = null)
        val update = feed(LivenessState(), HeadTurn.LEFT, unknown, unknown, unknown)
        assertNull(update.shot)
    }

    @Test
    fun `eyes shut then open again is a blink`() {
        val update = feed(afterFront, HeadTurn.LEFT, straight, eyesShut, straight)
        assertEquals(LivenessStep.TURN, update.state.step)
        assertNull(update.shot)
    }

    @Test
    fun `open eyes alone are not a blink`() {
        val update = feed(afterFront, HeadTurn.LEFT, straight, straight, straight)
        assertEquals(LivenessStep.BLINK, update.state.step)
    }

    @Test
    fun `turning the asked way for two frames takes the turn photo`() {
        val update = feed(atTurn, HeadTurn.LEFT, turnedLeft, turnedLeft)
        assertEquals(LivenessShot.TURN, update.shot)
        assertEquals(LivenessStep.DONE, update.state.step)
    }

    @Test
    fun `turning the wrong way takes no photo`() {
        val update = feed(atTurn, HeadTurn.LEFT, turnedRight, turnedRight, turnedRight)
        assertNull(update.shot)
        assertEquals(LivenessStep.TURN, update.state.step)
    }

    @Test
    fun `a right challenge wants the opposite turn`() {
        assertEquals(LivenessShot.TURN, feed(atTurn, HeadTurn.RIGHT, turnedRight, turnedRight).shot)
        assertNull(feed(atTurn, HeadTurn.RIGHT, turnedLeft, turnedLeft).shot)
    }

    @Test
    fun `a small turn is not enough`() {
        val slight = straight.copy(yawDeg = 15f * LivenessGuide.YAW_SIGN_FOR_LEFT)
        assertNull(feed(atTurn, HeadTurn.LEFT, slight, slight, slight).shot)
    }

    @Test
    fun `losing the face for ten frames starts again from the front photo`() {
        val update = feed(atTurn, HeadTurn.LEFT, *Array(LivenessGuide.MISSED_FRAMES_RESTART) { noFace })
        assertEquals(LivenessState(), update.state)
    }

    @Test
    fun `a short loss of the face does not start again`() {
        val update = feed(atTurn, HeadTurn.LEFT, noFace, noFace, noFace, turnedLeft, turnedLeft)
        assertEquals(LivenessShot.TURN, update.shot)
    }

    @Test
    fun `two faces count as no usable face`() {
        val crowd = FaceReading(faces = 2)
        val update = feed(LivenessState(), HeadTurn.LEFT, straight, straight, crowd, straight, straight)
        assertNull(update.shot)
    }

    @Test
    fun `done stays done`() {
        val done = LivenessState(step = LivenessStep.DONE)
        assertEquals(LivenessUpdate(done), LivenessGuide.next(done, turnedLeft, HeadTurn.LEFT))
    }
}

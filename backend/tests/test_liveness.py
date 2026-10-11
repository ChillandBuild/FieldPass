from __future__ import annotations

import logging

import pytest

from app.config import ConfigError
from app.liveness import LivenessLimits, head_turn_ok, nose_offset_from_landmarks
from tests.fakes import BLANK, TWO_FACES, face
from tests.test_timing_settings import load

LIMITS = LivenessLimits(front_max_offset=0.12, turn_min_offset=0.25, mirrored=False)


def row(nose_x: float, right_eye=(40.0, 50.0), left_eye=(60.0, 50.0)) -> list[float]:
    """A YuNet row: bbox(4), right eye(2), left eye(2), nose(2), mouth corners(4), score(1)."""
    return [0, 0, 100, 100, *right_eye, *left_eye, nose_x, 60.0, 45, 70, 55, 70, 0.9]


# ---- landmark maths --------------------------------------------------------

def test_centred_nose_is_zero():
    assert nose_offset_from_landmarks(row(50)) == pytest.approx(0.0)


def test_nose_toward_picture_right_is_positive():
    assert nose_offset_from_landmarks(row(57)) == pytest.approx(0.35)


def test_eye_labels_swapped_gives_same_offset():
    assert nose_offset_from_landmarks(row(57, right_eye=(60, 50), left_eye=(40, 50))) == pytest.approx(0.35)


def test_eyes_on_top_of_each_other_is_none():
    assert nose_offset_from_landmarks(row(50, right_eye=(50, 50), left_eye=(50.5, 50))) is None


@pytest.mark.parametrize(("front", "turned", "challenge", "ok"), [
    (0.0, 0.35, "left", True),
    (0.0, 0.25, "left", True),      # exactly on the threshold passes
    (0.0, 0.24, "left", False),
    (0.0, 0.35, "right", False),    # turned the wrong way
    (0.0, -0.35, "right", True),
    (0.2, 0.35, "left", False),     # the "straight" photo was already turned
    (None, 0.35, "left", False),
    (0.0, None, "left", False),
])
def test_head_turn_ok(front, turned, challenge, ok):
    assert head_turn_ok(front, turned, challenge, LIMITS) is ok


def test_mirrored_flips_the_direction():
    mirrored = LivenessLimits(front_max_offset=0.12, turn_min_offset=0.25, mirrored=True)
    assert head_turn_ok(0.0, -0.35, "left", mirrored) and not head_turn_ok(0.0, 0.35, "left", mirrored)


# ---- settings --------------------------------------------------------------

def test_liveness_settings_defaults(tmp_path):
    s = load(tmp_path)
    assert (s.liveness_front_max_offset, s.liveness_turn_min_offset, s.liveness_mirrored) == (0.12, 0.25, False)


def test_liveness_settings_from_env(tmp_path):
    s = load(tmp_path, LIVENESS_FRONT_MAX_OFFSET="0.1", LIVENESS_TURN_MIN_OFFSET="0.3", LIVENESS_MIRRORED="true")
    assert (s.liveness_front_max_offset, s.liveness_turn_min_offset, s.liveness_mirrored) == (0.1, 0.3, True)


@pytest.mark.parametrize("key", ["LIVENESS_FRONT_MAX_OFFSET", "LIVENESS_TURN_MIN_OFFSET"])
@pytest.mark.parametrize("bad", ["nan", "inf", "-inf"])
def test_non_finite_liveness_offsets_are_rejected(tmp_path, key, bad):
    with pytest.raises(ConfigError, match=key):
        load(tmp_path, **{key: bad})


def test_front_max_at_or_above_turn_min_logs_a_warning(tmp_path, caplog):
    with caplog.at_level(logging.WARNING, logger="fieldpass"):
        s = load(tmp_path, LIVENESS_FRONT_MAX_OFFSET="9", LIVENESS_TURN_MIN_OFFSET="-9")
    assert (s.liveness_front_max_offset, s.liveness_turn_min_offset) == (9.0, -9.0)  # allowed, not an error
    assert "head-turn check cannot fail for a single photo" in caplog.text


def test_default_liveness_offsets_log_no_warning(tmp_path, caplog):
    with caplog.at_level(logging.WARNING, logger="fieldpass"):
        load(tmp_path)
    assert "head-turn check" not in caplog.text


def test_liveness_is_required_unless_switched_off(tmp_path):
    assert load(tmp_path).liveness_required is True
    assert load(tmp_path, LIVENESS_REQUIRED="false").liveness_required is False
    assert load(tmp_path, LIVENESS_REQUIRED="off").liveness_required is False
    assert load(tmp_path, LIVENESS_REQUIRED="true").liveness_required is True


# ---- through the API -------------------------------------------------------

@pytest.fixture()
def ready(api, test_clock):
    api.enroll("student1", "asha")
    activity = api.make_activity()
    test_clock.advance(minutes=31)
    return activity


def submit_with_turn(api, ready, turn_for, selfie=face("asha"), **kw):
    """turn_for(challenge) -> turn photo bytes."""
    attempt = api.attempt("student1", ready["id"], "check_in").json()
    return api.submit("student1", attempt["id"], selfie, turn=turn_for(attempt["challenge"]), **kw).json()


def opposite(challenge: str) -> str:
    return "right" if challenge == "left" else "left"


def test_correct_turn_is_recorded(api, ready):
    body = submit_with_turn(api, ready, lambda c: face(f"asha@{c}"))
    assert (body["status"], body["review_reasons"]) == ("recorded", [])


def test_one_photo_is_enough_when_liveness_is_switched_off(api, app, ready):
    from dataclasses import replace

    app.state.settings = replace(app.state.settings, liveness_required=False)
    # The app sends the same photo as both parts; with the head-turn check off that is a normal check-in.
    body = submit_with_turn(api, ready, lambda c: face("asha"))
    assert (body["status"], body["review_reasons"]) == ("recorded", [])


def test_wrong_direction_is_liveness_failed(api, ready):
    assert submit_with_turn(api, ready, lambda c: face(f"asha@{opposite(c)}"))["review_reasons"] == ["liveness_failed"]


def test_no_turn_is_liveness_failed(api, ready):
    assert submit_with_turn(api, ready, lambda c: face("asha"))["review_reasons"] == ["liveness_failed"]


def test_turn_photo_without_face_is_liveness_failed(api, ready):
    assert submit_with_turn(api, ready, lambda c: BLANK)["review_reasons"] == ["liveness_failed"]


def test_turn_photo_with_two_faces_is_liveness_failed(api, ready):
    assert submit_with_turn(api, ready, lambda c: TWO_FACES)["review_reasons"] == ["liveness_failed"]


def test_turn_photo_of_someone_else_is_liveness_failed(api, ready):
    assert submit_with_turn(api, ready, lambda c: face(f"ravi@{c}"))["review_reasons"] == ["liveness_failed"]


def test_liveness_reason_sits_between_face_and_flags(api, ready):
    # selfie "asha~" is an uncertain match for the enrolled "asha" (a clear mismatch is refused: face_not_matched)
    body = submit_with_turn(api, ready, lambda c: face("ravi"), selfie=face("asha~"), mock=True)
    assert body["review_reasons"] == ["face_uncertain", "liveness_failed", "location_mocked"]

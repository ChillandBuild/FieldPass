from __future__ import annotations

from pathlib import Path

from tests.fakes import BLANK, TWO_FACES, face
from tests.helpers import P


def upload(api, username, data):
    return api.post(username, "/enrollment", files={"photo": ("p.jpg", data, "image/jpeg")})


def test_status_none_before_upload(api):
    body = api.get("student1", "/enrollment/me").json()
    assert body["status"] == "none" and body["id"] is None
    assert body["student"]["username"] == "student1"
    assert body["submitted_at"] is None and body["decided_by"] is None


def test_upload_creates_pending_enrollment(api):
    resp = upload(api, "student1", face("asha"))
    body = resp.json()
    assert resp.status_code == 201
    assert body["status"] == "pending" and body["id"].startswith("enr_")
    assert body["submitted_at"] == "2026-10-10T09:00:00Z"
    assert api.get("student1", "/enrollment/me").json()["status"] == "pending"


def test_zero_faces_is_unusable_capture(api):
    resp = upload(api, "student1", BLANK)
    assert resp.status_code == 422 and resp.json()["error"]["code"] == "unusable_capture"
    assert api.get("student1", "/enrollment/me").json()["status"] == "none"


def test_multiple_faces_rejected(api):
    resp = upload(api, "student1", TWO_FACES)
    assert resp.status_code == 422 and resp.json()["error"]["code"] == "multiple_faces"


def test_missing_photo_part_is_validation_error(api):
    resp = api.post("student1", "/enrollment")
    assert resp.status_code == 422 and resp.json()["error"]["code"] == "validation_error"


def test_oversize_photo_is_413(api):
    resp = upload(api, "student1", b"FACE:" + b"x" * (5 * 1024 * 1024))
    assert resp.status_code == 413 and resp.json()["error"]["code"] == "image_too_large"


def test_photo_is_stored_encrypted(api, settings):
    upload(api, "student1", face("asha-secret-pixels"))
    files = list(Path(settings.photo_dir).glob("*.enc"))
    assert len(files) == 1
    assert b"asha-secret-pixels" not in files[0].read_bytes()


def test_faculty_lists_filters_and_views_photo(api):
    upload(api, "student1", face("asha"))
    upload(api, "student2", face("rohan"))
    pending = api.get("faculty1", "/enrollments", params={"status": "pending"}).json()["items"]
    assert {e["student"]["username"] for e in pending} == {"student1", "student2"}
    assert api.get("faculty1", "/enrollments", params={"status": "approved"}).json()["items"] == []
    resp = api.get("faculty1", f"/enrollments/{pending[0]['id']}/photo")
    assert resp.status_code == 200
    assert resp.headers["content-type"] == "image/jpeg"
    assert resp.headers["cache-control"] == "no-store"
    assert resp.content in (face("asha"), face("rohan"))


def test_invalid_status_filter_is_validation_error(api):
    resp = api.get("faculty1", "/enrollments", params={"status": "bogus"})
    assert resp.status_code == 422 and resp.json()["error"]["code"] == "validation_error"


def test_approve_requires_identity_confirmed_true(api):
    enrollment = upload(api, "student1", face("asha")).json()
    for body in ({"identity_confirmed": False}, {}):
        resp = api.post("faculty1", f"/enrollments/{enrollment['id']}/approve", json=body)
        assert resp.status_code == 422 and resp.json()["error"]["code"] == "validation_error"
    assert api.get("student1", "/enrollment/me").json()["status"] == "pending"


def test_approve_records_actor_and_time(api, test_clock):
    enrollment = upload(api, "student1", face("asha")).json()
    test_clock.advance(minutes=5)
    body = api.post("faculty1", f"/enrollments/{enrollment['id']}/approve", json={"identity_confirmed": True}).json()
    assert body["status"] == "approved"
    assert body["decided_by"] == "Dr. Meera Rao"
    assert body["decided_at"] == "2026-10-10T09:05:00Z"


def test_reject_requires_reason_and_records_it(api):
    enrollment = upload(api, "student1", face("asha")).json()
    path = f"/enrollments/{enrollment['id']}/reject"
    for body in ({}, {"reason": ""}, {"reason": "   "}):
        assert api.post("faculty1", path, json=body).status_code == 422
    body = api.post("faculty1", path, json={"reason": "Photo is too dark"}).json()
    assert body["status"] == "rejected" and body["decision_reason"] == "Photo is too dark"
    assert body["decided_by"] == "Dr. Meera Rao"


def test_already_decided_enrollment_cannot_be_decided_again(api):
    enrollment = api.enroll("student1", "asha")
    for path, body in (("approve", {"identity_confirmed": True}), ("reject", {"reason": "x"})):
        resp = api.post("faculty1", f"/enrollments/{enrollment['id']}/{path}", json=body)
        assert resp.status_code == 409 and resp.json()["error"]["code"] == "already_decided"


def test_unknown_enrollment_is_404(api):
    assert api.get("faculty1", "/enrollments/enr_nope/photo").status_code == 404
    assert api.post("faculty1", "/enrollments/enr_nope/approve", json={"identity_confirmed": True}).status_code == 404


def test_reupload_replaces_photo_and_resets_approval(api, settings):
    approved = api.enroll("student1", "asha")
    assert approved["status"] == "approved"
    again = upload(api, "student1", face("asha2")).json()
    assert again["status"] == "pending"
    assert again["decided_at"] is None and again["decided_by"] is None and again["decision_reason"] is None
    assert api.get("student1", "/enrollment/me").json()["status"] == "pending"
    assert len(list(Path(settings.photo_dir).glob("*.enc"))) == 1  # the old file was removed
    photo = api.get("faculty1", f"/enrollments/{again['id']}/photo")
    assert photo.content == face("asha2")


def test_reupload_after_rejection_goes_pending(api):
    enrollment = upload(api, "student1", face("asha")).json()
    api.post("faculty1", f"/enrollments/{enrollment['id']}/reject", json={"reason": "blurry"})
    assert upload(api, "student1", face("asha")).json()["status"] == "pending"


def faculty_upload(api, student_id, data, username="faculty1"):
    return api.post(username, f"/students/{student_id}/enrollment", files={"photo": ("p.jpg", data, "image/jpeg")})


def student_id_of(api, username):
    return api.get("student1", "/enrollment/me").json()["student"]["id"] if username == "student1" else next(
        s["id"] for s in api.get("faculty1", "/students").json()["items"] if s["username"] == username
    )


def test_faculty_uploads_pending_enrollment_for_a_student(api, settings):
    sid = student_id_of(api, "student1")
    resp = faculty_upload(api, sid, face("asha"))
    body = resp.json()
    assert resp.status_code == 201
    assert body["status"] == "pending" and body["student"]["username"] == "student1"
    assert api.get("student1", "/enrollment/me").json()["status"] == "pending"
    assert api.get("faculty1", f"/enrollments/{body['id']}/photo").content == face("asha")
    assert all(b"asha" not in p.read_bytes() for p in Path(settings.photo_dir).iterdir())


def test_faculty_upload_still_needs_a_separate_approval(api):
    sid = student_id_of(api, "student1")
    enr = faculty_upload(api, sid, face("asha")).json()
    resp = api.post("faculty1", f"/enrollments/{enr['id']}/approve", json={"identity_confirmed": True})
    assert resp.json()["status"] == "approved" and resp.json()["decided_by"] == "Dr. Meera Rao"


def test_faculty_upload_is_refused_when_a_photo_already_exists(api):
    sid = student_id_of(api, "student1")
    first = upload(api, "student1", face("asha")).json()
    resp = faculty_upload(api, sid, face("asha2"))
    assert resp.status_code == 409 and resp.json()["error"]["code"] == "already_enrolled"
    assert [e["id"] for e in api.get("faculty1", "/enrollments").json()["items"]] == [first["id"]]


def test_faculty_upload_is_refused_even_after_rejection(api):
    sid = student_id_of(api, "student1")
    first = upload(api, "student1", face("asha")).json()
    api.post("faculty1", f"/enrollments/{first['id']}/reject", json={"reason": "blurry"})
    assert faculty_upload(api, sid, face("asha2")).status_code == 409


def test_student_cannot_use_the_faculty_upload(api):
    sid = student_id_of(api, "student2")
    resp = faculty_upload(api, sid, face("rohan"), username="student1")
    assert resp.status_code == 403


def test_faculty_upload_unknown_student_is_404(api):
    resp = faculty_upload(api, "usr_nope", face("asha"))
    assert resp.status_code == 404 and resp.json()["error"]["code"] == "not_found"


def test_faculty_upload_for_a_faculty_id_is_404(api):
    faculty_id = api.get("faculty1", "/auth/me").json()["id"]
    assert faculty_upload(api, faculty_id, face("asha")).status_code == 404


def test_faculty_upload_without_a_usable_face_is_rejected(api):
    sid = student_id_of(api, "student1")
    resp = faculty_upload(api, sid, BLANK)
    assert resp.status_code == 422 and resp.json()["error"]["code"] == "unusable_capture"
    assert faculty_upload(api, sid, TWO_FACES).json()["error"]["code"] == "multiple_faces"

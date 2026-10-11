"""Findings 3, 8 and 12: a new id per upload, clean concurrent uploads, atomic decisions and submits."""
from __future__ import annotations

import threading
from pathlib import Path

from fastapi.testclient import TestClient
from sqlalchemy import func, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app.models import AttendanceEvent, Enrollment, new_id
from app.routers import attempts as attempts_module
from app.routers import enrollment as enrollment_module
from tests.fakes import face
from tests.helpers import P, VENUE


def upload(api, username, identity):
    return api.post(username, "/enrollment", files={"photo": ("p.jpg", face(identity), "image/jpeg")})


def err(resp):
    return resp.status_code, resp.json()["error"]["code"]


def enc_files(settings):
    return sorted(Path(settings.photo_dir).glob("*.enc"))


def count_rows(app, student_id="u_s1"):
    with app.state.session_factory() as session:
        return session.scalar(select(func.count()).select_from(Enrollment).where(Enrollment.student_id == student_id))


# ---- finding 3: every upload is a new enrollment id --------------------------

def test_every_upload_gets_a_new_id_and_the_old_one_stops_resolving(api, app, settings):
    first = upload(api, "student1", "asha").json()
    second = upload(api, "student1", "asha2").json()
    assert first["id"] != second["id"]
    assert api.get("student1", "/enrollment/me").json()["id"] == second["id"]
    assert err(api.get("faculty1", f"/enrollments/{first['id']}/photo")) == (404, "not_found")
    approve = {"identity_confirmed": True}
    assert err(api.post("faculty1", f"/enrollments/{first['id']}/approve", json=approve)) == (404, "not_found")
    assert err(api.post("faculty1", f"/enrollments/{first['id']}/reject", json={"reason": "x"})) == (404, "not_found")
    assert count_rows(app) == 1 and len(enc_files(settings)) == 1
    assert api.get("faculty1", f"/enrollments/{second['id']}/photo").content == face("asha2")


def test_photo_swapped_after_faculty_viewed_it_cannot_be_approved_by_the_old_click(api):
    seen = upload(api, "student1", "asha").json()
    assert api.get("faculty1", f"/enrollments/{seen['id']}/photo").content == face("asha")
    swapped = upload(api, "student1", "someone-else").json()
    assert err(api.post("faculty1", f"/enrollments/{seen['id']}/approve", json={"identity_confirmed": True})) == (404, "not_found")
    assert api.get("student1", "/enrollment/me").json()["status"] == "pending"
    assert api.get("student1", "/enrollment/me").json()["id"] == swapped["id"]


def test_reupload_after_approval_leaves_one_pending_current_enrollment(api):
    api.enroll("student1", "asha")
    again = upload(api, "student1", "asha2").json()
    pending = api.get("faculty1", "/enrollments", params={"status": "pending"}).json()["items"]
    assert [e["id"] for e in pending] == [again["id"]]
    assert api.get("faculty1", "/enrollments", params={"status": "approved"}).json()["items"] == []


# ---- finding 8: concurrent uploads -------------------------------------------

def test_concurrent_first_uploads_have_one_winner_no_500_and_no_orphan_file(api, app, settings):
    headers = api.auth("student1")
    results, barrier = [], threading.Barrier(6)

    def worker(n):
        with TestClient(app, raise_server_exceptions=False) as client:
            barrier.wait()
            resp = client.post(f"{P}/enrollment", headers=headers,
                               files={"photo": ("p.jpg", face(f"asha{n}"), "image/jpeg")})
            results.append(resp.status_code)

    threads = [threading.Thread(target=worker, args=(n,)) for n in range(6)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    assert 500 not in results and set(results) <= {201, 409}
    assert count_rows(app) == 1 and len(enc_files(settings)) == 1
    current = api.get("student1", "/enrollment/me").json()
    assert api.get("faculty1", f"/enrollments/{current['id']}/photo").status_code == 200


def test_unique_clash_on_commit_is_retried_cleanly(api, app, settings, monkeypatch):
    real_commit, state = Session.commit, {"left": 1}

    def flaky(self):
        if state["left"]:
            state["left"] -= 1
            self.rollback()
            raise IntegrityError("insert", {}, Exception("UNIQUE constraint failed: enrollments.student_id"))
        return real_commit(self)

    api.auth("student1")  # log in (binds the phone) before commit is sabotaged
    monkeypatch.setattr(Session, "commit", flaky)
    resp = upload(api, "student1", "asha")
    assert resp.status_code == 201
    assert count_rows(app) == 1 and len(enc_files(settings)) == 1


def test_persistent_clash_gives_a_clean_error_and_leaves_no_file(api, app, settings, monkeypatch):
    def always(self):
        self.rollback()
        raise IntegrityError("insert", {}, Exception("UNIQUE constraint failed"))

    api.auth("student1")  # log in (binds the phone) before commit is sabotaged
    monkeypatch.setattr(Session, "commit", always)
    resp = upload(api, "student1", "asha")
    assert resp.status_code == 409 and resp.json()["error"]["code"] == "invalid_action_order"
    assert enc_files(settings) == []


def test_failure_while_saving_leaves_no_orphan_file(api, settings, monkeypatch):
    def boom(self):
        raise RuntimeError("db down")

    api.auth("student1")  # log in (binds the phone) before commit is sabotaged
    monkeypatch.setattr(Session, "commit", boom)
    assert upload(api, "student1", "asha").status_code == 500
    assert enc_files(settings) == []


# ---- finding 12: decisions are atomic ----------------------------------------

def test_two_simultaneous_decisions_one_wins_the_other_is_already_decided(api, app):
    enrollment = upload(api, "student1", "asha").json()
    headers = api.auth("faculty1")
    results, barrier = [], threading.Barrier(2)

    def worker(action, body):
        with TestClient(app, raise_server_exceptions=False) as client:
            barrier.wait()
            resp = client.post(f"{P}/enrollments/{enrollment['id']}/{action}", headers=headers, json=body)
            results.append((action, resp.status_code))

    threads = [
        threading.Thread(target=worker, args=("approve", {"identity_confirmed": True})),
        threading.Thread(target=worker, args=("reject", {"reason": "blurry"})),
    ]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    assert sorted(code for _, code in results) == [200, 409]
    winner = next(action for action, code in results if code == 200)
    status = api.get("student1", "/enrollment/me").json()["status"]
    assert status == {"approve": "approved", "reject": "rejected"}[winner]


def test_decision_made_between_the_check_and_the_write_is_not_accepted_twice(api, app, monkeypatch):
    enrollment = upload(api, "student1", "asha").json()
    real_get = enrollment_module._get_enrollment

    def get_then_lose_the_race(session, enrollment_id):
        row = real_get(session, enrollment_id)  # faculty A sees it pending ...
        with app.state.session_factory() as other:  # ... faculty B decides before A writes
            stored = other.get(Enrollment, enrollment_id)
            stored.status, stored.decision_reason = "rejected", "B said no"
            other.commit()
        return row

    monkeypatch.setattr(enrollment_module, "_get_enrollment", get_then_lose_the_race)
    resp = api.post("faculty1", f"/enrollments/{enrollment['id']}/approve", json={"identity_confirmed": True})
    assert err(resp) == (409, "already_decided")
    me = api.get("student1", "/enrollment/me").json()
    assert me["status"] == "rejected" and me["decision_reason"] == "B said no"


def replace_enrollment(app, student_id, status):
    """Simulate a re-upload committed by another request: old row gone, a new row with a new id."""
    with app.state.session_factory() as session:
        old = session.scalar(select(Enrollment).where(Enrollment.student_id == student_id))
        new = Enrollment(id=new_id("enr"), student_id=student_id, status=status, photo_file=old.photo_file,
                         embedding_enc=old.embedding_enc, submitted_at=old.submitted_at)
        session.delete(old)
        session.flush()
        session.add(new)
        session.commit()


def attempt_ready(api, test_clock):
    api.enroll("student1", "asha")
    activity = api.make_activity()
    test_clock.advance(minutes=31)
    return activity, api.attempt("student1", activity["id"], "check_in").json()


def event_count(app):
    with app.state.session_factory() as session:
        return session.scalar(select(func.count()).select_from(AttendanceEvent))


def test_enrollment_replaced_before_the_event_is_saved_blocks_the_submit(api, app, test_clock, monkeypatch):
    _, attempt = attempt_ready(api, test_clock)
    real_verdict = attempts_module._face_verdict

    def compare_then_replace(engine, store, enrollment, probe):
        verdict = real_verdict(engine, store, enrollment, probe)
        replace_enrollment(app, "u_s1", "pending")
        return verdict

    monkeypatch.setattr(attempts_module, "_face_verdict", compare_then_replace)
    resp = api.submit("student1", attempt["id"], face("asha"))
    assert err(resp) == (409, "enrollment_not_approved")
    assert event_count(app) == 0


def test_replaced_and_reapproved_enrollment_is_still_a_different_enrollment(api, app, test_clock, monkeypatch):
    _, attempt = attempt_ready(api, test_clock)
    real_verdict = attempts_module._face_verdict

    def compare_then_replace(engine, store, enrollment, probe):
        verdict = real_verdict(engine, store, enrollment, probe)
        replace_enrollment(app, "u_s1", "approved")  # new id, even though it is approved again
        return verdict

    monkeypatch.setattr(attempts_module, "_face_verdict", compare_then_replace)
    assert err(api.submit("student1", attempt["id"], face("asha"))) == (409, "enrollment_not_approved")
    assert event_count(app) == 0


def test_submit_still_records_when_the_enrollment_is_unchanged(api, app, test_clock):
    _, attempt = attempt_ready(api, test_clock)
    resp = api.submit("student1", attempt["id"], face("asha"))
    assert resp.status_code == 200 and resp.json()["status"] == "recorded"
    assert event_count(app) == 1

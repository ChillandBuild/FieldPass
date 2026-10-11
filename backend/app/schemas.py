"""Request and response models, field-for-field as in contracts/API.md."""
from __future__ import annotations

from datetime import datetime, timedelta, timezone
from typing import Annotated, Literal

from pydantic import BaseModel, Field, StringConstraints, field_validator, model_validator

Role = Literal["student", "faculty"]
EnrollmentStatus = Literal["none", "pending", "approved", "rejected"]
Action = Literal["check_in", "checkpoint", "check_out"]
AttendanceStatus = Literal["recorded", "needs_review", "confirmed", "not_confirmed"]
LocationVerdict = Literal["inside", "uncertain", "outside"]
FaceVerdict = Literal["match", "uncertain", "no_match"]
HeadTurn = Literal["left", "right"]
ObservationType = Literal["present", "absent", "left_early", "other"]


Name200 = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=200)]
Reason = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=1000)]
Note = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=2000)]


# ---- responses -------------------------------------------------------------

class UserOut(BaseModel):
    id: str
    username: str
    full_name: str
    role: Role


class StudentOut(UserOut):
    device_bound_at: str | None


class StudentList(BaseModel):
    items: list[StudentOut]


class LoginOut(BaseModel):
    access_token: str
    token_type: Literal["bearer"] = "bearer"
    expires_at: str
    user: UserOut


class HealthOut(BaseModel):
    status: Literal["ok"] = "ok"
    server_time: str


class EnrollmentOut(BaseModel):
    id: str | None
    status: EnrollmentStatus
    student: UserOut
    submitted_at: str | None
    decided_at: str | None
    decided_by: str | None
    decision_reason: str | None


class CheckpointWindowOut(BaseModel):
    id: str
    activity_id: str
    opens_at: str
    closes_at: str
    opened_by: str


class LocationOut(BaseModel):
    verdict: LocationVerdict
    distance_m: int
    accuracy_m: int
    captured_at: str


class FaceOut(BaseModel):
    verdict: FaceVerdict


class AttendanceEventOut(BaseModel):
    id: str
    attempt_id: str
    activity_id: str
    student_id: str
    action: Action
    status: Literal["recorded", "needs_review"]
    received_at: str
    checkpoint_id: str | None
    review_reasons: list[str]
    location: LocationOut
    face: FaceOut
    presence_verified: bool


class DecisionOut(BaseModel):
    id: str
    decision: Literal["confirmed", "not_confirmed"]
    reason: str
    actor_name: str
    decided_at: str
    is_current: bool


class ObservationOut(BaseModel):
    id: str
    activity_id: str
    student_id: str
    observation_type: ObservationType
    note: str
    actor_name: str
    observed_at: str


class AttendanceRecordOut(BaseModel):
    activity_id: str
    activity_name: str
    student: UserOut
    status: AttendanceStatus | None
    check_in: AttendanceEventOut | None
    checkpoints: list[AttendanceEventOut]
    check_out: AttendanceEventOut | None
    missing_checkout: bool
    complete: bool
    review_reasons: list[str]
    decision: DecisionOut | None
    decision_history: list[DecisionOut]
    observations: list[ObservationOut]
    next_actions: list[Action]


class VenueRegionIn(BaseModel):
    venue_lat: float = Field(ge=-90, le=90)
    venue_lng: float = Field(ge=-180, le=180)
    radius_m: int = Field(ge=0, le=5000)


class VenueRegionOut(BaseModel):
    """Output twin of VenueRegionIn: no radius cap, so a legacy or test row above the input limit still renders."""

    venue_lat: float
    venue_lng: float
    radius_m: int


class ActivityOut(BaseModel):
    id: str
    name: str
    venue_name: str
    venue_lat: float
    venue_lng: float
    radius_m: int
    venue_regions: list[VenueRegionOut] = Field(default_factory=list)
    starts_at: str
    ends_at: str
    created_by: str
    roster_count: int
    checkpoint_windows: list[CheckpointWindowOut]
    open_checkpoint: CheckpointWindowOut | None
    my_attendance: AttendanceRecordOut | None


class AttemptOut(BaseModel):
    id: str
    activity_id: str
    action: Action
    created_at: str
    expires_at: str
    checkpoint_id: str | None
    nonce: str
    challenge: HeadTurn


class PresenceCodeOut(BaseModel):
    code: str
    expires_at: str
    server_time: str
    step_seconds: int


class PresenceOut(BaseModel):
    verified: bool


class RegisterOut(BaseModel):
    activity: ActivityOut
    server_time: str
    rows: list[AttendanceRecordOut]


class UserList(BaseModel):
    items: list[UserOut]


class EnrollmentList(BaseModel):
    items: list[EnrollmentOut]


class ActivityList(BaseModel):
    items: list[ActivityOut]


class AttendanceList(BaseModel):
    items: list[AttendanceRecordOut]


class ErrorBody(BaseModel):
    code: str
    message: str


class ErrorOut(BaseModel):
    error: ErrorBody


# ---- requests --------------------------------------------------------------

class LoginIn(BaseModel):
    username: str = Field(min_length=1, max_length=100)
    password: str = Field(min_length=1, max_length=200)
    # Students only: Base64 SubjectPublicKeyInfo of the phone's Keystore key. Faculty omit it.
    device_public_key: str | None = Field(default=None, max_length=512)


class ApproveIn(BaseModel):
    identity_confirmed: bool

    @field_validator("identity_confirmed")
    @classmethod
    def _must_be_true(cls, value: bool) -> bool:
        if value is not True:
            raise ValueError("identity_confirmed must be true")
        return value


class RejectIn(BaseModel):
    reason: Reason


MIN_ACTIVITY_YEAR = 2000
MAX_ACTIVITY_YEAR = 2100
MAX_ACTIVITY_DURATION = timedelta(days=7)


def _aware(value: datetime) -> datetime:
    """UTC-aware copy. The year is checked first so extreme dates cannot overflow the conversion."""
    if not MIN_ACTIVITY_YEAR <= value.year <= MAX_ACTIVITY_YEAR:
        raise ValueError(f"times must be in the years {MIN_ACTIVITY_YEAR} to {MAX_ACTIVITY_YEAR}")
    return value.replace(tzinfo=timezone.utc) if value.tzinfo is None else value.astimezone(timezone.utc)


class ActivityIn(BaseModel):
    name: Name200
    venue_name: Name200
    venue_lat: float = Field(ge=-90, le=90)
    venue_lng: float = Field(ge=-180, le=180)
    radius_m: int = Field(default=100, ge=0, le=5000)
    venue_regions: list[VenueRegionIn] = Field(default_factory=list)
    starts_at: datetime
    ends_at: datetime
    student_ids: list[str] = Field(default_factory=list, max_length=500)

    @model_validator(mode="after")
    def _check_times(self) -> "ActivityIn":
        self.starts_at = _aware(self.starts_at)
        self.ends_at = _aware(self.ends_at)
        if self.ends_at <= self.starts_at:
            raise ValueError("ends_at must be after starts_at")
        if self.ends_at - self.starts_at > MAX_ACTIVITY_DURATION:
            raise ValueError("an activity can last at most 7 days")
        return self


class CheckpointIn(BaseModel):
    duration_minutes: int = Field(default=10, ge=1, le=60)


class AttemptIn(BaseModel):
    action: Action


class PresenceIn(BaseModel):
    code: str = Field(min_length=1, max_length=200)


class DecisionIn(BaseModel):
    decision: Literal["confirmed", "not_confirmed"]
    reason: Reason


class ObservationIn(BaseModel):
    student_id: str
    observation_type: ObservationType
    note: Note

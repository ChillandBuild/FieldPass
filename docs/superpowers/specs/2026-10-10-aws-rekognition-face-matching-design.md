# AWS Rekognition face matching

Date: 2026-10-10. Status: draft, waiting for review.

## Goal

When a student submits an attendance attempt, the backend compares the captured selfie with the student's
approved enrollment photo using AWS Rekognition. The result is the same three verdicts as today:
`match`, `uncertain`, `no_match`.

Rekognition **replaces** OpenCV. OpenCV, the ONNX models and their download script are removed.

## Not changing

- Android app, dashboard, `contracts/openapi.yaml`, error codes, API responses.
- The verdict values, review reasons (`face_mismatch`, `face_uncertain`) and the rules that use them.
- Encrypted storage of the enrollment photo (`PhotoStore`).

## Design

### 1. New engine: `app/face_rekognition.py`

`RekognitionFaceEngine(region, threshold, margin, client=None)` wraps a boto3 `rekognition` client.

- `analyze(image_bytes)`
  1. Decode and normalize with Pillow (EXIF upright, max 1280 px, JPEG, no metadata). The existing
     `decode_upright` and `_to_jpeg` move to a small `app/imaging.py`, since they are not OpenCV specific.
  2. Call `DetectFaces`. Faces below a minimum size or confidence are not counted.
  3. Return `FaceAnalysis(count, normalized_jpeg)`. `normalized_jpeg` is set only when count == 1.
- `compare(enrolled_jpeg, probe_jpeg)`
  1. Call `CompareFaces(SourceImage=enrolled, TargetImage=probe, SimilarityThreshold=0)`.
  2. Take the best `FaceMatches[0].Similarity`, or 0 if there is no match and no face in the target.
  3. Return `verdict_from_score(similarity, threshold, margin)`.
- AWS errors (throttling, network, credentials, access denied) raise a new `face_service_unavailable` API error
  (HTTP 503). They are never turned into `no_match`, so a student is not marked as a mismatch because AWS was down.

### 2. Interface change in `app/face.py`

- `FaceAnalysis` loses `embedding`; it keeps `count` and `normalized_jpeg`.
- `FaceEngine.compare(enrolled_jpeg: bytes, probe_jpeg: bytes) -> str` now takes images, not embeddings.

### 3. Router changes

- `enrollment.py`: `require_one_face` checks `count == 1 and normalized_jpeg`; no embedding is stored.
- `attempts.py`: `_face_verdict` loads the enrollment photo with `store.load(enrollment.photo_file)`
  and compares it with the selfie's `normalized_jpeg`.
- `models.py`: remove `Enrollment.embedding_enc`. The prototype has no migrations, so existing local databases
  must be recreated (re-run the seed).

### 4. Config (`config.py`, `.env.example`)

- Add `AWS_REGION` (required).
- Defaults change to Rekognition's 0 to 100 scale: `FACE_MATCH_THRESHOLD=90`, `FACE_UNCERTAIN_MARGIN=10`.
  Both stay tunable. AWS suggests 99 for high-stakes identity checks; the right value should be set after
  testing with real volunteers.
- Remove `MODEL_DIR`.
- Credentials are **not** in `.env`. boto3's default chain is used (IAM role in AWS, or `aws configure` locally).
- Required IAM permissions: `rekognition:DetectFaces`, `rekognition:CompareFaces`.

### 5. Startup (`main.py`)

`_build_engine` builds `RekognitionFaceEngine`. If the region is missing, startup fails with a clear
`ConfigError`. Missing credentials surface on the first call as `face_service_unavailable`.

### 6. Removals

`face_opencv.py`, `scripts/download_models.py`, `opencv-python-headless` and `numpy` (if unused elsewhere) from
`requirements.txt`, the `models/` directory logic, and `tests/test_real_engine.py`.

### 7. Add

`boto3` in `requirements.txt` and `botocore` stubber usage in dev tests.

## Tests

- Unit: `RekognitionFaceEngine` with botocore `Stubber`. Cases: one face, no face, several faces, high /
  borderline / low similarity, no match returned, throttling and access-denied errors raising
  `face_service_unavailable`.
- `tests/fakes.py` `FakeFaceEngine` updated to the new interface; existing router tests keep running against it.
- Update tests that read `embedding`: `test_enrollment_races.py`, `test_decode_robustness.py`.
- Opt-in live smoke test (skipped unless `AWS_REGION` and credentials are set) using two photos of the same
  person and one of another.

## Docs to update

`backend/README.md` (face section and threshold notes), `integration/known-limitations.md`, `integration/runbook.md`.
State plainly that photos are now sent to AWS, and that no liveness check exists (a photo of a photo can pass).

## Risks and open points

- **Privacy:** selfies and enrollment photos leave the server for AWS. Students need to be told and to consent.
- **Cost and latency:** per attendance submit, one `DetectFaces` and one `CompareFaces` call.
- **Threshold:** 90 / 10 is a starting guess and not validated on real people.
- **Liveness:** out of scope. AWS Face Liveness is a separate service with an Android SDK and a different flow.

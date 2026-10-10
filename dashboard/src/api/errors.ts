export type ApiErrorCode =
  | 'invalid_credentials'
  | 'not_authenticated'
  | 'forbidden'
  | 'not_assigned'
  | 'not_found'
  | 'enrollment_not_approved'
  | 'outside_activity_window'
  | 'invalid_action_order'
  | 'checkpoint_not_open'
  | 'already_decided'
  | 'attempt_expired'
  | 'image_too_large'
  | 'validation_error'
  | 'unusable_capture'
  | 'multiple_faces'
  | 'location_stale'
  // Client-side codes (not in the contract): no response / unexpected response.
  | 'network_error'
  | 'unknown_error'

export class ApiError extends Error {
  readonly code: ApiErrorCode
  readonly status: number

  constructor(code: ApiErrorCode, message: string, status = 0) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.status = status
  }
}

export function isApiError(value: unknown): value is ApiError {
  return value instanceof ApiError
}

const MESSAGES: Record<ApiErrorCode, string> = {
  invalid_credentials: 'Wrong username or password.',
  not_authenticated: 'Your session has ended. Please sign in again.',
  forbidden: 'Your account is not allowed to do this.',
  not_assigned: 'That student is not on this activity roster.',
  not_found: 'That item no longer exists.',
  enrollment_not_approved: 'That student has no approved enrollment photo yet.',
  outside_activity_window: 'This activity is not open for that action right now.',
  invalid_action_order: 'That action is not allowed right now. A checkpoint window may already be open.',
  checkpoint_not_open: 'There is no open checkpoint window.',
  already_decided: 'This enrollment has already been approved or rejected.',
  attempt_expired: 'That attempt has expired.',
  image_too_large: 'The photo is too large.',
  validation_error: 'Some fields are missing or invalid. Check the form and try again.',
  unusable_capture: 'The image could not be used.',
  multiple_faces: 'More than one face was found in the image.',
  location_stale: 'The location reading is too old.',
  network_error: 'Could not reach the server. Check the server address and your network.',
  unknown_error: 'Something unexpected went wrong. Please try again.',
}

/** Friendly text for any thrown value. Branches on the contract code, never on server text. */
export function messageForError(err: unknown): string {
  if (isApiError(err)) return MESSAGES[err.code]
  return MESSAGES.unknown_error
}

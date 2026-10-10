import { ApiError, messageForError, type ApiErrorCode } from '../src/api/errors'

describe('error code mapping', () => {
  const codes: ApiErrorCode[] = [
    'invalid_credentials', 'not_authenticated', 'forbidden', 'not_assigned', 'not_found',
    'enrollment_not_approved', 'outside_activity_window', 'invalid_action_order', 'checkpoint_not_open',
    'already_decided', 'attempt_expired', 'image_too_large', 'validation_error', 'unusable_capture',
    'multiple_faces', 'location_stale', 'network_error', 'unknown_error',
  ]
  it.each(codes)('maps %s to a non-empty friendly message', (code) => {
    const text = messageForError(new ApiError(code, 'server text that must be ignored', 400))
    expect(text.length).toBeGreaterThan(5)
    expect(text).not.toContain('server text')
  })
  it('branches on code, not on server message', () => {
    const a = messageForError(new ApiError('invalid_credentials', 'x'))
    const b = messageForError(new ApiError('invalid_credentials', 'something else'))
    expect(a).toBe(b)
    expect(a).toBe('Wrong username or password.')
  })
  it('carries the contract code and status', () => {
    const e = new ApiError('already_decided', 'm', 409)
    expect(e.code).toBe('already_decided')
    expect(e.status).toBe(409)
  })
  it('falls back for non-API errors', () => {
    expect(messageForError(new Error('boom'))).toBe(messageForError(new ApiError('unknown_error', '')))
    expect(messageForError('oops')).toContain('unexpected')
  })
})

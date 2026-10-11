// @vitest-environment node
import { canonicalMessage } from '../e2e/support/canonical'

describe('canonical submit message (same vector as backend and Android)', () => {
  it('matches the contract example byte for byte', () => {
    const message = canonicalMessage({
      attemptId: 'att_1', nonce: 'n0nce', selfie: Buffer.from('A'), turn: Buffer.from('B'),
      lat: 12.9716, lng: 77.5946, accuracyM: 10, capturedEpoch: Date.parse('2026-10-10T09:31:05Z') / 1000,
      isMock: false, rooted: true, emulator: false,
    })
    expect(message.toString('utf-8')).toBe([
      'fieldpass-attempt-v1', 'att_1', 'n0nce',
      '559aead08264d5795d3909718cdd05abd49572e84fe55590eef31a88a08fdffd',
      'df7e70e5021544f4834bbee64a9e3789febc4be81470df629cad6ddb03320a5c',
      '12.971600', '77.594600', '10.0', '1791624665', '0', '1', '0',
    ].join('\n'))
  })
})

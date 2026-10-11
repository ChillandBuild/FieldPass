import { createHash } from 'node:crypto'

export interface CanonicalInput {
  attemptId: string
  nonce: string
  selfie: Buffer
  turn: Buffer
  lat: number
  lng: number
  accuracyM: number
  /** Whole Unix seconds. */
  capturedEpoch: number
  isMock: boolean
  rooted: boolean
  emulator: boolean
}

const sha256Hex = (bytes: Buffer): string => createHash('sha256').update(bytes).digest('hex')
const bit = (flag: boolean): string => (flag ? '1' : '0')

/** The 12 lines the phone signs for a submit (contracts/API.md, "Phone lock, head turn and phone flags"). */
export function canonicalMessage(p: CanonicalInput): Buffer {
  return Buffer.from([
    'fieldpass-attempt-v1', p.attemptId, p.nonce, sha256Hex(p.selfie), sha256Hex(p.turn),
    p.lat.toFixed(6), p.lng.toFixed(6), p.accuracyM.toFixed(1), String(p.capturedEpoch),
    bit(p.isMock), bit(p.rooted), bit(p.emulator),
  ].join('\n'), 'utf-8')
}

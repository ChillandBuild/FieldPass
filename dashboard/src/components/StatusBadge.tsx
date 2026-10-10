import type { AttendanceStatus } from '../api'
import { statusLabel } from '../lib/labels'

/** Status is always conveyed by the badge text; colour is secondary. */
export function StatusBadge({ status }: { status: AttendanceStatus | null }) {
  return <span className={`badge badge-${status ?? 'none'}`}>{statusLabel(status)}</span>
}

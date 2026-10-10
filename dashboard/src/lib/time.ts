const DATE_TIME: Intl.DateTimeFormatOptions = {
  day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit', hour12: false, timeZoneName: 'short',
}
const TIME_ONLY: Intl.DateTimeFormatOptions = {
  hour: '2-digit', minute: '2-digit', hour12: false, timeZoneName: 'short',
}

function parse(iso: string | null | undefined): Date | null {
  if (!iso) return null
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? null : d
}

/** e.g. "10 Oct, 15:00 IST" in the browser's timezone. Returns an em dash for missing/invalid input. */
export function formatDateTime(iso: string | null | undefined, locale?: string): string {
  const d = parse(iso)
  return d ? new Intl.DateTimeFormat(locale, DATE_TIME).format(d) : '—'
}

const TIME_SECONDS: Intl.DateTimeFormatOptions = { ...TIME_ONLY, second: '2-digit' }

/** e.g. "15:00:07 IST". For "last updated" stamps. */
export function formatTimeWithSeconds(iso: string | null | undefined, locale?: string): string {
  const d = parse(iso)
  return d ? new Intl.DateTimeFormat(locale, TIME_SECONDS).format(d) : '\u2014'
}

/** e.g. "15:00 IST". */
export function formatTime(iso: string | null | undefined, locale?: string): string {
  const d = parse(iso)
  return d ? new Intl.DateTimeFormat(locale, TIME_ONLY).format(d) : '—'
}

/** Timezone abbreviation of the browser, e.g. "IST". Falls back to the IANA name. */
export function localTimeZoneLabel(at: Date = new Date(), locale?: string): string {
  const part = new Intl.DateTimeFormat(locale, { timeZoneName: 'short' })
    .formatToParts(at)
    .find((p) => p.type === 'timeZoneName')
  return part?.value ?? Intl.DateTimeFormat().resolvedOptions().timeZone
}

const LOCAL_INPUT = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/

/** "2026-10-10T15:00" (datetime-local, browser local time) -> "2026-10-10T09:30:00Z". Null if invalid. */
export function localInputToUtcIso(value: string): string | null {
  if (!LOCAL_INPUT.test(value)) return null
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return null
  return d.toISOString().replace(/\.\d{3}Z$/, 'Z')
}

const pad = (n: number): string => String(n).padStart(2, '0')

/** Inverse of localInputToUtcIso, for default values in datetime-local inputs. */
export function dateToLocalInput(d: Date): string {
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
}

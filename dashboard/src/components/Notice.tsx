import type { ReactNode } from 'react'

type Kind = 'error' | 'warn' | 'info' | 'ok'

export function Notice({ kind, children }: { kind: Kind; children: ReactNode }) {
  return (
    <div className={`notice notice-${kind}`} role={kind === 'error' ? 'alert' : 'status'}>
      {children}
    </div>
  )
}

export function Loading({ what }: { what: string }) {
  return (
    <p role="status" className="muted">
      <span className="spinner" aria-hidden="true" /> Loading {what}...
    </p>
  )
}

export function Empty({ children }: { children: ReactNode }) {
  return <div className="empty">{children}</div>
}

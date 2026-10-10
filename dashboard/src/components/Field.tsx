import { useId, type ReactNode } from 'react'

interface FieldProps {
  label: string
  hint?: string
  error?: string | undefined
  children: (ids: { id: string; describedBy: string | undefined; invalid: boolean }) => ReactNode
}

/** Labelled form control wrapper: wires label, hint and error text to the control. */
export function Field({ label, hint, error, children }: FieldProps) {
  const id = useId()
  const hintId = `${id}-hint`
  const errId = `${id}-err`
  const describedBy = [hint ? hintId : null, error ? errId : null].filter(Boolean).join(' ') || undefined
  return (
    <div className="field">
      <label htmlFor={id}>{label}</label>
      {children({ id, describedBy, invalid: Boolean(error) })}
      {hint ? <span id={hintId} className="field-hint">{hint}</span> : null}
      {error ? <span id={errId} className="field-error">{error}</span> : null}
    </div>
  )
}

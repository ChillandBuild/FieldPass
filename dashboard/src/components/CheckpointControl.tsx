import { useState } from 'react'
import { api } from '../api'
import type { Activity, CheckpointWindow } from '../api'
import { useAsyncAction } from '../hooks/useAsyncAction'
import { formatTime } from '../lib/time'
import { validateCheckpointMinutes } from '../lib/validation'
import { Field } from './Field'
import { Notice } from './Notice'

interface Props {
  activity: Activity
  onOpened: (w: CheckpointWindow) => void
}

export function CheckpointControl({ activity, onOpened }: Props) {
  const [minutes, setMinutes] = useState('10')
  const [minutesError, setMinutesError] = useState<string | undefined>()
  const open = useAsyncAction(async () => { onOpened(await api.openCheckpoint(activity.id, Number(minutes))) })

  function submit(): void {
    const problem = validateCheckpointMinutes(minutes)
    setMinutesError(problem ?? undefined)
    if (!problem) void open.run()
  }

  return (
    <section className="card checkpoint-control" aria-label="Checkpoint window">
      <h2>Checkpoint window</h2>
      {activity.open_checkpoint ? (
        <p><strong>Open</strong> until {formatTime(activity.open_checkpoint.closes_at)}. Students can submit one checkpoint during this window.</p>
      ) : (
        <p className="muted">No window is open. Students cannot submit a checkpoint until you open one.</p>
      )}
      {open.error ? <Notice kind="error">{open.error}</Notice> : null}
      <div className="cp-inline">
        <Field label="Duration (minutes)" error={minutesError}>
          {(p) => <input id={p.id} inputMode="numeric" value={minutes} aria-invalid={p.invalid}
            aria-describedby={p.describedBy} onChange={(e) => setMinutes(e.target.value)} />}
        </Field>
        <button type="button" className="btn btn-primary" disabled={open.isBusy || Boolean(activity.open_checkpoint)} onClick={submit}>
          {open.isBusy ? 'Opening...' : 'Open checkpoint window'}
        </button>
      </div>
    </section>
  )
}

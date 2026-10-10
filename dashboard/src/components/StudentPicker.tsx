import type { User } from '../api'

interface Props {
  students: readonly User[]
  selected: readonly string[]
  onChange: (next: string[]) => void
  error?: string | undefined
}

export function StudentPicker({ students, selected, onChange, error }: Props) {
  const toggle = (id: string, on: boolean): void =>
    onChange(on ? [...selected, id] : selected.filter((s) => s !== id))
  return (
    <fieldset className="field" aria-describedby={error ? 'students-err' : undefined}>
      <legend>Assigned students</legend>
      <div className="student-picks">
        {students.map((s) => (
          <div key={s.id} className="check">
            <input id={`stu-${s.id}`} type="checkbox" checked={selected.includes(s.id)}
              onChange={(e) => toggle(s.id, e.target.checked)} />
            <label htmlFor={`stu-${s.id}`}>{s.full_name} <span className="muted">({s.username})</span></label>
          </div>
        ))}
      </div>
      {error ? <span id="students-err" className="field-error">{error}</span> : null}
    </fieldset>
  )
}

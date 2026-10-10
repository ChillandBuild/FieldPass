import { useState, type FormEvent } from 'react'
import { api, messageForError, USE_FIXTURES } from '../api'
import { getBaseUrl, isValidBaseUrl, saveBaseUrl } from '../api/config'
import { Field } from '../components/Field'
import { FixtureBanner } from '../components/FixtureBanner'
import { Notice } from '../components/Notice'
import { useAsyncAction } from '../hooks/useAsyncAction'
import { useSession } from '../hooks/useSession'
import { formatDateTime } from '../lib/time'

type Check = { kind: 'ok' | 'error'; text: string } | null

export function LoginScreen() {
  const { login } = useSession()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [serverUrl, setServerUrl] = useState(getBaseUrl)
  const [notFaculty, setNotFaculty] = useState(false)
  const [urlError, setUrlError] = useState<string | undefined>()
  const [check, setCheck] = useState<Check>(null)

  const submit = useAsyncAction(async () => {
    setNotFaculty(false)
    const outcome = await login(username.trim(), password)
    if (!outcome.ok) setNotFaculty(true)
  })
  const test = useAsyncAction(async () => {
    setCheck(null)
    if (!applyUrl()) return
    try {
      const h = await api.health()
      setCheck({ kind: 'ok', text: `Connected. Server time ${formatDateTime(h.server_time)}.` })
    } catch (err) {
      setCheck({ kind: 'error', text: messageForError(err) })
    }
  })

  function applyUrl(): boolean {
    if (!isValidBaseUrl(serverUrl)) {
      setUrlError('Enter a full address such as http://192.168.43.10:8000')
      return false
    }
    setUrlError(undefined)
    saveBaseUrl(serverUrl)
    return true
  }

  function onSubmit(e: FormEvent): void {
    e.preventDefault()
    if (!applyUrl()) return
    void submit.run()
  }

  return (
    <>
      <FixtureBanner />
      <div className="center-screen">
        <div className="center-card stack">
          <h1>FieldPass faculty sign in</h1>
          {notFaculty ? (
            <Notice kind="error">
              This dashboard is for faculty. You have been signed out. Student accounts use the FieldPass Android app.
            </Notice>
          ) : null}
          {submit.error ? <Notice kind="error">{submit.error}</Notice> : null}
          <form className="card" onSubmit={onSubmit} noValidate>
            <Field label="Username">
              {(p) => <input id={p.id} value={username} autoComplete="username" autoCapitalize="none"
                onChange={(e) => setUsername(e.target.value)} required />}
            </Field>
            <Field label="Password">
              {(p) => <input id={p.id} type="password" value={password} autoComplete="current-password"
                onChange={(e) => setPassword(e.target.value)} required />}
            </Field>
            <Field label="Server address" error={urlError}
              hint={USE_FIXTURES ? 'Ignored in fixture mode.' : 'The laptop running the backend. It changes when the hotspot changes.'}>
              {(p) => <input id={p.id} inputMode="url" value={serverUrl} aria-invalid={p.invalid}
                aria-describedby={p.describedBy} onChange={(e) => setServerUrl(e.target.value)} />}
            </Field>
            <div className="btn-row">
              <button type="submit" className="btn btn-primary" disabled={submit.isBusy || !username || !password}>
                {submit.isBusy ? 'Signing in...' : 'Sign in'}
              </button>
              <button type="button" className="btn" onClick={() => { void test.run() }} disabled={test.isBusy}>
                {test.isBusy ? 'Testing...' : 'Test connection'}
              </button>
            </div>
            {check ? <div style={{ marginTop: 12 }}><Notice kind={check.kind}>{check.text}</Notice></div> : null}
          </form>
          {USE_FIXTURES ? (
            <p className="muted">Fixture mode: sign in as faculty1 with any password. student1 shows the faculty-only message.</p>
          ) : null}
        </div>
      </div>
    </>
  )
}

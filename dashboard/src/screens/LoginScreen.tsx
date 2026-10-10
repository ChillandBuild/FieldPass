import { useState, type FormEvent } from 'react'
import { USE_FIXTURES } from '../api'
import { Field } from '../components/Field'
import { FixtureBanner } from '../components/FixtureBanner'
import { Notice } from '../components/Notice'
import { useAsyncAction } from '../hooks/useAsyncAction'
import { useSession } from '../hooks/useSession'

export function LoginScreen() {
  const { login } = useSession()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [notFaculty, setNotFaculty] = useState(false)

  const submit = useAsyncAction(async () => {
    setNotFaculty(false)
    const outcome = await login(username.trim(), password)
    if (!outcome.ok) setNotFaculty(true)
  })
  function onSubmit(e: FormEvent): void {
    e.preventDefault()
    void submit.run()
  }

  return (
    <>
      <FixtureBanner />
      <div className="center-screen">
        <div className="login-layout">
          <div className="login-intro">
            <div className="brand login-brand">
              <span className="brand-mark" aria-hidden="true">f</span>
              <span>FieldPass<span className="brand-role">Faculty workspace</span></span>
            </div>
            <div className="login-intro-copy">
              <p className="login-eyebrow">FIELD VISITS, MADE CLEAR</p>
              <p className="login-intro-title">See the visit.<br />{' '}<span>Decide with context.</span></p>
              <p>Follow each student’s recorded moments, review uncertain evidence, and make the final attendance decision.</p>
            </div>
            <div className="login-flow" aria-label="How faculty review a field visit">
              <div className="login-flow-row"><span className="login-flow-mark">1</span><span><strong>Evidence arrives</strong><small>Check-in, checkpoint, and checkout</small></span></div>
              <div className="login-flow-row"><span className="login-flow-mark">2</span><span><strong>Uncertainty is flagged</strong><small>Every review reason stays visible</small></span></div>
              <div className="login-flow-row"><span className="login-flow-mark">3</span><span><strong>Faculty decide</strong><small>Your reason is part of the record</small></span></div>
            </div>
            <p className="login-intro-note">Each time is a point of observation, not proof of continuous presence.</p>
          </div>
          <div className="login">
            <div className="login-card">
              <p className="login-card-eyebrow">FACULTY ACCESS</p>
              <h1 id="login-title" className="login-title">Welcome back.</h1>
              <p className="login-lede">Sign in to review your field activities.</p>
              {notFaculty ? (
                <Notice kind="error">
                  This dashboard is for faculty. You have been signed out. Student accounts use the FieldPass Android app.
                </Notice>
              ) : null}
              {submit.error ? <Notice kind="error">{submit.error}</Notice> : null}
              <form className="login-form" aria-labelledby="login-title" onSubmit={onSubmit} noValidate>
                <Field label="Username">
                  {(p) => <input id={p.id} value={username} autoComplete="username" autoCapitalize="none"
                    onChange={(e) => setUsername(e.target.value)} required />}
                </Field>
                <Field label="Password">
                  {(p) => <input id={p.id} type="password" value={password} autoComplete="current-password"
                    onChange={(e) => setPassword(e.target.value)} required />}
                </Field>
                <button type="submit" className="btn btn-primary" disabled={submit.isBusy || !username || !password}>
                  {submit.isBusy ? 'Signing in...' : 'Sign in'}
                </button>
              </form>
            </div>
            {USE_FIXTURES ? (
              <p className="login-foot muted">Fixture mode: sign in as faculty1 with any password. student1 shows the faculty-only message.</p>
            ) : null}
          </div>
        </div>
      </div>
    </>
  )
}

import { USE_FIXTURES } from './config'
import { isApiError } from './errors'
import { fixtureApi } from './fixtureClient'
import { realApi } from './realClient'
import type { Api } from './types'

export const UNAUTHENTICATED_EVENT = 'fieldpass:unauthenticated'

type AsyncFn = (...args: never[]) => Promise<unknown>

/** Wraps every call so a not_authenticated error (except on login) signals the app to return to login. */
function withAuthWatch(inner: Api): Api {
  const wrapped = {} as Record<string, AsyncFn>
  for (const key of Object.keys(inner) as (keyof Api)[]) {
    const fn = inner[key] as unknown as (...args: unknown[]) => Promise<unknown>
    wrapped[key] = (async (...args: unknown[]) => {
      try {
        return await fn(...args)
      } catch (err) {
        if (key !== 'login' && isApiError(err) && err.code === 'not_authenticated') {
          window.dispatchEvent(new Event(UNAUTHENTICATED_EVENT))
        }
        throw err
      }
    }) as AsyncFn
  }
  return wrapped as unknown as Api
}

/** The one API instance the app uses. Fixture mode is chosen at start time via VITE_USE_FIXTURES. */
export const api: Api = withAuthWatch(USE_FIXTURES ? fixtureApi : realApi)

export { USE_FIXTURES } from './config'
export { ApiError, isApiError, messageForError } from './errors'
export type * from './types'

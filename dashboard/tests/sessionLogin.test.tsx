import { act, renderHook } from '@testing-library/react'
import type { ReactNode } from 'react'
import { ApiError } from '../src/api'
import { getToken, setToken } from '../src/api/config'
import { SessionProvider, useSession } from '../src/hooks/useSession'

const login = vi.fn()

vi.mock('../src/api', async (orig) => {
  const real = await orig<typeof import('../src/api')>()
  return { ...real, api: { login: (...args: unknown[]) => login(...args), me: vi.fn() } }
})

const wrapper = ({ children }: { children: ReactNode }) => <SessionProvider>{children}</SessionProvider>

beforeEach(() => { setToken(null); login.mockReset() })

describe('session login with a student account', () => {
  it('treats device_key_required (the server wants a phone key) as a non-faculty account and stores no token', async () => {
    login.mockRejectedValue(new ApiError('device_key_required', 'Update the FieldPass app.', 422))
    const { result } = renderHook(() => useSession(), { wrapper })

    let outcome: unknown
    await act(async () => { outcome = await result.current.login('student1', 'pw') })

    expect(outcome).toEqual({ ok: false, reason: 'not_faculty' })
    expect(getToken()).toBeNull()
    expect(result.current.user).toBeNull()
  })

  it('still rethrows other login errors', async () => {
    login.mockRejectedValue(new ApiError('invalid_credentials', 'x', 401))
    const { result } = renderHook(() => useSession(), { wrapper })
    await act(async () => {
      await expect(result.current.login('student1', 'bad')).rejects.toMatchObject({ code: 'invalid_credentials' })
    })
    expect(getToken()).toBeNull()
  })
})

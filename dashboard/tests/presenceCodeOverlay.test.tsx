import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ApiError } from '../src/api'
import type { Activity, PresenceCode } from '../src/api'
import { PresenceCodeControl } from '../src/components/PresenceCodeControl'
import { PresenceCodeOverlay } from '../src/components/PresenceCodeOverlay'

const getPresenceCode = vi.hoisted(() => vi.fn<(id: string) => Promise<PresenceCode>>())

vi.mock('../src/api', async (orig) => {
  const real = await orig<typeof import('../src/api')>()
  return { ...real, api: { getPresenceCode } }
})
// The real QR needs no test: the mock exposes exactly what was handed to the renderer.
vi.mock('qrcode.react', () => ({
  QRCodeSVG: (props: { value: string; bgColor: string; fgColor: string; marginSize: number }) => (
    <svg data-testid="qr-svg" data-value={props.value} data-bg={props.bgColor} data-fg={props.fgColor} data-margin={props.marginSize} />
  ),
}))

/** A code that has `leftMs` of life on the server clock when it arrives. */
function codeWith(code: string, leftMs: number): PresenceCode {
  const serverNow = Date.parse('2026-06-01T10:00:00Z')
  return {
    code,
    server_time: new Date(serverNow).toISOString(),
    expires_at: new Date(serverNow + leftMs).toISOString(),
    step_seconds: 10,
  }
}

function setVisibility(state: 'visible' | 'hidden'): void {
  Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => state })
  document.dispatchEvent(new Event('visibilitychange'))
}
const tick = async (ms: number): Promise<void> => { await act(async () => { await vi.advanceTimersByTimeAsync(ms) }) }

const activity = { id: 'act_1', name: 'Site Visit' } as Activity

beforeEach(() => {
  vi.useFakeTimers({ shouldAdvanceTime: false })
  // Testing Library only drains its own async waits under fake timers when it sees a `jest` global.
  vi.stubGlobal('jest', { advanceTimersByTime: vi.advanceTimersByTime })
  setVisibility('visible')
  getPresenceCode.mockReset()
})
afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals() })

function renderOverlay(onClose = vi.fn()) {
  render(<PresenceCodeOverlay activityId="act_1" activityName="Site Visit" onClose={onClose} />)
  return onClose
}

describe('PresenceCodeOverlay', () => {
  it('shows the QR for exactly the code string, white background and dark modules, with a quiet zone', async () => {
    getPresenceCode.mockResolvedValue(codeWith('fp1.176000000.AbCd', 7000))
    renderOverlay()
    await tick(0)
    const qr = screen.getByTestId('qr-svg')
    expect(qr).toHaveAttribute('data-value', 'fp1.176000000.AbCd')
    expect(qr).toHaveAttribute('data-bg', '#ffffff')
    expect(qr).toHaveAttribute('data-fg', '#000000')
    expect(Number(qr.getAttribute('data-margin'))).toBeGreaterThanOrEqual(4)
    expect(getPresenceCode).toHaveBeenCalledWith('act_1')
  })

  it('is a labelled modal dialog with instructions and a text alternative for the QR', async () => {
    getPresenceCode.mockResolvedValue(codeWith('c1', 7000))
    renderOverlay()
    await tick(0)
    const dialog = screen.getByRole('dialog', { name: 'Presence code' })
    expect(dialog).toHaveAttribute('aria-modal', 'true')
    expect(screen.getByText('Scan this with the FieldPass app')).toBeInTheDocument()
    expect(screen.getByRole('img', { name: /QR code.*changes every 10 seconds/i })).toBeInTheDocument()
  })

  it('counts down from the server clock (expires_at minus server_time), updating as time passes', async () => {
    getPresenceCode.mockResolvedValue(codeWith('c1', 7000))
    renderOverlay()
    await tick(0)
    expect(screen.getByTestId('presence-countdown')).toHaveTextContent('7')
    await tick(3000)
    expect(screen.getByTestId('presence-countdown')).toHaveTextContent('4')
  })

  it('does not announce the countdown to screen readers', async () => {
    getPresenceCode.mockResolvedValue(codeWith('c1', 7000))
    renderOverlay()
    await tick(0)
    const countdown = screen.getByTestId('presence-countdown')
    expect(countdown.closest('[aria-live]')).toBeNull()
    expect(countdown.closest('[aria-hidden="true"]')).not.toBeNull()
  })

  it('fetches a new code when the current one expires and shows it', async () => {
    getPresenceCode
      .mockResolvedValueOnce(codeWith('first', 10_000))
      .mockResolvedValueOnce(codeWith('second', 10_000))
    renderOverlay()
    await tick(0)
    expect(screen.getByTestId('qr-svg')).toHaveAttribute('data-value', 'first')
    await tick(9_000)
    expect(getPresenceCode).toHaveBeenCalledTimes(1)
    await tick(1_400)
    expect(getPresenceCode).toHaveBeenCalledTimes(2)
    expect(screen.getByTestId('qr-svg')).toHaveAttribute('data-value', 'second')
  })

  it('never overlaps requests: a slow response is not followed by another until it settles', async () => {
    let resolveSecond: (c: PresenceCode) => void = () => undefined
    getPresenceCode
      .mockResolvedValueOnce(codeWith('first', 1000))
      .mockImplementationOnce(() => new Promise<PresenceCode>((r) => { resolveSecond = r }))
    renderOverlay()
    await tick(0)
    await tick(40_000)
    expect(getPresenceCode).toHaveBeenCalledTimes(2)
    await act(async () => { resolveSecond(codeWith('second', 10_000)) })
    expect(screen.getByTestId('qr-svg')).toHaveAttribute('data-value', 'second')
  })

  it('shows a clear message when the activity is not running, hides the QR, does not retry, and Close still works', async () => {
    getPresenceCode.mockRejectedValue(new ApiError('outside_activity_window', 'closed', 409))
    const onClose = renderOverlay()
    await tick(0)
    expect(screen.getByRole('alert')).toHaveTextContent(/not running/i)
    expect(screen.queryByTestId('qr-svg')).not.toBeInTheDocument()
    await tick(60_000)
    expect(getPresenceCode).toHaveBeenCalledTimes(1)
    await userEvent.setup({ delay: null }).click(screen.getByRole('button', { name: 'Close' }))
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('keeps a code the server still accepts on screen through a network blip, and retries soon', async () => {
    getPresenceCode
      .mockResolvedValueOnce(codeWith('first', 1000))
      .mockRejectedValueOnce(new ApiError('network_error', 'down'))
      .mockResolvedValueOnce(codeWith('back', 10_000))
    renderOverlay()
    await tick(0)
    await tick(1_400)
    expect(getPresenceCode).toHaveBeenCalledTimes(2)
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(screen.getByTestId('qr-svg')).toHaveAttribute('data-value', 'first')
    await tick(2_100)
    expect(screen.getByTestId('qr-svg')).toHaveAttribute('data-value', 'back')
  })

  it('hides the code and says so once the network has been down longer than the server accepts it', async () => {
    getPresenceCode
      .mockResolvedValueOnce(codeWith('first', 1000))
      .mockRejectedValue(new ApiError('network_error', 'down'))
    renderOverlay()
    await tick(0)
    await tick(10_000) // 1 s of life plus one more 10 s step: accepted until 11 s
    expect(screen.getByTestId('qr-svg')).toHaveAttribute('data-value', 'first')
    await tick(3_000)
    expect(screen.getByRole('alert')).toBeInTheDocument()
    expect(screen.queryByTestId('qr-svg')).not.toBeInTheDocument()
    getPresenceCode.mockReset()
    getPresenceCode.mockResolvedValue(codeWith('back', 10_000))
    await tick(5_100)
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(screen.getByTestId('qr-svg')).toHaveAttribute('data-value', 'back')
  })

  it('"Try again" requests a code immediately after a definitive error', async () => {
    getPresenceCode
      .mockRejectedValueOnce(new ApiError('outside_activity_window', 'closed', 409))
      .mockResolvedValueOnce(codeWith('ok', 10_000))
    renderOverlay()
    await tick(0)
    await userEvent.setup({ delay: null }).click(screen.getByRole('button', { name: 'Try again' }))
    await tick(0)
    expect(screen.getByTestId('qr-svg')).toHaveAttribute('data-value', 'ok')
  })

  it('stops fetching when the tab is hidden and fetches again when it is visible', async () => {
    getPresenceCode.mockResolvedValue(codeWith('c', 2000))
    renderOverlay()
    await tick(0)
    expect(getPresenceCode).toHaveBeenCalledTimes(1)
    act(() => setVisibility('hidden'))
    await tick(60_000)
    expect(getPresenceCode).toHaveBeenCalledTimes(1)
    act(() => setVisibility('visible'))
    await tick(0)
    expect(getPresenceCode).toHaveBeenCalledTimes(2)
  })

  it('stops fetching after it is closed (unmounted)', async () => {
    getPresenceCode.mockResolvedValue(codeWith('c', 2000))
    render(<PresenceCodeControl activity={activity} />)
    const user = userEvent.setup({ delay: null })
    await user.click(screen.getByRole('button', { name: 'Show presence code' }))
    await tick(0)
    const callsWhileOpen = getPresenceCode.mock.calls.length
    await user.click(screen.getByRole('button', { name: 'Close' }))
    await tick(60_000)
    expect(getPresenceCode).toHaveBeenCalledTimes(callsWhileOpen)
  })

  it('ignores a response that arrives after close', async () => {
    let resolveIt: (c: PresenceCode) => void = () => undefined
    getPresenceCode.mockImplementation(() => new Promise<PresenceCode>((r) => { resolveIt = r }))
    const { unmount } = render(<PresenceCodeOverlay activityId="act_1" activityName="Site Visit" onClose={() => undefined} />)
    unmount()
    await act(async () => { resolveIt(codeWith('late', 10_000)) })
    await tick(60_000)
    expect(getPresenceCode).toHaveBeenCalledTimes(1)
  })
})

describe('PresenceCodeOverlay keyboard and focus', () => {
  it('moves focus into the dialog, Escape closes it, and Tab stays inside', async () => {
    getPresenceCode.mockResolvedValue(codeWith('c1', 7000))
    const onClose = renderOverlay()
    await tick(0)
    const dialog = screen.getByRole('dialog')
    expect(dialog).toContainElement(document.activeElement as HTMLElement)
    const user = userEvent.setup({ delay: null })
    await user.tab()
    expect(screen.getByRole('button', { name: 'Close' })).toHaveFocus()
    await user.tab()
    expect(dialog).toContainElement(document.activeElement as HTMLElement)
    await user.keyboard('{Escape}')
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('returns focus to the "Show presence code" button after closing', async () => {
    getPresenceCode.mockResolvedValue(codeWith('c1', 7000))
    render(<PresenceCodeControl activity={activity} />)
    const user = userEvent.setup({ delay: null })
    const opener = screen.getByRole('button', { name: 'Show presence code' })
    await user.click(opener)
    await tick(0)
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    await user.keyboard('{Escape}')
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(opener).toHaveFocus()
  })

  it('opens from the keyboard', async () => {
    getPresenceCode.mockResolvedValue(codeWith('c1', 7000))
    render(<PresenceCodeControl activity={activity} />)
    const user = userEvent.setup({ delay: null })
    await user.tab()
    await user.keyboard('{Enter}')
    expect(screen.getByRole('dialog', { name: 'Presence code' })).toBeInTheDocument()
  })
})

const BASE_URL_KEY = 'fieldpass.apiBaseUrl'
const TOKEN_KEY = 'fieldpass.token'
const DEFAULT_BASE_URL = 'http://localhost:8000'

export const USE_FIXTURES: boolean = import.meta.env.VITE_USE_FIXTURES === 'true'

export function normalizeBaseUrl(raw: string): string {
  return raw.trim().replace(/\/+$/, '')
}

export function isValidBaseUrl(raw: string): boolean {
  try {
    const url = new URL(normalizeBaseUrl(raw))
    return url.protocol === 'http:' || url.protocol === 'https:'
  } catch {
    return false
  }
}

export function getBaseUrl(): string {
  const fromEnv = import.meta.env.VITE_API_BASE_URL
  // A deployed dashboard must use its configured API, even if this browser has an old local test address.
  // Without one, use its own origin so a hosting rewrite can serve /api/v1.
  if (import.meta.env.PROD) return normalizeBaseUrl(fromEnv || window.location.origin)
  try {
    const saved = localStorage.getItem(BASE_URL_KEY)
    if (saved && isValidBaseUrl(saved)) return normalizeBaseUrl(saved)
  } catch {
    // storage unavailable: fall through to the env default
  }
  return normalizeBaseUrl(fromEnv && fromEnv.length > 0 ? fromEnv : DEFAULT_BASE_URL)
}

export function saveBaseUrl(raw: string): void {
  try {
    localStorage.setItem(BASE_URL_KEY, normalizeBaseUrl(raw))
  } catch {
    // ignore: the address then only lasts for this page load
  }
}

// Token: in memory first, mirrored to sessionStorage so a reload keeps the session.
let memoryToken: string | null = null

export function getToken(): string | null {
  if (memoryToken) return memoryToken
  try {
    memoryToken = sessionStorage.getItem(TOKEN_KEY)
  } catch {
    memoryToken = null
  }
  return memoryToken
}

export function setToken(token: string | null): void {
  memoryToken = token
  try {
    if (token) sessionStorage.setItem(TOKEN_KEY, token)
    else sessionStorage.removeItem(TOKEN_KEY)
  } catch {
    // memory copy still works
  }
}

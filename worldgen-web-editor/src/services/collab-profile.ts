const SESSION_KEY = 'nodeEditor:collabSessionId'
const NAME_KEY = 'nodeEditor:collabName'
const COLOR_KEY = 'nodeEditor:collabColor'

const PALETTE = [
  '#ef4444',
  '#f97316',
  '#eab308',
  '#22c55e',
  '#14b8a6',
  '#3b82f6',
  '#8b5cf6',
  '#ec4899',
  '#06b6d4',
  '#84cc16',
]

function randomId(): string {
  if (typeof crypto !== 'undefined' && crypto.randomUUID) {
    return crypto.randomUUID()
  }
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0
    const v = c === 'x' ? r : (r & 0x3) | 0x8
    return v.toString(16)
  })
}

export function getOrCreateCollabSessionId(): string {
  try {
    const existing = localStorage.getItem(SESSION_KEY)
    if (existing && /^[0-9a-f-]{36}$/i.test(existing)) {
      return existing
    }
    const id = randomId()
    localStorage.setItem(SESSION_KEY, id)
    return id
  } catch {
    return randomId()
  }
}

function defaultNameForSession(sessionId: string): string {
  const tail = sessionId.replace(/-/g, '').slice(-4)
  return `Editor ${tail}`
}

function pickDefaultColor(): string {
  return PALETTE[Math.floor(Math.random() * PALETTE.length)]!
}

export interface CollabProfile {
  sessionId: string
  name: string
  color: string
}

export function loadCollabProfile(): CollabProfile {
  const sessionId = getOrCreateCollabSessionId()
  let name = defaultNameForSession(sessionId)
  let color = pickDefaultColor()
  try {
    const n = localStorage.getItem(NAME_KEY)
    if (n != null && n.trim().length > 0 && n.length <= 64) name = n.trim()
    const c = localStorage.getItem(COLOR_KEY)
    if (c != null && /^#[0-9a-fA-F]{6}$/.test(c.trim())) color = c.trim()
  } catch {
    /* ignore */
  }
  return { sessionId, name, color }
}

export function persistCollabProfile(name: string, color: string): CollabProfile {
  const sessionId = getOrCreateCollabSessionId()
  const n = name.trim().slice(0, 64) || defaultNameForSession(sessionId)
  let c = color.trim()
  if (!/^#[0-9a-fA-F]{6}$/.test(c)) c = pickDefaultColor()
  try {
    localStorage.setItem(NAME_KEY, n)
    localStorage.setItem(COLOR_KEY, c)
  } catch {
    /* ignore */
  }
  return { sessionId, name: n, color: c }
}

export const collabColorPalette = PALETTE

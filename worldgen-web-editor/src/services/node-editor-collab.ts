import { nodeEditorBackendOrigin } from '@/services/node-editor-backend'
import type { CollabProfile } from '@/services/collab-profile'

export type CollabRosterParticipant = { sessionId: string; name: string; color: string }

export type CollabInboundMessage =
  | { type: 'hello' }
  | { type: 'roster'; participants: CollabRosterParticipant[] }
  | { type: 'cursor'; from: string; x: number; y: number; active?: boolean }
  | { type: 'flowViewport'; from: string; x: number; y: number; zoom: number }
  | {
      type: 'worldViewport'
      from: string
      worldName: string
      centerX: number
      centerZ: number
      radius: number
    }
  | { type: 'graph'; from: string; rev: number; graph: unknown }

type CollabListener = (msg: CollabInboundMessage) => void

let eventSource: EventSource | null = null
let sessionId: string | null = null
let listener: CollabListener | null = null

function apiOrigin(): string {
  return nodeEditorBackendOrigin()
}

function encodeParams(params: Record<string, string>): string {
  return new URLSearchParams(params).toString()
}

export function isCollabConnected(): boolean {
  return eventSource != null && eventSource.readyState === EventSource.OPEN
}

export async function pushCollabProfile(profile: CollabProfile): Promise<void> {
  const q = encodeParams({
    sessionId: profile.sessionId,
    name: profile.name,
    color: profile.color,
  })
  await fetch(`${apiOrigin()}/nodeeditor/collab/profile?${q}`, { method: 'POST' })
}

export function publishCollabPayload(payload: Record<string, unknown>): void {
  if (!sessionId || !isCollabConnected()) return
  const body = JSON.stringify(payload)
  void fetch(`${apiOrigin()}/nodeeditor/collab/publish?${encodeParams({ sessionId })}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body,
  }).catch(() => {
    /* ignore */
  })
}

function parseCollabData(raw: string): CollabInboundMessage | null {
  try {
    const o = JSON.parse(raw) as Record<string, unknown>
    const t = o.type
    if (t === 'hello') return { type: 'hello' }
    if (t === 'roster' && Array.isArray(o.participants)) {
      const participants = (o.participants as unknown[])
        .map((p) => {
          if (!p || typeof p !== 'object') return null
          const r = p as Record<string, unknown>
          const sid = typeof r.sessionId === 'string' ? r.sessionId : ''
          const name = typeof r.name === 'string' ? r.name : ''
          const color = typeof r.color === 'string' ? r.color : '#888'
          if (!sid) return null
          return { sessionId: sid, name, color }
        })
        .filter((x): x is CollabRosterParticipant => x != null)
      return { type: 'roster', participants }
    }
    const from = typeof o.from === 'string' ? o.from : ''
    if (t === 'cursor' && from) {
      const x = Number(o.x)
      const y = Number(o.y)
      if (!Number.isFinite(x) || !Number.isFinite(y)) return null
      return { type: 'cursor', from, x, y, active: o.active === true }
    }
    if (t === 'flowViewport' && from) {
      const x = Number(o.x)
      const y = Number(o.y)
      const zoom = Number(o.zoom)
      if (!Number.isFinite(x) || !Number.isFinite(y) || !Number.isFinite(zoom)) return null
      return { type: 'flowViewport', from, x, y, zoom }
    }
    if (t === 'worldViewport' && from) {
      const worldName = typeof o.worldName === 'string' ? o.worldName : ''
      const centerX = Number(o.centerX)
      const centerZ = Number(o.centerZ)
      const radius = Number(o.radius)
      if (!Number.isFinite(centerX) || !Number.isFinite(centerZ) || !Number.isFinite(radius)) return null
      return { type: 'worldViewport', from, worldName, centerX, centerZ, radius }
    }
    if (t === 'graph' && from && o.graph !== undefined) {
      const rev = Number(o.rev)
      if (!Number.isFinite(rev)) return null
      return { type: 'graph', from, rev, graph: o.graph }
    }
    return null
  } catch {
    return null
  }
}

export function startNodeEditorCollab(
  sid: string,
  profile: CollabProfile,
  onMessage: CollabListener,
  onConnectionChange: (open: boolean) => void
): void {
  stopNodeEditorCollab()
  sessionId = sid
  listener = onMessage
  const url = `${apiOrigin()}/nodeeditor/collab/stream?${encodeParams({ sessionId: sid })}`
  const es = new EventSource(url)
  eventSource = es

  es.addEventListener('open', () => {
    onConnectionChange(true)
    void pushCollabProfile(profile)
  })

  es.addEventListener('collab', (ev: MessageEvent) => {
    const msg = parseCollabData(String(ev.data))
    if (msg && listener) listener(msg)
  })

  es.onerror = () => {
    onConnectionChange(false)
  }
}

export function stopNodeEditorCollab(): void {
  if (eventSource) {
    eventSource.close()
    eventSource = null
  }
  sessionId = null
  listener = null
}

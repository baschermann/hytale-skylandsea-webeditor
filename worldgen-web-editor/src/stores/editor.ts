import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { DensityTraceResponse } from '@/services/density-nodes'
import { fetchDensityTrace, invalidateDensityCache } from '@/services/density-nodes'
import { nodeEditorBackendOrigin } from '@/services/node-editor-backend'
import type { CollabRosterParticipant } from '@/services/node-editor-collab'

export interface CollabPresenceEntry {
  cursor?: { x: number; y: number; active?: boolean }
  flowViewport?: { x: number; y: number; zoom: number }
  worldViewport?: { worldName: string; centerX: number; centerZ: number; radius: number }
}

const GRAPH_PATH_KEY = 'nodeEditor:graphPath'
const SELECTED_WORLD_KEY = 'nodeEditor:selectedWorld'
/** Shared “radius in chunks” for configure viewport and 3D render distance (1–32). */
const CHUNK_RENDER_RADIUS_KEY = 'nodeEditor:chunkRenderRadius'
const LEGACY_3D_RENDER_DISTANCE_KEY = 'density-3d-render-distance-chunks'
const VIEWPORT_PREFS_KEY = 'nodeEditor:viewportPrefs'
/** Chunk indices for viewport / 3D field center (synced with TopBar and DensityNode3DView). */
const VIEWPORT_CENTER_CHUNKS_KEY = 'nodeEditor:viewportCenterChunks'
const DENSITY_3D_TARGET_FPS_KEY = 'density-3d-target-fps'
const DENSITY_3D_AUTO_RESOLUTION_KEY = 'density-3d-auto-resolution'
const DENSITY_3D_MIN_RESOLUTION_KEY = 'density-3d-min-resolution'
/** Same key as legacy DensityNode3DView session flag; now persisted via store. */
const DENSITY_3D_HEIGHT_HELPERS_KEY = 'density-3d-height-helpers'
const TARGET_FPS_MIN = 30
const TARGET_FPS_MAX = 240
const MIN_RESOLUTION_PCT_MIN = 5
const MIN_RESOLUTION_PCT_MAX = 100

function getScreenRefreshRate(): number {
  if (typeof window === 'undefined') return 60
  const rate = (window.screen as { refreshRate?: number }).refreshRate
  if (typeof rate === 'number' && rate >= TARGET_FPS_MIN && rate <= TARGET_FPS_MAX) return Math.round(rate)
  return 60
}

function getSavedTargetFps(): number {
  try {
    const raw = localStorage.getItem(DENSITY_3D_TARGET_FPS_KEY)
    if (raw == null) return getScreenRefreshRate()
    const n = parseInt(raw, 10)
    if (!Number.isFinite(n)) return getScreenRefreshRate()
    return Math.max(TARGET_FPS_MIN, Math.min(TARGET_FPS_MAX, n))
  } catch {
    return getScreenRefreshRate()
  }
}

function getSavedAutoResolution(): boolean {
  try {
    const raw = localStorage.getItem(DENSITY_3D_AUTO_RESOLUTION_KEY)
    if (raw == null) return true
    return raw === 'true'
  } catch {
    return true
  }
}

function getSavedMinResolutionPct(): number {
  try {
    const raw = localStorage.getItem(DENSITY_3D_MIN_RESOLUTION_KEY)
    if (raw == null) return 5
    const n = parseInt(raw, 10)
    if (!Number.isFinite(n)) return 5
    return Math.max(MIN_RESOLUTION_PCT_MIN, Math.min(MIN_RESOLUTION_PCT_MAX, n))
  } catch {
    return 5
  }
}

function getSavedShowHeightHelpers(): boolean {
  try {
    const ls = localStorage.getItem(DENSITY_3D_HEIGHT_HELPERS_KEY)
    if (ls === '1') return true
    if (ls === '0') return false
    const ss = sessionStorage.getItem(DENSITY_3D_HEIGHT_HELPERS_KEY)
    if (ss === '1') {
      try {
        localStorage.setItem(DENSITY_3D_HEIGHT_HELPERS_KEY, '1')
      } catch {}
      return true
    }
    return false
  } catch {
    return false
  }
}

function getStoredGraphPath(): string {
  try {
    return localStorage.getItem(GRAPH_PATH_KEY) ?? ''
  } catch {
    return ''
  }
}

function getStoredSelectedWorld(): string {
  try {
    return localStorage.getItem(SELECTED_WORLD_KEY) ?? ''
  } catch {
    return ''
  }
}

const CHUNK_RENDER_RADIUS_MIN = 1
const CHUNK_RENDER_RADIUS_MAX = 32

function clampChunkRenderRadius(n: number): number {
  return Math.max(CHUNK_RENDER_RADIUS_MIN, Math.min(CHUNK_RENDER_RADIUS_MAX, Math.round(n)))
}

function getSavedChunkRenderRadius(): number {
  try {
    const raw = localStorage.getItem(CHUNK_RENDER_RADIUS_KEY)
    if (raw != null) {
      const n = parseInt(raw, 10)
      if (Number.isFinite(n)) return clampChunkRenderRadius(n)
    }
    const leg = localStorage.getItem(LEGACY_3D_RENDER_DISTANCE_KEY)
    if (leg != null) {
      const n = parseInt(leg, 10)
      if (Number.isFinite(n)) return clampChunkRenderRadius(n)
    }
    const prefsRaw = localStorage.getItem(VIEWPORT_PREFS_KEY)
    if (prefsRaw) {
      const o = JSON.parse(prefsRaw) as Record<string, unknown>
      const r = Number(o.radius)
      if (Number.isFinite(r)) return clampChunkRenderRadius(r)
    }
  } catch {
    /* ignore */
  }
  return 3
}

function getSavedViewportCenterChunks(): { x: number; z: number } {
  try {
    const raw = localStorage.getItem(VIEWPORT_CENTER_CHUNKS_KEY)
    if (raw) {
      const o = JSON.parse(raw) as Record<string, unknown>
      const x = Number(o.x)
      const z = Number(o.z)
      if (Number.isFinite(x) && Number.isFinite(z)) {
        return { x: Math.round(x), z: Math.round(z) }
      }
    }
    const prefsRaw = localStorage.getItem(VIEWPORT_PREFS_KEY)
    if (prefsRaw) {
      const o = JSON.parse(prefsRaw) as Record<string, unknown>
      const x = Number(o.centerX)
      const z = Number(o.centerZ)
      if (Number.isFinite(x) && Number.isFinite(z)) {
        return { x: Math.round(x), z: Math.round(z) }
      }
    }
  } catch {
    /* ignore */
  }
  return { x: 0, z: 0 }
}

function persistViewportCenterChunks(x: number, z: number) {
  try {
    localStorage.setItem(VIEWPORT_CENTER_CHUNKS_KEY, JSON.stringify({ x, z }))
  } catch {
    /* ignore */
  }
}

export const useEditorStore = defineStore('editor', () => {
  const triggerSave = ref(0)
  const triggerLoad = ref(0)
  /** Bumped when server sends density/chunk updates; 3D view watches this to refetch. */
  const densityUpdateSignal = ref(0)
  /** Per-node min/max metrics from chunk generation (populated via SSE). */
  const nodeMetrics = ref<Record<string, { min: number; max: number }>>({})

  /** 3D view: target FPS for adaptive resolution (persisted). */
  const density3dTargetFps = ref(getSavedTargetFps())
  /** 3D view: settings menu open (only relevant when density viewer route is active). */
  const density3dSettingsMenuOpen = ref(false)
  /** 3D view: enable automatic resolution scaling (persisted). */
  const density3dAutoResolution = ref(getSavedAutoResolution())
  /** 3D view: minimum resolution as percentage 5–100 (persisted). Stored as integer; use as scale via value/100. */
  const density3dMinResolutionPct = ref(getSavedMinResolutionPct())
  /** 3D view: show y=0 / y=320 grids and corner posts (persisted). */
  const density3dShowHeightHelpers = ref(getSavedShowHeightHelpers())

  /** Chunk radius: half-width in chunks from center; synced with configure viewport and 3D render distance. */
  const chunkRenderRadius = ref(getSavedChunkRenderRadius())

  function setChunkRenderRadius(value: number) {
    const c = clampChunkRenderRadius(value)
    chunkRenderRadius.value = c
    try {
      localStorage.setItem(CHUNK_RENDER_RADIUS_KEY, String(c))
    } catch {}
    return c
  }

  const c0 = getSavedViewportCenterChunks()
  const viewportCenterChunkX = ref(c0.x)
  const viewportCenterChunkZ = ref(c0.z)

  function setViewportCenterChunkX(value: number) {
    const x = Math.round(Number(value))
    if (!Number.isFinite(x)) return viewportCenterChunkX.value
    viewportCenterChunkX.value = x
    persistViewportCenterChunks(x, viewportCenterChunkZ.value)
    return x
  }

  function setViewportCenterChunkZ(value: number) {
    const z = Math.round(Number(value))
    if (!Number.isFinite(z)) return viewportCenterChunkZ.value
    viewportCenterChunkZ.value = z
    persistViewportCenterChunks(viewportCenterChunkX.value, z)
    return z
  }

  function setDensity3dTargetFps(value: number) {
    const clamped = Math.max(TARGET_FPS_MIN, Math.min(TARGET_FPS_MAX, Math.round(value)))
    density3dTargetFps.value = clamped
    try {
      localStorage.setItem(DENSITY_3D_TARGET_FPS_KEY, String(clamped))
    } catch {}
    return clamped
  }

  function setDensity3dAutoResolution(value: boolean) {
    density3dAutoResolution.value = value
    try {
      localStorage.setItem(DENSITY_3D_AUTO_RESOLUTION_KEY, value ? 'true' : 'false')
    } catch {}
  }

  function setDensity3dMinResolutionPct(value: number) {
    const clamped = Math.max(MIN_RESOLUTION_PCT_MIN, Math.min(MIN_RESOLUTION_PCT_MAX, Math.round(value)))
    density3dMinResolutionPct.value = clamped
    try {
      localStorage.setItem(DENSITY_3D_MIN_RESOLUTION_KEY, String(clamped))
    } catch {}
    return clamped
  }

  function setDensity3dShowHeightHelpers(value: boolean) {
    density3dShowHeightHelpers.value = value
    try {
      localStorage.setItem(DENSITY_3D_HEIGHT_HELPERS_KEY, value ? '1' : '0')
    } catch {}
  }

  const isConnected = ref(false)
  const isConnecting = ref(false)

  /** Selected world (top bar dropdown); changes do not trigger generation unless a 3D view is mounted. */
  const selectedWorldName = ref<string>(getStoredSelectedWorld())

  function setSelectedWorldName(name: string) {
    const next = typeof name === 'string' ? name : ''
    selectedWorldName.value = next
    try {
      localStorage.setItem(SELECTED_WORLD_KEY, next)
    } catch {}
  }

  /** Mounted `DensityNode3DView` instances. While > 0 the store keeps viewport refreshed on world/center/radius change. */
  const activeDensityViewCount = ref(0)

  function incDensityViewActive() {
    activeDensityViewCount.value++
  }

  function decDensityViewActive() {
    activeDensityViewCount.value = Math.max(0, activeDensityViewCount.value - 1)
    if (activeDensityViewCount.value === 0) {
      // Release the dedupe key so re-opening a 3D view after external changes (Save, asset reload) regenerates.
      lastRefreshedFootprint = null
    }
  }

  /**
   * POST `/viewport/refresh` with the current world + center + radius, deduplicating repeat requests for
   * the same footprint. Debounced by the caller / store watchers (not by this function).
   *
   * Server side: resets debug metrics, bumps debug version, runs chunk regeneration, then fires a single
   * SSE tick when the wave settles (or via a fallback timer when all chunks were cached).
   */
  let viewportRefreshInFlight: Promise<boolean> | null = null
  let lastRefreshedFootprint: string | null = null

  function viewportFootprintKey(): string {
    return `${selectedWorldName.value}|${viewportCenterChunkX.value}|${viewportCenterChunkZ.value}|${chunkRenderRadius.value}`
  }

  async function triggerViewportRefreshIfNeeded(options?: { force?: boolean }): Promise<boolean> {
    const world = selectedWorldName.value.trim()
    if (!world) return false
    if (viewportRefreshInFlight) return viewportRefreshInFlight
    const key = viewportFootprintKey()
    if (!options?.force && lastRefreshedFootprint === key) return false

    const params = new URLSearchParams({
      worldName: world,
      centerX: String(viewportCenterChunkX.value),
      centerZ: String(viewportCenterChunkZ.value),
      radius: String(chunkRenderRadius.value),
    })
    const promise = (async () => {
      try {
        const res = await fetch(`${nodeEditorBackendOrigin()}/viewport/refresh?${params}`, { method: 'POST' })
        if (!res.ok) {
          try {
            const err = await res.json() as { error?: string }
            if (err?.error) showToast(err.error)
          } catch {}
          return false
        }
        lastRefreshedFootprint = key
        invalidateDensityCache()
        return true
      } catch (e: unknown) {
        const msg = e instanceof Error ? e.message : 'network error'
        showToast('Viewport refresh failed: ' + msg)
        return false
      } finally {
        viewportRefreshInFlight = null
      }
    })()
    viewportRefreshInFlight = promise
    return promise
  }

  /** Forgets the dedupe key so the next {@link triggerViewportRefreshIfNeeded} call regenerates. */
  function invalidateViewportFootprintCache() {
    lastRefreshedFootprint = null
  }

  /** Node editor live collaboration (separate SSE from density metrics). */
  const collabLive = ref(false)
  const collabRoster = ref<CollabRosterParticipant[]>([])
  const collabPresence = ref<Record<string, CollabPresenceEntry>>({})

  function setCollabLive(value: boolean) {
    collabLive.value = value
    if (!value) {
      collabRoster.value = []
      collabPresence.value = {}
    }
  }

  function setCollabRoster(participants: CollabRosterParticipant[]) {
    collabRoster.value = participants
    const ids = new Set(participants.map((p) => p.sessionId))
    const next: Record<string, CollabPresenceEntry> = { ...collabPresence.value }
    for (const k of Object.keys(next)) {
      if (!ids.has(k)) {
        delete next[k]
      }
    }
    collabPresence.value = next
  }

  function patchCollabPresence(sessionId: string, patch: Partial<CollabPresenceEntry>) {
    collabPresence.value = {
      ...collabPresence.value,
      [sessionId]: { ...collabPresence.value[sessionId], ...patch },
    }
  }

  /** Incremented when TopBar updates collaboration name/color so NodeEditor can refresh + push profile. */
  const collabProfileRevision = ref(0)
  function bumpCollabProfileRevision() {
    collabProfileRevision.value++
  }

  /** Path for graph load/save (set via Load or query); persisted in localStorage. Empty until user loads a file or URL has ?graph=. */
  const graphPath = ref(getStoredGraphPath())
  function setGraphPath(path: string) {
    graphPath.value = path
    try {
      localStorage.setItem(GRAPH_PATH_KEY, path)
    } catch {}
  }

  const toastMessage = ref('')
  let toastTimer: ReturnType<typeof setTimeout> | null = null

  /** Incremented when save completes successfully; TopBar uses it to animate the Save button. */
  const saveSuccessTrigger = ref(0)
  /** Node ID that just had density/mesh data loaded; GenericNode uses it to animate View 3D button. */
  const densityDataLoadedNodeId = ref<string | null>(null)
  let densityDataLoadedTimer: ReturnType<typeof setTimeout> | null = null

  /** Matching graph node id for trace list ↔ graph sync. */
  const densityTraceHighlightNodeId = ref<string | null>(null)
  /** `'list'` = hover from trace table (highlight graph node); `'graph'` = hover from graph (list only). */
  const densityTraceHighlightSource = ref<'list' | 'graph' | null>(null)

  /** Density pick/trace (lives in node editor; persists when 3D overlay is closed). */
  const densityTraceNodeId = ref<string | null>(null)
  const densityTracePickedWorld = ref<{ x: number; y: number; z: number } | null>(null)
  const densityTraceLoading = ref(false)
  const densityTraceError = ref<string | null>(null)
  const densityTraceData = ref<DensityTraceResponse | null>(null)

  function showToast(message: string, durationMs = 2800) {
    toastMessage.value = message
    if (toastTimer) clearTimeout(toastTimer)
    toastTimer = setTimeout(() => {
      toastMessage.value = ''
      toastTimer = null
    }, durationMs)
  }

  function save() {
    triggerSave.value++
  }

  function triggerSaveSuccess() {
    saveSuccessTrigger.value++
  }

  function signalDensityDataLoaded(nodeId: string) {
    densityDataLoadedNodeId.value = nodeId
    if (densityDataLoadedTimer) clearTimeout(densityDataLoadedTimer)
    densityDataLoadedTimer = setTimeout(() => {
      densityDataLoadedNodeId.value = null
      densityDataLoadedTimer = null
    }, 2000)
  }

  function load() {
    triggerLoad.value++
  }

  function bumpDensityUpdate() {
    densityUpdateSignal.value++
  }

  function setDensityTraceHighlightNodeId(id: string | null, source?: 'list' | 'graph') {
    densityTraceHighlightNodeId.value = id
    densityTraceHighlightSource.value = id == null ? null : (source ?? 'list')
  }

  async function runDensityTraceAt(
    nodeId: string,
    x: number,
    y: number,
    z: number,
    options?: { silent?: boolean }
  ) {
    const silent = options?.silent === true
    if (!silent) densityTraceLoading.value = true
    densityTraceError.value = null
    try {
      densityTraceData.value = await fetchDensityTrace(nodeId, x, y, z)
      densityTraceNodeId.value = nodeId
      densityTracePickedWorld.value = { x, y, z }
    } catch (e: unknown) {
      densityTraceData.value = null
      densityTraceError.value = e instanceof Error ? e.message : String(e)
    } finally {
      if (!silent) densityTraceLoading.value = false
    }
  }

  function recordDensityTracePick(nodeId: string, x: number, y: number, z: number) {
    densityTraceNodeId.value = nodeId
    densityTracePickedWorld.value = { x, y, z }
    void runDensityTraceAt(nodeId, x, y, z)
  }

  function clearDensityTrace() {
    densityTraceNodeId.value = null
    densityTracePickedWorld.value = null
    densityTraceLoading.value = false
    densityTraceError.value = null
    densityTraceData.value = null
    densityTraceHighlightNodeId.value = null
    densityTraceHighlightSource.value = null
  }

  return {
    triggerSave,
    triggerLoad,
    saveSuccessTrigger,
    densityDataLoadedNodeId,
    densityTraceHighlightNodeId,
    densityTraceHighlightSource,
    densityTraceNodeId,
    densityTracePickedWorld,
    densityTraceLoading,
    densityTraceError,
    densityTraceData,
    runDensityTraceAt,
    recordDensityTracePick,
    clearDensityTrace,
    triggerSaveSuccess,
    signalDensityDataLoaded,
    setDensityTraceHighlightNodeId,
    densityUpdateSignal,
    nodeMetrics,
    density3dTargetFps,
    density3dSettingsMenuOpen,
    density3dAutoResolution,
    density3dMinResolutionPct,
    density3dShowHeightHelpers,
    setDensity3dTargetFps,
    setDensity3dAutoResolution,
    setDensity3dMinResolutionPct,
    setDensity3dShowHeightHelpers,
    chunkRenderRadius,
    setChunkRenderRadius,
    viewportCenterChunkX,
    viewportCenterChunkZ,
    setViewportCenterChunkX,
    setViewportCenterChunkZ,
    CHUNK_RENDER_RADIUS_MIN,
    CHUNK_RENDER_RADIUS_MAX,
    TARGET_FPS_MIN,
    TARGET_FPS_MAX,
    MIN_RESOLUTION_PCT_MIN,
    MIN_RESOLUTION_PCT_MAX,
    isConnected,
    isConnecting,
    selectedWorldName,
    setSelectedWorldName,
    activeDensityViewCount,
    incDensityViewActive,
    decDensityViewActive,
    triggerViewportRefreshIfNeeded,
    invalidateViewportFootprintCache,
    graphPath,
    setGraphPath,
    toastMessage,
    showToast,
    save,
    load,
    bumpDensityUpdate,
    setConnected: (status: boolean) => {
      isConnected.value = status
      if (status) isConnecting.value = false
    },
    setConnecting: (status: boolean) => {
      isConnecting.value = status
    },
    collabLive,
    collabRoster,
    collabPresence,
    setCollabLive,
    setCollabRoster,
    patchCollabPresence,
    collabProfileRevision,
    bumpCollabProfileRevision,
  }
})

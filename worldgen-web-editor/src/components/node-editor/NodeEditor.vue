<script setup lang="ts">
import { ref, onMounted, onUnmounted, markRaw, computed, watch, nextTick } from 'vue'
import { VueFlow, useVueFlow } from '@vue-flow/core'
import type { Connection, Node, Edge, OnConnectStartParams, NodeChange, EdgeChange, ViewportTransform } from '@vue-flow/core'
import { Background } from '@vue-flow/background'
import { Controls } from '@vue-flow/controls'
import { loadNodes, type NodeDefinition, type Workspace } from '@/services/node-loader'
import { invalidateDensityCache, fetchDensityDebugSnapshot } from '@/services/density-nodes'
import { nodeEditorBackendOrigin } from '@/services/node-editor-backend'
import { parseGraph, serializeGraph, type GraphMetadataPassthrough } from '@/services/graph-loader'
import { loadCollabProfile, type CollabProfile } from '@/services/collab-profile'
import {
  startNodeEditorCollab,
  stopNodeEditorCollab,
  publishCollabPayload,
  pushCollabProfile,
  type CollabInboundMessage,
} from '@/services/node-editor-collab'
import { useEditorStore } from '@/stores/editor'
import { storeToRefs } from 'pinia'
import GenericNode from './GenericNode.vue'
import GraphGroupNode from './GraphGroupNode.vue'
import EditorCommentNode, { type EditorCommentNodeData } from './EditorCommentNode.vue'
import NodeSearchDialog from './NodeSearchDialog.vue'

// Import Vue Flow styles
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import '@vue-flow/controls/dist/style.css'

interface CustomNodeData {
  definition: NodeDefinition;
  values: Record<string, string | number | boolean>;
  displayTitle?: string;
  description?: string;
}

interface EditorGroupNodeData {
  name: string
  /** `true`: drag moves overlapping nodes. Omitted / `false`: frame moves alone (default). */
  locked?: boolean
}

type CustomNode = Node<CustomNodeData>;
type EditorGroupFlowNode = Node<EditorGroupNodeData> & { type: 'editorGroup' };
type EditorCommentFlowNode = Node<EditorCommentNodeData> & { type: 'editorComment' };

const {
  onConnect, addEdges, addNodes, findNode, project,
  onPaneClick, onConnectStart, onConnectEnd, onMoveStart,
  getSelectedNodes, removeSelectedNodes, setNodes, setEdges,
  fitView, setViewport, onViewportChangeEnd, onViewportChange, onSelectionStart, onNodesChange, onEdgesChange,
  toObject, onNodeDragStart, onNodeDrag, onNodeDragStop, getNodes, getEdges,
  flowToScreenCoordinate, viewport, onPaneMouseMove, onPaneMouseLeave, onPaneMouseEnter,
  updateNodeInternals,
} = useVueFlow()

const FLOW_VIEWPORT_KEY = 'nodeEditor:flowViewport'
const FLOW_ZOOM_MIN = 0.01
const FLOW_ZOOM_MAX = 4

function clampStoredViewport(v: ViewportTransform): ViewportTransform {
  return {
    x: v.x,
    y: v.y,
    zoom: Math.max(FLOW_ZOOM_MIN, Math.min(FLOW_ZOOM_MAX, v.zoom)),
  }
}

function readStoredFlowViewport(): ViewportTransform | null {
  try {
    const raw = localStorage.getItem(FLOW_VIEWPORT_KEY)
    if (!raw) return null
    const o = JSON.parse(raw) as Record<string, unknown>
    const x = Number(o.x)
    const y = Number(o.y)
    const zoom = Number(o.zoom)
    if (!Number.isFinite(x) || !Number.isFinite(y) || !Number.isFinite(zoom)) return null
    return clampStoredViewport({ x, y, zoom })
  } catch {
    return null
  }
}

function persistFlowViewport(v: ViewportTransform) {
  try {
    const c = clampStoredViewport(v)
    localStorage.setItem(FLOW_VIEWPORT_KEY, JSON.stringify({ x: c.x, y: c.y, zoom: c.zoom }))
  } catch {
    /* ignore */
  }
}

onViewportChangeEnd((transform) => {
  persistFlowViewport(transform)
})

/** After nodes/edges change: restore last pan/zoom or fit if none saved. */
async function applyViewportAfterGraphLoad() {
  await new Promise<void>((r) => setTimeout(r, 100))
  const stored = readStoredFlowViewport()
  if (stored) {
    await setViewport(stored)
  } else {
    await fitView()
  }
}

const store = useEditorStore()
const { nodeMetrics, collabLive, collabRoster, collabPresence } = storeToRefs(store)
const nodeDefinitions = ref<Record<string, NodeDefinition>>({})
const workspace = ref<Workspace | null>(null)

const collabProfile = ref<CollabProfile>(loadCollabProfile())
let collabGraphRev = 0
const lastRemoteGraphRevByPeer = new Map<string, number>()
let applyingRemoteGraph = false
let collabGraphBroadcastNotBefore = 0
let collabCursorThrottleTimer: ReturnType<typeof setTimeout> | null = null
let collabFlowVpThrottleTimer: ReturnType<typeof setTimeout> | null = null
let lastFlowVpForCollab: ViewportTransform | null = null
let collabGraphDebounceTimer: ReturnType<typeof setTimeout> | null = null
let pendingCursorFlow: { x: number; y: number } | null = null

function scheduleCollabGraphBroadcastQuietPeriod() {
  collabGraphBroadcastNotBefore = Date.now() + 1400
}

function flushCollabCursor() {
  collabCursorThrottleTimer = null
  if (!pendingCursorFlow || !collabLive.value) return
  publishCollabPayload({
    type: 'cursor',
    x: pendingCursorFlow.x,
    y: pendingCursorFlow.y,
    active: true,
  })
}

const remoteCursorMarkers = computed(() => {
  void viewport.value.x
  void viewport.value.y
  void viewport.value.zoom
  const myId = collabProfile.value.sessionId
  const roster = collabRoster.value
  const pres = collabPresence.value
  const out: Array<{
    sessionId: string
    name: string
    color: string
    left: number
    top: number
  }> = []
  for (const p of roster) {
    if (p.sessionId === myId) continue
    const c = pres[p.sessionId]?.cursor
    if (!c?.active) continue
    const scr = flowToScreenCoordinate({ x: c.x, y: c.y })
    out.push({
      sessionId: p.sessionId,
      name: p.name || 'Peer',
      color: p.color || '#888',
      left: scr.x,
      top: scr.y,
    })
  }
  return out
})

function queueCollabGraphPublish() {
  if (!collabLive.value || applyingRemoteGraph) return
  if (Date.now() < collabGraphBroadcastNotBefore) return
  if (collabGraphDebounceTimer) clearTimeout(collabGraphDebounceTimer)
  collabGraphDebounceTimer = setTimeout(() => {
    collabGraphDebounceTimer = null
    if (!collabLive.value || applyingRemoteGraph) return
    if (Date.now() < collabGraphBroadcastNotBefore) return
    collabGraphRev += 1
    const graph = serializeGraph(getNodes.value, getEdges.value, metadataPassthrough.value)
    publishCollabPayload({ type: 'graph', rev: collabGraphRev, graph })
  }, 280)
}

const LAST_GRAPH_KEY = 'nodeEditor:lastGraph'
const LAST_METRICS_KEY = 'nodeEditor:lastMetrics'

const nodeTypes = {
  custom: markRaw(GenericNode),
  editorGroup: markRaw(GraphGroupNode),
  editorComment: markRaw(EditorCommentNode),
}

const flowContainerEl = ref<HTMLElement | null>(null)
const metadataPassthrough = ref<GraphMetadataPassthrough>({})

// Dialog state
const showSearch = ref(false)
const searchPosition = ref({ x: 0, y: 0 })
const searchFlowPosition = ref({ x: 0, y: 0 })
const searchFilterType = ref<string | null>(null)
const searchFilterSide = ref<'source' | 'target' | null>(null)
const activeConnectionSource = ref<OnConnectStartParams | null>(null)
let lastSearchOpenTime = 0

/** In-memory graph clipboard: nodes and edges whose endpoints are all within the copied set. */
interface GraphClipboard {
  nodes: Node[]
  edges: Edge[]
}
const graphClipboard = ref<GraphClipboard | null>(null)
const PASTE_OFFSET = { x: 40, y: 40 }

function targetIsEditable(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false
  if (target.isContentEditable) return true
  const tag = target.tagName
  return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT'
}

function newGroupId(): string {
  return `grp-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
}

function newCommentId(): string {
  return `cmt-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
}

function cloneNodeForClipboard(n: Node): Node {
  if (n.type === 'editorGroup') {
    const d = n.data as EditorGroupNodeData
    return {
      id: n.id,
      type: 'editorGroup',
      position: { ...n.position },
      zIndex: n.zIndex ?? -8,
      data: {
        name: typeof d?.name === 'string' ? d.name : '',
        locked: d?.locked === true,
      },
      style: n.style ? { ...n.style } : { width: 560, height: 240 },
    }
  }
  if (n.type === 'editorComment') {
    const d = n.data as EditorCommentNodeData
    return {
      id: n.id,
      type: 'editorComment',
      position: { ...n.position },
      zIndex: n.zIndex ?? -6,
      data: {
        blockName: typeof d?.blockName === 'string' && d.blockName.trim() ? d.blockName.trim() : 'Comment',
        text: typeof d?.text === 'string' ? d.text : '',
        fontSize:
          typeof d?.fontSize === 'number' && Number.isFinite(d.fontSize) ? d.fontSize : 21,
      },
      style: n.style ? { ...n.style } : { width: 400, height: 160 },
    }
  }
  const c = n as CustomNode
  const cd = c.data!
  return {
    id: c.id,
    type: 'custom',
    position: { ...c.position },
    data: {
      definition: cd.definition,
      values: { ...cd.values },
      ...(cd.displayTitle ? { displayTitle: cd.displayTitle } : {}),
      ...(cd.description ? { description: cd.description } : {}),
    },
  } as CustomNode
}

function newPastedNodeId(definitionId: string): string {
  return `${definitionId}-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
}

function copySelection() {
  const selected = getSelectedNodes.value
  if (selected.length === 0) return
  const idSet = new Set(selected.map((n) => n.id))
  const internalEdges = getEdges.value.filter(
    (e) => idSet.has(e.source) && idSet.has(e.target)
  )
  graphClipboard.value = {
    nodes: selected.map(cloneNodeForClipboard),
    edges: internalEdges.map((e) => ({ ...e })),
  }
}

function pasteFromClipboard() {
  const clip = graphClipboard.value
  if (!clip || clip.nodes.length === 0) return

  const idMap = new Map<string, string>()
  for (const n of clip.nodes) {
    if (n.type === 'editorGroup') {
      idMap.set(n.id, newGroupId())
    } else if (n.type === 'editorComment') {
      idMap.set(n.id, newCommentId())
    } else {
      idMap.set(n.id, newPastedNodeId((n as CustomNode).data!.definition.Id))
    }
  }

  const pastedNodes: Node[] = clip.nodes.map((src) => {
    const newId = idMap.get(src.id)!
    if (src.type === 'editorGroup') {
      const s = src as EditorGroupFlowNode
      return {
        id: newId,
        type: 'editorGroup',
        position: {
          x: src.position.x + PASTE_OFFSET.x,
          y: src.position.y + PASTE_OFFSET.y,
        },
        zIndex: s.zIndex ?? -8,
        selected: true,
        data: { ...s.data },
        style: s.style ? { ...s.style } : { width: 560, height: 240 },
      }
    }
    if (src.type === 'editorComment') {
      const s = src as EditorCommentFlowNode
      return {
        id: newId,
        type: 'editorComment',
        position: {
          x: src.position.x + PASTE_OFFSET.x,
          y: src.position.y + PASTE_OFFSET.y,
        },
        zIndex: s.zIndex ?? -6,
        selected: true,
        data: { ...s.data },
        style: s.style ? { ...s.style } : { width: 400, height: 160 },
      }
    }
    const c = src as CustomNode
    const cd = c.data!
    return {
      id: newId,
      type: 'custom',
      position: {
        x: src.position.x + PASTE_OFFSET.x,
        y: src.position.y + PASTE_OFFSET.y,
      },
      selected: true,
      data: {
        definition: cd.definition,
        values: { ...cd.values },
        ...(cd.displayTitle ? { displayTitle: cd.displayTitle } : {}),
        ...(cd.description ? { description: cd.description } : {}),
      },
    } as CustomNode
  })

  const existing = getNodes.value.map((n) => ({ ...n, selected: false }))
  setNodes([...existing, ...pastedNodes])

  const pastedEdges: Edge[] = clip.edges.map((e) => {
    const src = idMap.get(e.source)
    const tgt = idMap.get(e.target)
    if (!src || !tgt || !e.sourceHandle || !e.targetHandle) return null
    return {
      ...e,
      id: `e-${src}-${e.sourceHandle}-${tgt}-${e.targetHandle}`,
      source: src,
      target: tgt,
      sourceHandle: e.sourceHandle,
      targetHandle: e.targetHandle,
    } as Edge
  }).filter((e): e is Edge => e != null)

  if (pastedEdges.length > 0) {
    addEdges(pastedEdges)
  }

  saveHistory()
}

// Undo/Redo State
const history = ref<string[]>([])
const redoStack = ref<string[]>([])
let isUndoingRedoing = false

const API_BASE = `${nodeEditorBackendOrigin()}/nodeeditor`

/** Load default graph from disk via backend; returns whether a graph was applied. */
async function fetchAndApplyDefaultGraph(opts?: { showToast?: boolean }): Promise<boolean> {
  scheduleCollabGraphBroadcastQuietPeriod()
  const defs = nodeDefinitions.value
  if (!defs || Object.keys(defs).length === 0) return false
  try {
    const params = new URLSearchParams({ _: String(Date.now()) })
    if (store.selectedWorldName) params.set('worldName', store.selectedWorldName)
    const res = await fetch(`${API_BASE}/default-graph?${params}`)
    if (!res.ok) return false
    const initialGraph = await res.json()
    const { nodes, edges, metadataPassthrough: mp } = parseGraph(initialGraph, defs)
    metadataPassthrough.value = mp
    setNodes(nodes)
    setEdges(edges)
    await applyViewportAfterGraphLoad()
    saveHistory()
    if (opts?.showToast) {
      store.showToast('Graph reloaded from server')
    }
    return true
  } catch (e) {
    console.error('[NodeEditor] Failed to load default graph', e)
    return false
  }
}

async function doSave() {
  const json = serializeGraph(getNodes.value, getEdges.value, metadataPassthrough.value)
  try {
    localStorage.setItem(LAST_GRAPH_KEY, JSON.stringify(json))
  } catch (e) {
    console.warn('Failed to persist last graph to localStorage', e)
  }
  const text = JSON.stringify(json, null, '\t')
  try {
    const params = new URLSearchParams()
    if (store.selectedWorldName) params.set('worldName', store.selectedWorldName)
    const qs = params.toString()
    // POST with ?worldName= when available so the backend writes to the world's biome graph
    // (derived from WorldStructure.DefaultBiome); falls back to the default graph file otherwise.
    const res = await fetch(`${API_BASE}/save${qs ? `?${qs}` : ''}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: text,
    })
    if (res.ok) {
      store.triggerSaveSuccess()
    } else {
      const err = await res.json().catch(() => ({}))
      store.showToast('Save failed: ' + (err.error || res.statusText))
    }
  } catch (e: any) {
    store.showToast('Save failed: ' + (e?.message || 'network error'))
  }
}

// Watch for save trigger (Save button / Ctrl+S)
watch(() => store.triggerSave, () => {
  doSave()
})

// Reload graph when the user picks a different world in the top bar. The backend resolves the matching
// biome graph file via WorldStructure.DefaultBiome, so the editor must refetch to avoid Save clobbering
// the wrong biome file.
watch(
  () => store.selectedWorldName,
  async (newName, oldName) => {
    if (!newName || newName === oldName) return
    // Skip if `nodeDefinitions` are not yet populated; `fetchAndApplyDefaultGraph` already bails out
    // safely in that case but skipping here avoids an unnecessary fetch race with the initial load.
    const defs = nodeDefinitions.value
    if (!defs || Object.keys(defs).length === 0) return
    await fetchAndApplyDefaultGraph({ showToast: false })
  }
)

function saveHistory() {
  if (isUndoingRedoing) return
  const state = JSON.stringify(toObject())
  if (history.value.length > 0 && history.value[history.value.length - 1] === state) return

  history.value.push(state)
  if (history.value.length > 500) history.value.shift()
  redoStack.value = []
  queueCollabGraphPublish()
}

function applyRemoteCollaborationGraph(graphJson: unknown) {
  const defs = nodeDefinitions.value
  if (!defs || Object.keys(defs).length === 0) return
  try {
    applyingRemoteGraph = true
    const { nodes, edges, metadataPassthrough: mp } = parseGraph(
      graphJson as Parameters<typeof parseGraph>[0],
      defs,
    )
    metadataPassthrough.value = mp
    setNodes(nodes)
    setEdges(edges)
    saveHistory()
  } catch (e) {
    console.warn('[Collab] Failed to apply remote graph', e)
  } finally {
    applyingRemoteGraph = false
  }
}

function onCollabInbound(msg: CollabInboundMessage) {
  if (msg.type === 'hello') return
  if (msg.type === 'roster') {
    store.setCollabRoster(msg.participants)
    return
  }
  if (msg.type === 'cursor') {
    store.patchCollabPresence(msg.from, {
      cursor: { x: msg.x, y: msg.y, active: msg.active !== false },
    })
    return
  }
  if (msg.type === 'flowViewport') {
    store.patchCollabPresence(msg.from, {
      flowViewport: { x: msg.x, y: msg.y, zoom: msg.zoom },
    })
    return
  }
  if (msg.type === 'worldViewport') {
    store.patchCollabPresence(msg.from, {
      worldViewport: {
        worldName: msg.worldName,
        centerX: msg.centerX,
        centerZ: msg.centerZ,
        radius: msg.radius,
      },
    })
    return
  }
  if (msg.type === 'graph') {
    if (msg.from === collabProfile.value.sessionId) return
    const prev = lastRemoteGraphRevByPeer.get(msg.from) ?? 0
    if (msg.rev <= prev) return
    lastRemoteGraphRevByPeer.set(msg.from, msg.rev)
    applyRemoteCollaborationGraph(msg.graph)
  }
}

function startCollabSession() {
  stopNodeEditorCollab()
  scheduleCollabGraphBroadcastQuietPeriod()
  startNodeEditorCollab(
    collabProfile.value.sessionId,
    collabProfile.value,
    onCollabInbound,
    (open) => {
      store.setCollabLive(open)
    },
  )
}

function undo() {
  if (history.value.length <= 1) return
  isUndoingRedoing = true

  const current = history.value.pop()!
  redoStack.value.push(current)

  const previous = history.value[history.value.length - 1]!
  const state = JSON.parse(previous)

  setNodes(state.nodes)
  setEdges(state.edges)

  setTimeout(() => { isUndoingRedoing = false }, 50)
}

function redo() {
  if (redoStack.value.length === 0) return
  isUndoingRedoing = true

  const stateStr = redoStack.value.pop()!
  history.value.push(stateStr)

  const state = JSON.parse(stateStr)
  setNodes(state.nodes)
  setEdges(state.edges)

  setTimeout(() => { isUndoingRedoing = false }, 50)
}

const isValidConnection = (connection: Connection) => {
  const sourceNode = findNode(connection.source)
  const targetNode = findNode(connection.target)

  if (!sourceNode || !targetNode) return false
  if (sourceNode.type === 'editorGroup' || targetNode.type === 'editorGroup') return false
  if (sourceNode.type === 'editorComment' || targetNode.type === 'editorComment') return false

  const s = sourceNode as CustomNode
  const t = targetNode as CustomNode
  if (!s.data?.definition || !t.data?.definition) return false

  const sourceHandleDef = s.data.definition.Outputs.find((o) => o.Id === connection.sourceHandle)
  const targetHandleDef = t.data.definition.Inputs.find((i) => i.Id === connection.targetHandle)

  if (!sourceHandleDef || !targetHandleDef) return false

  return sourceHandleDef.Type === targetHandleDef.Type
}

let eventSource: EventSource | null = null
let reconnectTimeout: number | null = null
let debugSnapshotRequest: Promise<void> | null = null

function cleanupSSE() {
  if (reconnectTimeout) {
    clearTimeout(reconnectTimeout)
    reconnectTimeout = null
  }
  if (eventSource) {
    console.log('[SSE] Closing connection')
    eventSource.close()
    eventSource = null
  }
  store.setConnected(false)
  store.setConnecting(false)
}

function isTestMetricId(id: string): boolean {
  return /^test-node-\d+$/.test(String(id))
}

function normalizeMetricEntry(m: unknown): { id: string; min: number; max: number } | null {
  if (!m || typeof m !== 'object') return null
  const o = m as Record<string, unknown>
  const rawId = o.id ?? o.nodeId
  if (rawId == null || rawId === '') return null
  const id = String(rawId)
  if (isTestMetricId(id)) return null
  const min = Number(o.min)
  const max = Number(o.max)
  if (!Number.isFinite(min) || !Number.isFinite(max)) return null
  return { id, min, max }
}

/**
 * Align with server after connect: GET snapshot so node min/max badges show even
 * if the SSE initial-metrics message races with this fetch.
 *
 * This intentionally does NOT invalidate the density cache or bump densityUpdateSignal —
 * the SSE `onmessage` handler is the single authority for that so we avoid double-fetch
 * loops (onopen snapshot + onmessage initial metrics both firing loadField).
 */
async function applyDebugSnapshotFromServer() {
  if (debugSnapshotRequest) {
    await debugSnapshotRequest
    return
  }
  debugSnapshotRequest = (async () => {
    const snap = await fetchDensityDebugSnapshot()
    const list = snap?.nodeMetrics
    if (!list || !Array.isArray(list) || list.length === 0) {
      nodeMetrics.value = {}
      try {
        localStorage.removeItem(LAST_METRICS_KEY)
      } catch { /* ignore */ }
    } else {
      const next: Record<string, { min: number; max: number }> = {}
      for (const m of list) {
        const norm = normalizeMetricEntry(m)
        if (!norm) continue
        next[norm.id] = { min: norm.min, max: norm.max }
      }
      if (Object.keys(next).length === 0) {
        nodeMetrics.value = {}
        try {
          localStorage.removeItem(LAST_METRICS_KEY)
        } catch { /* ignore */ }
      } else {
        nodeMetrics.value = next
        try {
          localStorage.setItem(LAST_METRICS_KEY, JSON.stringify(next))
        } catch { /* ignore */ }
      }
    }
  })().finally(() => {
    debugSnapshotRequest = null
  })
  await debugSnapshotRequest
}

function connectToServer() {
  cleanupSSE()

  const url = `${nodeEditorBackendOrigin()}/density-updates`
  console.log('[SSE] Connecting to:', url)
  store.setConnecting(true)

  eventSource = new EventSource(url)

  eventSource.onopen = () => {
    console.log('[SSE] Connection established')
    store.setConnecting(false)
    store.setConnected(true)
    invalidateDensityCache()
    void applyDebugSnapshotFromServer()
  }

  eventSource.addEventListener('ping', (event) => {
    console.log('[SSE] Received warmup ping:', event.data)
  })

  eventSource.addEventListener('graph-reload', () => {
    console.log('[SSE] graph-reload — refetching default graph')
    void fetchAndApplyDefaultGraph({ showToast: true })
  })

  eventSource.onmessage = (event) => {
    try {
      let payload = event.data
      if (payload.startsWith('"')) {
        try {
          payload = JSON.parse(payload) as string
        } catch {
          /* use raw */
        }
      }
      if (payload.startsWith('[') || payload.startsWith('{')) {
        const data = JSON.parse(payload)
        const arr = Array.isArray(data) ? data : [data]
        const realEntries = arr.map(normalizeMetricEntry).filter((e): e is NonNullable<typeof e> => e != null)
        if (realEntries.length === 0) return

        const newMetrics = { ...nodeMetrics.value }
        realEntries.forEach((metric) => {
          newMetrics[metric.id] = { min: metric.min, max: metric.max }
        })
        nodeMetrics.value = newMetrics
        localStorage.setItem(LAST_METRICS_KEY, JSON.stringify(newMetrics))
        // Always invalidate and bump so the 3D view refetches; backend sends only after chunks are done
        invalidateDensityCache()
        store.bumpDensityUpdate()
      }
    } catch (e) {
      console.error('[SSE] Failed to parse density update', e)
    }
  }

  eventSource.onerror = (err) => {
    if (eventSource == null) return
    const readyState = eventSource.readyState
    console.error('[SSE] EventSource error', err)
    store.setConnected(false)
    // Native EventSource already retries while CONNECTING. Only create a fresh
    // instance when the browser gives up and the stream is actually CLOSED.
    if (readyState === EventSource.CONNECTING) {
      store.setConnecting(true)
      return
    }
    cleanupSSE()
    reconnectTimeout = window.setTimeout(() => {
      connectToServer()
    }, 3000)
  }
}

onMounted(async () => {
  const data = await loadNodes()
  nodeDefinitions.value = data.nodes
  workspace.value = data.workspace

  const graphLoaded = await fetchAndApplyDefaultGraph()
  if (!graphLoaded && nodeDefinitions.value['RootNode']) {
    addNewNode('RootNode', { x: 400, y: 100 })
    await applyViewportAfterGraphLoad()
    saveHistory()
  }

  window.addEventListener('keydown', handleKeyDown)
  window.addEventListener('beforeunload', cleanupSSE)
  window.addEventListener('beforeunload', cleanupCollabSession)

  // Initial connection
  connectToServer()
  startCollabSession()
})

function cleanupCollabSession() {
  if (collabCursorThrottleTimer) {
    clearTimeout(collabCursorThrottleTimer)
    collabCursorThrottleTimer = null
  }
  if (collabFlowVpThrottleTimer) {
    clearTimeout(collabFlowVpThrottleTimer)
    collabFlowVpThrottleTimer = null
  }
  if (collabGraphDebounceTimer) {
    clearTimeout(collabGraphDebounceTimer)
    collabGraphDebounceTimer = null
  }
  stopNodeEditorCollab()
  store.setCollabLive(false)
}

onUnmounted(() => {
  cleanupSSE()
  cleanupCollabSession()
  window.removeEventListener('keydown', handleKeyDown)
  window.removeEventListener('beforeunload', cleanupSSE)
  window.removeEventListener('beforeunload', cleanupCollabSession)
})

function handleKeyDown(e: KeyboardEvent) {
  if (targetIsEditable(e.target)) return

  const mod = e.ctrlKey || e.metaKey
  const key = e.key.toLowerCase()

  if (mod && key === 'z' && !e.shiftKey) {
    e.preventDefault()
    undo()
  } else if (mod && (key === 'y' || (key === 'z' && e.shiftKey))) {
    e.preventDefault()
    redo()
  } else if (mod && key === 'c') {
    e.preventDefault()
    copySelection()
  } else if (mod && key === 'v') {
    e.preventDefault()
    pasteFromClipboard()
  }
}

// Track changes for history
onNodesChange((changes: NodeChange[]) => {
  if (changes.some(c => c.type === 'remove' || c.type === 'add')) {
    saveHistory()
  }
})

/** While dragging a layout group, nodes whose bounds touch the group move with it (same delta). */
interface GroupDragCarryState {
  groupId: string
  groupStart: { x: number; y: number }
  attached: Record<string, { x: number; y: number }>
}

let groupDragCarry: GroupDragCarryState | null = null

function readNodeRectDims(n: Pick<Node, 'id' | 'type' | 'style' | 'dimensions'>): { w: number; h: number } {
  const dim = (n as { dimensions?: { width?: number; height?: number } }).dimensions
  if (
    dim &&
    typeof dim.width === 'number' &&
    dim.width > 8 &&
    typeof dim.height === 'number' &&
    dim.height > 8
  ) {
    return { w: dim.width, h: dim.height }
  }
  const st = n.style as Record<string, unknown> | undefined
  const parseDim = (v: unknown): number => {
    if (typeof v === 'number' && Number.isFinite(v) && v > 0) return v
    if (typeof v === 'string') {
      const p = parseFloat(v)
      if (Number.isFinite(p) && p > 0) return p
    }
    return 0
  }
  const sw = parseDim(st?.width)
  const sh = parseDim(st?.height)
  if (sw > 0 && sh > 0) return { w: sw, h: sh }
  if (n.type === 'editorGroup') return { w: 560, h: 240 }
  if (n.type === 'editorComment') return { w: 400, h: 160 }
  return { w: 280, h: 120 }
}

function rectsOverlap(
  a: { x: number; y: number; w: number; h: number },
  b: { x: number; y: number; w: number; h: number },
): boolean {
  /* Inclusive: counts edge-adjacent (“touching”) as well as overlapping. */
  return a.x <= b.x + b.w && a.x + a.w >= b.x && a.y <= b.y + b.h && a.y + a.h >= b.y
}

function computeGroupDragCarry(groupNode: Node): GroupDragCarryState {
  const { w: gw, h: gh } = readNodeRectDims(groupNode)
  const groupRect = {
    x: groupNode.position.x,
    y: groupNode.position.y,
    w: gw,
    h: gh,
  }

  const attached: Record<string, { x: number; y: number }> = {}
  for (const n of getNodes.value) {
    if (n.id === groupNode.id) continue
    if (n.type === 'editorGroup') continue
    const { w, h } = readNodeRectDims(n)
    const nr = { x: n.position.x, y: n.position.y, w, h }
    if (rectsOverlap(groupRect, nr)) {
      attached[n.id] = { x: n.position.x, y: n.position.y }
    }
  }

  return {
    groupId: groupNode.id,
    groupStart: { x: groupNode.position.x, y: groupNode.position.y },
    attached,
  }
}

onNodeDragStart(({ node, nodes }) => {
  if (node.type !== 'editorGroup') {
    groupDragCarry = null
    return
  }
  const d = (node as EditorGroupFlowNode).data as EditorGroupNodeData
  if (d?.locked !== true) {
    groupDragCarry = null
    return
  }
  const carry = computeGroupDragCarry(node as Node)
  const draggedIds = new Set(nodes.map((gn) => gn.id))
  for (const id of Object.keys(carry.attached)) {
    if (draggedIds.has(id)) delete carry.attached[id]
  }
  groupDragCarry = carry
})

onNodeDrag(({ node }) => {
  if (!groupDragCarry || node.id !== groupDragCarry.groupId) return
  const dx = node.position.x - groupDragCarry.groupStart.x
  const dy = node.position.y - groupDragCarry.groupStart.y
  const ids = Object.keys(groupDragCarry.attached)
  if (ids.length === 0 || (dx === 0 && dy === 0)) return
  setNodes(
    getNodes.value.map((n) => {
      const p0 = groupDragCarry!.attached[n.id]
      if (!p0) return n
      return { ...n, position: { x: p0.x + dx, y: p0.y + dy } }
    }),
  )
})

onNodeDragStop(() => {
  groupDragCarry = null
  saveHistory()
})

onEdgesChange((changes: EdgeChange[]) => {
  if (changes.some(c => c.type === 'remove' || c.type === 'add')) {
    saveHistory()
  }
})

onSelectionStart(() => {
  showSearch.value = false
})

function addNewNode(id: string, position = { x: 100, y: 100 }, connectTo?: OnConnectStartParams) {
  const def = nodeDefinitions.value[id]
  if (!def) return null

  const newNodeId = `${id}-${Date.now()}`
  const newNode: CustomNode = {
    id: newNodeId,
    type: 'custom',
    position,
    data: {
      definition: def,
      values: def.Content.reduce((acc, item) => {
        acc[item.Id] = item.Options?.Default !== undefined ? item.Options.Default : ''
        return acc
      }, {} as Record<string, string | number | boolean>)
    },
  }

  addNodes([newNode])

  if (connectTo) {
    if (connectTo.handleType === 'source') {
      const targetHandle = def.Inputs.find(i => i.Type === searchFilterType.value)
      if (targetHandle) {
        addEdges([{
          id: `e-${connectTo.nodeId}-${newNodeId}`,
          source: connectTo.nodeId as string,
          sourceHandle: connectTo.handleId as string,
          target: newNodeId,
          targetHandle: targetHandle.Id
        }])
      }
    } else {
      const sourceHandle = def.Outputs.find(o => o.Type === searchFilterType.value)
      if (sourceHandle) {
        addEdges([{
          id: `e-${newNodeId}-${connectTo.nodeId}`,
          source: newNodeId,
          sourceHandle: sourceHandle.Id,
          target: connectTo.nodeId as string,
          targetHandle: connectTo.handleId as string
        }])
      }
    }
  }

  saveHistory()
  return newNode
}

onPaneClick((event: MouseEvent) => {
  const selectedNodes = getSelectedNodes.value
  // If a new-node search was just opened from a drag (activeConnectionSource set),
  // keep it open even if there are selected nodes.
  if (selectedNodes.length > 0 && !(showSearch.value && activeConnectionSource.value)) {
    showSearch.value = false
    return
  }

  if (event.detail === 2) {
    // Double left click: open node search to create a new node
    showSearch.value = true
    searchPosition.value = { x: event.clientX, y: event.clientY }
    searchFlowPosition.value = project({ x: event.clientX, y: event.clientY })
    searchFilterType.value = null
    searchFilterSide.value = null
    activeConnectionSource.value = null
    lastSearchOpenTime = Date.now()
  } else if (event.detail === 1 && showSearch.value && !activeConnectionSource.value) {
    // Single click: close search when clicking on pane (but not when this click is the release after drag-from-handle)
    showSearch.value = false
  }
})

onMoveStart(() => {
  showSearch.value = false
})

onPaneMouseMove((e) => {
  if (!collabLive.value) return
  pendingCursorFlow = project({ x: e.clientX, y: e.clientY })
  if (collabCursorThrottleTimer == null) {
    collabCursorThrottleTimer = setTimeout(flushCollabCursor, 48)
  }
})

onPaneMouseLeave(() => {
  pendingCursorFlow = null
  if (collabLive.value) {
    publishCollabPayload({ type: 'cursor', x: 0, y: 0, active: false })
  }
})

onViewportChange((t) => {
  if (!collabLive.value) return
  lastFlowVpForCollab = t
  if (collabFlowVpThrottleTimer != null) return
  collabFlowVpThrottleTimer = setTimeout(() => {
    collabFlowVpThrottleTimer = null
    const v = lastFlowVpForCollab
    if (!collabLive.value || !v) return
    publishCollabPayload({ type: 'flowViewport', x: v.x, y: v.y, zoom: v.zoom })
  }, 140)
})

onConnectStart((params) => {
  activeConnectionSource.value = params

  const node = findNode(params.nodeId)
  if (node?.type === 'editorGroup') return
  if (node?.type === 'editorComment') return
  const cn = node as CustomNode | undefined
  if (cn?.data) {
    const handleDef = params.handleType === 'source'
      ? cn.data.definition.Outputs.find(o => o.Id === params.handleId)
      : cn.data.definition.Inputs.find(i => i.Id === params.handleId)

    searchFilterType.value = handleDef?.Type || null
    searchFilterSide.value = params.handleType as 'source' | 'target'
  }
})

onConnectEnd((event) => {
  if (activeConnectionSource.value && event instanceof MouseEvent) {
    const target = event.target as HTMLElement
    const isHandle = target.closest('.vue-flow__handle')

    if (!isHandle) {
      showSearch.value = true
      searchPosition.value = { x: event.clientX, y: event.clientY }
      searchFlowPosition.value = project({ x: event.clientX, y: event.clientY })
      lastSearchOpenTime = Date.now()
    }
  }
})

onConnect((params) => {
  addEdges([params])
  activeConnectionSource.value = null
  saveHistory()
})

function onDialogSelect(nodeDef: NodeDefinition) {
  addNewNode(nodeDef.Id, searchFlowPosition.value, activeConnectionSource.value || undefined)
  showSearch.value = false
  activeConnectionSource.value = null
}

function handleNodeValueUpdate(nodeId: string, event: { id: string, value: string | number | boolean }) {
  const node = findNode(nodeId) as CustomNode | undefined
  if (node?.data) {
    node.data.values[event.id] = event.value
    saveHistory()
  }
}

function handleNodeMetaUpdate(
  nodeId: string,
  patch: { displayTitle?: string | null; description?: string | null },
) {
  const node = findNode(nodeId) as CustomNode | undefined
  if (!node?.data) return
  if ('displayTitle' in patch) {
    const v = patch.displayTitle
    if (v == null || v === '') delete node.data.displayTitle
    else node.data.displayTitle = v
  }
  if ('description' in patch) {
    const v = patch.description
    if (v == null || v === '') delete node.data.description
    else node.data.description = v
  }
  saveHistory()
}

function handleGroupNameUpdate(nodeId: string, name: string) {
  setNodes(
    getNodes.value.map((n) =>
      n.id === nodeId && n.type === 'editorGroup'
        ? {
            ...n,
            data: { ...(n.data as EditorGroupNodeData), name },
          }
        : n,
    ),
  )
  saveHistory()
}

function handleGroupLockUpdate(nodeId: string, locked: boolean) {
  setNodes(
    getNodes.value.map((n) =>
      n.id === nodeId && n.type === 'editorGroup'
        ? {
            ...n,
            data: { ...(n.data as EditorGroupNodeData), locked },
          }
        : n,
    ),
  )
  saveHistory()
}

function handleGroupSizeUpdate(nodeId: string, size: { width: number; height: number }) {
  setNodes(
    getNodes.value.map((n) =>
      n.id === nodeId && n.type === 'editorGroup'
        ? { ...n, style: { ...n.style, width: size.width, height: size.height } }
        : n,
    ),
  )
  void nextTick(() => {
    updateNodeInternals([nodeId])
  })
  saveHistory()
}

function handleCommentBlockName(nodeId: string, blockName: string) {
  setNodes(
    getNodes.value.map((n) =>
      n.id === nodeId && n.type === 'editorComment'
        ? { ...n, data: { ...(n.data as EditorCommentNodeData), blockName } }
        : n,
    ),
  )
  saveHistory()
}

function handleCommentText(nodeId: string, text: string) {
  setNodes(
    getNodes.value.map((n) =>
      n.id === nodeId && n.type === 'editorComment'
        ? { ...n, data: { ...(n.data as EditorCommentNodeData), text } }
        : n,
    ),
  )
  saveHistory()
}

function handleCommentSizeUpdate(nodeId: string, size: { width: number; height: number }) {
  setNodes(
    getNodes.value.map((n) =>
      n.id === nodeId && n.type === 'editorComment'
        ? { ...n, style: { ...n.style, width: size.width, height: size.height } }
        : n,
    ),
  )
  void nextTick(() => {
    updateNodeInternals([nodeId])
  })
  saveHistory()
}

function addEditorGroup() {
  const el = flowContainerEl.value
  if (!el) return
  const r = el.getBoundingClientRect()
  const p = project({ x: r.left + r.width / 2, y: r.top + r.height / 2 })
  addNodes([
    {
      id: newGroupId(),
      type: 'editorGroup',
      position: { x: p.x - 280, y: p.y - 120 },
      zIndex: -8,
      data: { name: 'New group', locked: false },
      style: { width: 560, height: 240 },
    },
  ])
  saveHistory()
}

function addEditorComment() {
  const el = flowContainerEl.value
  if (!el) return
  const r = el.getBoundingClientRect()
  const p = project({ x: r.left + r.width / 2, y: r.top + r.height / 2 })
  addNodes([
    {
      id: newCommentId(),
      type: 'editorComment',
      position: { x: p.x - 200, y: p.y - 80 },
      zIndex: -6,
      data: { blockName: 'Comment', text: '', fontSize: 21 },
      style: { width: 400, height: 160 },
    },
  ])
  saveHistory()
}

watch(
  () => store.collabProfileRevision,
  () => {
    collabProfile.value = loadCollabProfile()
    void pushCollabProfile(collabProfile.value)
  },
)
</script>

<template>
  <div class="editor-wrapper">
    <div ref="flowContainerEl" class="flow-container">
        <div class="flow-toolbar" aria-label="Graph tools">
          <button
            type="button"
            class="flow-toolbar-btn"
            title="Add a layout group (saved as $NodeEditorMetadata.$Groups, same as the official editor)"
            @click="addEditorGroup"
          >
            Add group
          </button>
          <button
            type="button"
            class="flow-toolbar-btn"
            title="Add a comment block (saved as $NodeEditorMetadata.$Comments, same as the official editor)"
            @click="addEditorComment"
          >
            Add comment
          </button>
        </div>
        <VueFlow
          :node-types="nodeTypes"
          :is-valid-connection="isValidConnection"
          :snap-to-grid="true"
          :snap-grid="[20, 20]"
          :connection-radius="30"
          :auto-pan-on-connect="true"
          :delete-key-code="['Delete', 'Backspace']"
          :selection-key="'Shift'"
          :min-zoom="0.01"
          :max-zoom="4"
          :zoom-on-double-click="false"
        >
          <div
            v-if="remoteCursorMarkers.length > 0"
            class="collab-cursors-layer"
            aria-hidden="true"
          >
            <div
              v-for="m in remoteCursorMarkers"
              :key="m.sessionId"
              class="collab-cursor"
              :style="{ left: `${m.left}px`, top: `${m.top}px`, '--collab-color': m.color }"
            >
              <span class="collab-cursor-dot" />
              <span class="collab-cursor-label">{{ m.name }}</span>
            </div>
          </div>
          <template #node-custom="nodeProps">
            <GenericNode
              v-bind="nodeProps"
              @update:value="(e) => handleNodeValueUpdate(nodeProps.id, e)"
              @update:meta="(p) => handleNodeMetaUpdate(nodeProps.id, p)"
            />
          </template>
          <template #node-editorGroup="nodeProps">
            <GraphGroupNode
              v-bind="nodeProps"
              @update:name="(n) => handleGroupNameUpdate(nodeProps.id, n)"
              @update:locked="(l) => handleGroupLockUpdate(nodeProps.id, l)"
              @update:size="(s) => handleGroupSizeUpdate(nodeProps.id, s)"
            />
          </template>
          <template #node-editorComment="nodeProps">
            <EditorCommentNode
              v-bind="nodeProps"
              @update:block-name="(n) => handleCommentBlockName(nodeProps.id, n)"
              @update:text="(t) => handleCommentText(nodeProps.id, t)"
              @update:size="(s) => handleCommentSizeUpdate(nodeProps.id, s)"
            />
          </template>

          <Background pattern-color="#444" :gap="20" />
          <Controls :show-interactive="false" />
        </VueFlow>
      </div>

      <NodeSearchDialog
        v-if="showSearch"
        :nodes="nodeDefinitions"
        :workspace="workspace"
        :position="searchPosition"
        :filter-type="searchFilterType"
        :filter-side="searchFilterSide"
        @select="onDialogSelect"
        @close="showSearch = false"
      />
  </div>
</template>

<style scoped>
.editor-wrapper {
  display: flex;
  width: 100%;
  height: calc(100vh - 60px);
  background: #1a1a1a;
  color: white;
  position: relative;
}

.flow-container {
  flex-grow: 1;
  height: 100%;
  position: relative;
}

.flow-toolbar {
  position: absolute;
  top: 10px;
  left: 10px;
  z-index: 12;
  display: flex;
  gap: 8px;
  pointer-events: auto;
}

.flow-toolbar-btn {
  padding: 6px 12px;
  font-size: 12px;
  font-weight: 600;
  border-radius: 6px;
  border: 1px solid rgba(255, 255, 255, 0.18);
  background: rgba(28, 28, 32, 0.92);
  color: #e8e8e8;
  cursor: pointer;
}

.flow-toolbar-btn:hover {
  background: rgba(42, 42, 48, 0.95);
  border-color: rgba(29, 209, 161, 0.45);
}

.collab-cursors-layer {
  position: absolute;
  inset: 0;
  pointer-events: none;
  z-index: 20;
  overflow: hidden;
}

.collab-cursor {
  position: absolute;
  transform: translate(-2px, -2px);
  display: flex;
  align-items: center;
  gap: 6px;
}

.collab-cursor-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: var(--collab-color, #888);
  box-shadow: 0 0 0 2px rgba(0, 0, 0, 0.45);
  flex-shrink: 0;
}

.collab-cursor-label {
  font-size: 11px;
  font-weight: 600;
  line-height: 1;
  padding: 3px 7px;
  border-radius: 4px;
  background: rgba(15, 15, 15, 0.88);
  color: #f3f4f6;
  border: 1px solid var(--collab-color, #666);
  white-space: nowrap;
  max-width: 160px;
  overflow: hidden;
  text-overflow: ellipsis;
}

:deep(.vue-flow__node-custom) {
  border: none;
  background: none;
  padding: 0;
  width: 350px !important;
  min-width: 350px !important;
  max-width: 350px !important;
}

:deep(.vue-flow__selection) {
  background: rgba(66, 211, 146, 0.1);
  border: 1px solid #42d392;
  border-radius: 4px;
}

.editor-wrapper::after {
  content: 'Ctrl+Z: Undo | Ctrl+Shift+Z / Ctrl+Y: Redo | Ctrl+C / Ctrl+V: Copy/Paste | Shift: Selection | Double-click pane: New node';
  position: absolute;
  bottom: 20px;
  right: 20px;
  font-size: 0.7em;
  color: #555;
  pointer-events: none;
}

:deep(.vue-flow__controls) {
  background: #1e1e1e;
  border: 1px solid #444;
  border-radius: 6px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.4);
}

:deep(.vue-flow__controls-button) {
  background: #2a2a2a;
  border: none;
  border-bottom: 1px solid #333;
  color: #e5e5e5;
  fill: #aaa;
}

:deep(.vue-flow__controls-button:last-child) {
  border-bottom: none;
}

:deep(.vue-flow__controls-button:hover) {
  background: #333;
  fill: #e5e5e5;
}
:deep(.vue-flow__edge-path) {
  stroke-width: 4px !important;
  stroke: #888 !important;
  transition: stroke 0.2s, stroke-width 0.2s;
}

:deep(.vue-flow__edge.selected .vue-flow__edge-path) {
  stroke: #42d392 !important;
  stroke-width: 6px !important;
}

:deep(.vue-flow__edge:hover .vue-flow__edge-path) {
  stroke: #bbb !important;
  stroke-width: 6px !important;
}

:deep(.vue-flow__connection-path) {
  stroke-width: 4px !important;
  stroke: #42d392 !important;
}

:deep(.vue-flow__handle) {
  width: 12px;
  height: 12px;
  background: #555;
  border: 2px solid #999;
}

:deep(.vue-flow__handle:hover) {
  background: #42d392;
  border-color: #42d392;
}
</style>

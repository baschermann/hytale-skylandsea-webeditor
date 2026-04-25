<script setup lang="ts">
import { ref, watch, onMounted, onUnmounted, computed } from 'vue'
import { storeToRefs } from 'pinia'
import { useEditorStore } from '@/stores/editor'
import { nodeEditorBackendOrigin } from '@/services/node-editor-backend'
import { loadCollabProfile, persistCollabProfile, collabColorPalette } from '@/services/collab-profile'
import { isCollabConnected, publishCollabPayload } from '@/services/node-editor-collab'

const API_BASE = nodeEditorBackendOrigin()

const store = useEditorStore()
const { collabRoster, collabLive } = storeToRefs(store)

const collabPanelOpen = ref(false)
const collabPopoverRef = ref<HTMLElement | null>(null)
const collabInitial = loadCollabProfile()
const collabMyName = ref(collabInitial.name)
const collabMyColor = ref(collabInitial.color)

const collabPeerRows = computed(() => {
  const myId = loadCollabProfile().sessionId
  return collabRoster.value.filter((p) => p.sessionId !== myId)
})

/** Total connected editors including yourself (minimum 1 while live). */
const collabConnectedCount = computed(() => {
  if (!collabLive.value) return 0
  return Math.max(1, collabRoster.value.length)
})

function saveCollabProfileFromBar() {
  const next = persistCollabProfile(collabMyName.value, collabMyColor.value)
  collabMyName.value = next.name
  collabMyColor.value = next.color
  store.bumpCollabProfileRevision()
}

const saveSuccessAnim = ref(false)
let saveSuccessTimer: ReturnType<typeof setTimeout> | null = null

watch(() => store.saveSuccessTrigger, () => {
  if (saveSuccessTimer) clearTimeout(saveSuccessTimer)
  saveSuccessAnim.value = true
  saveSuccessTimer = setTimeout(() => {
    saveSuccessAnim.value = false
    saveSuccessTimer = null
  }, 1200)
})

interface WorldInfo { name: string; isDefault: boolean }
const worlds = ref<WorldInfo[]>([])
const worldsLoading = ref(false)

const selectedWorld = computed<string>({
  get: () => store.selectedWorldName,
  set: (v: string) => store.setSelectedWorldName(v ?? ''),
})

let collabWorldVpTimer: ReturnType<typeof setTimeout> | null = null
let suppressWorldViewportBroadcast = 0

function withSuppressedWorldViewportBroadcast<T>(fn: () => T): T {
  suppressWorldViewportBroadcast += 1
  try {
    return fn()
  } finally {
    suppressWorldViewportBroadcast = Math.max(0, suppressWorldViewportBroadcast - 1)
  }
}

watch(
  () => [
    store.viewportCenterChunkX,
    store.viewportCenterChunkZ,
    store.chunkRenderRadius,
    store.selectedWorldName,
  ],
  () => {
    if (suppressWorldViewportBroadcast > 0) return
    if (!isCollabConnected()) return
    if (collabWorldVpTimer) clearTimeout(collabWorldVpTimer)
    collabWorldVpTimer = setTimeout(() => {
      collabWorldVpTimer = null
      if (suppressWorldViewportBroadcast > 0) return
      publishCollabPayload({
        type: 'worldViewport',
        worldName: store.selectedWorldName,
        centerX: store.viewportCenterChunkX,
        centerZ: store.viewportCenterChunkZ,
        radius: store.chunkRenderRadius,
      })
    }, 450)
  },
)

function pickDefaultWorld(list: WorldInfo[]): string {
  const def = list.find(w => w.isDefault)
  return def?.name ?? list[0]?.name ?? ''
}

/** Refresh the world list and sync the store selection with the server's active viewport / the default world. */
async function syncWorldsOnConnect() {
  worldsLoading.value = true
  try {
    const res = await fetch(`${API_BASE}/worlds`)
    if (res.ok) {
      worlds.value = await res.json() as WorldInfo[]
    } else {
      worlds.value = []
    }
  } catch {
    worlds.value = []
  } finally {
    worldsLoading.value = false
  }

  let data: Record<string, unknown> | null = null
  try {
    const res = await fetch(`${API_BASE}/viewport`)
    if (res.ok) data = await res.json()
  } catch {
    /* ignore */
  }

  withSuppressedWorldViewportBroadcast(() => {
    if (data?.active === true) {
      const cx = Number(data.centerX)
      const cz = Number(data.centerZ)
      const r = Number(data.radius)
      const wn = typeof data.worldName === 'string' ? data.worldName : ''
      if (Number.isFinite(cx)) store.setViewportCenterChunkX(cx)
      if (Number.isFinite(cz)) store.setViewportCenterChunkZ(cz)
      if (Number.isFinite(r)) store.setChunkRenderRadius(r)
      if (wn && worlds.value.some(w => w.name === wn)) {
        store.setSelectedWorldName(wn)
      } else if (!store.selectedWorldName || !worlds.value.some(w => w.name === store.selectedWorldName)) {
        store.setSelectedWorldName(pickDefaultWorld(worlds.value))
      }
      return
    }
    if (!store.selectedWorldName || !worlds.value.some(w => w.name === store.selectedWorldName)) {
      store.setSelectedWorldName(pickDefaultWorld(worlds.value))
    }
  })
}

watch(
  () => store.isConnected,
  async (connected) => {
    if (!connected) return
    await syncWorldsOnConnect()
  },
  { immediate: true }
)

function onClickOutside(e: MouseEvent) {
  const t = e.target as Node
  if (collabPopoverRef.value && !collabPopoverRef.value.contains(t)) {
    collabPanelOpen.value = false
  }
}

onMounted(() => {
  document.addEventListener('mousedown', onClickOutside)
})
onUnmounted(() => document.removeEventListener('mousedown', onClickOutside))
</script>


<template>
  <div class="top-bar">
    <div class="content">
      <div class="left-section">
        <a href="/" class="logo-link">
          <span class="logo-title">Hytale WorldGenV2</span>
          <span class="logo-badge">Vibecoded</span>
        </a>

        <div class="world-selector">
          <label class="world-selector-label" for="top-bar-world-select">World</label>
          <select
            id="top-bar-world-select"
            class="world-select"
            v-model="selectedWorld"
            :disabled="!store.isConnected || worldsLoading || worlds.length === 0"
          >
            <option v-if="worlds.length === 0" value="" disabled>
              {{ worldsLoading ? 'Loading…' : 'No worlds' }}
            </option>
            <option v-for="w in worlds" :key="w.name" :value="w.name">
              {{ w.name }}{{ w.isDefault ? ' (default)' : '' }}
            </option>
          </select>
        </div>
      </div>

      <div class="center-section">
        <button
          class="action-btn primary save-btn"
          :class="{ 'save-success': saveSuccessAnim }"
          @click="store.save"
        >
          Save
        </button>
      </div>

      <div class="right-section">
        <div class="collab-wrapper" ref="collabPopoverRef">
          <button
            type="button"
            class="collab-chip"
            :class="{ live: collabLive }"
            @click="collabPanelOpen = !collabPanelOpen"
          >
            <span class="collab-chip-swatch" :style="{ background: collabMyColor }" />
            <span class="collab-chip-name">{{ collabMyName }}</span>
            <span v-if="collabLive" class="collab-chip-badge">{{ collabConnectedCount }}</span>
          </button>
          <div v-if="collabPanelOpen" class="collab-popover">
            <div class="collab-popover-title">Live collaboration</div>
            <div class="popover-row collab-name-row">
              <label for="collab-profile-name">Name</label>
              <input
                id="collab-profile-name"
                v-model="collabMyName"
                class="popover-input collab-name-input"
                maxlength="64"
                type="text"
              />
            </div>
            <div class="popover-row collab-color-row-wrap">
              <label>Color</label>
              <div class="collab-swatches">
                <button
                  v-for="c in collabColorPalette"
                  :key="c"
                  type="button"
                  class="collab-swatch"
                  :class="{ selected: collabMyColor.toLowerCase() === c.toLowerCase() }"
                  :style="{ background: c }"
                  :title="c"
                  @click="collabMyColor = c"
                />
              </div>
            </div>
            <button type="button" class="action-btn primary collab-save" @click="saveCollabProfileFromBar">
              Save profile
            </button>
            <div v-if="collabPeerRows.length > 0" class="collab-peers">
              <div class="collab-peers-title">Connected</div>
              <ul>
                <li v-for="row in collabPeerRows" :key="row.sessionId">
                  <span class="peer-dot" :style="{ background: row.color }" />
                  <span class="peer-name">{{ row.name }}</span>
                </li>
              </ul>
            </div>
            <div v-else-if="collabLive" class="collab-peers-empty">No other editors connected.</div>
            <div v-else class="collab-peers-empty">Open the node editor to connect.</div>
          </div>
        </div>
        <div class="server-status" :class="{ connected: store.isConnected, connecting: store.isConnecting }">
          <span class="status-dot"></span>
          <span class="status-text">{{ store.isConnected ? 'Connected' : store.isConnecting ? 'Connecting...' : 'Disconnected' }}</span>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
  .top-bar{
    background-color: transparent;
    display: flex;
    margin: 0;
    width: 100%;
    border-width: 0 0 thin 0;
    border-style: solid;
    border-color: rgba(84, 84, 84, .48);
    padding: 0.5rem;
  }

  .content {
    width: 100%;
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 0;
    padding: 0 1rem;
    position: relative;
  }

  .left-section {
    display: flex;
    align-items: center;
    gap: 1.5rem;
    flex-shrink: 0;
    z-index: 1;
  }

  .center-section {
    position: fixed;
    left: 0;
    right: 0;
    top: 0;
    height: 60px;
    display: flex;
    align-items: center;
    justify-content: center;
    pointer-events: none;
    z-index: 10;
  }

  .center-section .save-btn {
    pointer-events: auto;
  }

  .save-btn {
    width: 450px;
    max-width: min(450px, 80vw);
    transition: box-shadow 0.25s ease, transform 0.25s ease;
  }

  .save-btn.save-success {
    animation: save-success-pulse 1.2s ease-out;
  }

  @keyframes save-success-pulse {
    0% { box-shadow: 0 0 0 0 rgba(66, 211, 146, 0.6); }
    40% { box-shadow: 0 0 0 12px rgba(66, 211, 146, 0); }
    100% { box-shadow: 0 0 0 0 rgba(66, 211, 146, 0); }
  }

  .right-section {
    display: flex;
    align-items: center;
    gap: 1rem;
    flex-shrink: 0;
    z-index: 1;
  }

  .collab-wrapper {
    position: relative;
  }

  .collab-chip {
    display: inline-flex;
    align-items: center;
    gap: 0.45rem;
    padding: 0.25rem 0.55rem 0.25rem 0.35rem;
    border-radius: 999px;
    border: 1px solid #444;
    background: #222;
    color: #e5e5e5;
    font-size: 0.78rem;
    cursor: pointer;
    transition: border-color 0.2s, background 0.2s;
  }

  .collab-chip:hover {
    border-color: #555;
    background: #2a2a2a;
  }

  .collab-chip.live {
    border-color: #647eff;
    box-shadow: 0 0 0 1px rgba(100, 126, 255, 0.25);
  }

  .collab-chip-swatch {
    width: 18px;
    height: 18px;
    border-radius: 50%;
    border: 2px solid rgba(255, 255, 255, 0.2);
    flex-shrink: 0;
  }

  .collab-chip-name {
    max-width: 120px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .collab-chip-badge {
    min-width: 1.1rem;
    height: 1.1rem;
    padding: 0 5px;
    border-radius: 999px;
    background: #647eff;
    color: #0f0f12;
    font-size: 0.65rem;
    font-weight: 700;
    display: inline-flex;
    align-items: center;
    justify-content: center;
  }

  .collab-popover {
    position: absolute;
    top: calc(100% + 8px);
    right: 0;
    width: min(380px, 96vw);
    background: #1e1e1e;
    border: 1px solid #444;
    border-radius: 8px;
    padding: 0.85rem 0.95rem;
    z-index: 120;
    box-shadow: 0 8px 24px rgba(0, 0, 0, 0.5);
    display: flex;
    flex-direction: column;
    gap: 0.5rem;
  }

  .collab-popover-title {
    font-weight: 600;
    font-size: 0.95rem;
    color: #f3f4f6;
  }

  .popover-row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 0.75rem;
  }

  .popover-row label {
    font-size: 0.75rem;
    color: #aaa;
    white-space: nowrap;
  }

  .popover-input {
    width: 72px;
    padding: 0.25rem 0.4rem;
    background: #2a2a2a;
    border: 1px solid #444;
    border-radius: 4px;
    color: #e5e5e5;
    font-size: 0.8rem;
    text-align: right;
  }

  .collab-popover .collab-name-row {
    align-items: center;
  }

  .collab-popover .collab-name-input {
    flex: 1;
    min-width: 0;
    width: auto;
    text-align: left;
  }

  .collab-popover .collab-color-row-wrap {
    flex-direction: row;
    align-items: center;
    gap: 0.75rem;
  }

  .collab-popover .collab-color-row-wrap .collab-swatches {
    flex: 1;
    min-width: 0;
    justify-content: flex-start;
  }

  .collab-swatches {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    align-items: center;
  }

  .collab-swatch {
    width: 22px;
    height: 22px;
    border-radius: 4px;
    border: 2px solid transparent;
    box-sizing: border-box;
    cursor: pointer;
    padding: 0;
    flex-shrink: 0;
  }

  .collab-swatch.selected {
    border-color: #f3f4f6;
    box-shadow: 0 0 0 1px rgba(0, 0, 0, 0.6);
  }

  .collab-swatch:hover {
    transform: scale(1.06);
  }

  .collab-save {
    width: 100%;
    margin-top: 0.15rem;
  }

  .collab-peers {
    margin-top: 0.35rem;
    padding-top: 0.5rem;
    border-top: 1px solid #333;
  }

  .collab-peers-title {
    font-size: 0.75rem;
    font-weight: 600;
    color: #d1d5db;
    margin-bottom: 0.35rem;
  }

  .collab-peers ul {
    list-style: none;
    margin: 0;
    padding: 0;
    display: flex;
    flex-direction: column;
    gap: 0.35rem;
    max-height: 160px;
    overflow-y: auto;
  }

  .collab-peers li {
    display: flex;
    align-items: center;
    gap: 0.35rem;
    font-size: 0.72rem;
    color: #cbd5e1;
  }

  .peer-dot {
    width: 8px;
    height: 8px;
    border-radius: 50%;
    flex-shrink: 0;
  }

  .peer-name {
    font-weight: 600;
    color: #f3f4f6;
    flex: 1;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .collab-peers-empty {
    font-size: 0.72rem;
    color: #6b7280;
    margin-top: 0.25rem;
  }

  .logo-link {
    display: inline-flex;
    align-items: baseline;
    gap: 0.5rem;
    text-decoration: none;
    flex-grow: 0;
    flex-shrink: 0;
  }

  .logo-title {
    font-size: 1.35rem;
    font-weight: 700;
    letter-spacing: -0.02em;
    background: linear-gradient(135deg, #42d392 0%, #33b87c 40%, #647eff 100%);
    -webkit-background-clip: text;
    -webkit-text-fill-color: transparent;
    background-clip: text;
  }

  .logo-link:hover .logo-title {
    background: linear-gradient(135deg, #52e3a2 0%, #42d392 50%, #7c8fff 100%);
    -webkit-background-clip: text;
    background-clip: text;
  }

  .logo-badge {
    font-size: 0.65rem;
    font-weight: 600;
    text-transform: uppercase;
    letter-spacing: 0.12em;
    color: #1a1a1a;
    background: linear-gradient(135deg, #a78bfa 0%, #647eff 100%);
    padding: 0.2em 0.5em;
    border-radius: 4px;
    align-self: center;
    box-shadow: 0 1px 3px rgba(0, 0, 0, 0.3);
  }

  .world-selector {
    display: inline-flex;
    align-items: center;
    gap: 0.45rem;
  }

  .world-selector-label {
    font-size: 0.7rem;
    font-weight: 600;
    text-transform: uppercase;
    letter-spacing: 0.08em;
    color: #888;
  }

  .world-select {
    min-width: 150px;
    padding: 0.3rem 0.55rem;
    background: #222;
    border: 1px solid #444;
    border-radius: 4px;
    color: #e5e5e5;
    font-size: 0.82rem;
    cursor: pointer;
    transition: border-color 0.2s, background 0.2s;
  }

  .world-select:hover:not(:disabled) {
    background: #2a2a2a;
    border-color: #555;
  }

  .world-select:focus {
    outline: none;
    border-color: #42d392;
  }

  .world-select:disabled {
    opacity: 0.6;
    cursor: not-allowed;
  }


  .action-btn {
    background: #333;
    color: white;
    border: 1px solid #444;
    padding: 0.4rem 1rem;
    border-radius: 4px;
    cursor: pointer;
    font-weight: 500;
    transition: all 0.2s;
  }

  .action-btn:hover {
    background: #444;
    border-color: #555;
  }

  .action-btn.primary {
    background: #42d392;
    color: #1a1a1a;
    border-color: #42d392;
  }

  .action-btn.primary:hover {
    background: #33b87c;
    border-color: #33b87c;
  }

  .server-status {
    display: flex;
    align-items: center;
    gap: 0.5rem;
    font-size: 0.8rem;
    color: #888;
    background: #222;
    padding: 0.2rem 0.6rem;
    border-radius: 12px;
    border: 1px solid #333;
  }

  .server-status.connected {
    color: #42d392;
  }

  .server-status.connecting {
    color: #647eff;
  }

  .status-dot {
    width: 8px;
    height: 8px;
    background: #666;
    border-radius: 50%;
  }

  .connected .status-dot {
    background: #42d392;
    box-shadow: 0 0 8px #42d392;
  }

  .connecting .status-dot {
    background: #647eff;
    animation: blink 1s infinite;
  }

  @keyframes blink {
    0% { opacity: 0.4; }
    50% { opacity: 1; }
    100% { opacity: 0.4; }
  }
</style>

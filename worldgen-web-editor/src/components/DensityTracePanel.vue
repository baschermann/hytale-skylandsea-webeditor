<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { useEditorStore } from '@/stores/editor';
import { storeToRefs } from 'pinia';
import type { DensityTraceStep } from '@/services/density-nodes';

const route = useRoute();
const store = useEditorStore();
const {
  densityTraceNodeId,
  densityTracePickedWorld,
  densityTraceLoading,
  densityTraceError,
  densityTraceData,
  densityTraceHighlightNodeId,
  densityUpdateSignal,
} = storeToRefs(store);

/** v3: single `{ top, right }` — no separate split/full (closing 3D view no longer jumps the panel). */
const TRACE_PANEL_POS_KEY = 'density-trace-panel-position-v3';
const TRACE_PANEL_POS_KEY_V2 = 'density-trace-panel-position-v2';
const TRACE_PANEL_POS_KEY_LEGACY = 'density-trace-panel-position';

interface TracePanelPos {
  top: number;
  right: number;
}

function validateStoredPos(pos: TracePanelPos | undefined | null): pos is TracePanelPos {
  if (!pos) return false;
  if (!Number.isFinite(pos.top) || !Number.isFinite(pos.right)) return false;
  const iw = window.innerWidth;
  const ih = window.innerHeight;
  if (iw < 200 || ih < 200) return false;
  const margin = 8;
  const topBar = 60;
  const maxPanelW = Math.min(440, Math.max(200, iw - margin * 2));
  const approxH = 48;
  if (pos.top < topBar + margin || pos.top > ih - approxH) return false;
  if (pos.right < margin || pos.right > iw - maxPanelW - margin) return false;
  return true;
}

/** When the 3D viewer overlay is open, node editor is the left 50%; dock trace to the right of that area. */
const isDensityViewerSplit = computed(() => route.name === 'density-viewer');

const panelRef = ref<HTMLElement | null>(null);
/** Single dragged position for all layouts (graph-only and 3D split). */
const panelPosition = ref<TracePanelPos | null>(null);

function isFlatTracePos(data: unknown): data is TracePanelPos {
  if (data == null || typeof data !== 'object') return false;
  const o = data as Record<string, unknown>;
  return (
    typeof o.top === 'number' &&
    typeof o.right === 'number' &&
    Number.isFinite(o.top) &&
    Number.isFinite(o.right)
  );
}

function loadStoredPositions() {
  try {
    const tryV3 = localStorage.getItem(TRACE_PANEL_POS_KEY);
    if (tryV3) {
      const parsed: unknown = JSON.parse(tryV3);
      if (isFlatTracePos(parsed) && validateStoredPos(parsed)) {
        panelPosition.value = parsed;
        return;
      }
      localStorage.removeItem(TRACE_PANEL_POS_KEY);
    }

    const tryV2 = localStorage.getItem(TRACE_PANEL_POS_KEY_V2);
    if (tryV2) {
      const data = JSON.parse(tryV2) as { full?: TracePanelPos; split?: TracePanelPos };
      const merged =
        data.split && validateStoredPos(data.split)
          ? data.split
          : data.full && validateStoredPos(data.full)
            ? data.full
            : null;
      if (merged) {
        panelPosition.value = merged;
        saveStoredPositions();
      }
      localStorage.removeItem(TRACE_PANEL_POS_KEY_V2);
      return;
    }

    const tryLegacy = localStorage.getItem(TRACE_PANEL_POS_KEY_LEGACY);
    if (tryLegacy) {
      const parsed: unknown = JSON.parse(tryLegacy);
      if (isFlatTracePos(parsed) && validateStoredPos(parsed)) {
        panelPosition.value = parsed;
        saveStoredPositions();
      } else {
        const data = parsed as { full?: TracePanelPos; split?: TracePanelPos };
        const merged =
          data.split && validateStoredPos(data.split)
            ? data.split
            : data.full && validateStoredPos(data.full)
              ? data.full
              : null;
        if (merged) {
          panelPosition.value = merged;
          saveStoredPositions();
        }
      }
      localStorage.removeItem(TRACE_PANEL_POS_KEY_LEGACY);
    }
  } catch {
    panelPosition.value = null;
    try {
      localStorage.removeItem(TRACE_PANEL_POS_KEY);
    } catch {
      /* ignore */
    }
  }
}

function saveStoredPositions() {
  try {
    if (panelPosition.value == null) {
      localStorage.removeItem(TRACE_PANEL_POS_KEY);
    } else {
      localStorage.setItem(TRACE_PANEL_POS_KEY, JSON.stringify(panelPosition.value));
    }
  } catch {
    /* ignore */
  }
}

function clampPos(pos: TracePanelPos, width: number, height: number): TracePanelPos {
  const margin = 8;
  const topBar = 60;
  const ih = window.innerHeight;
  const iw = window.innerWidth;
  const minTop = topBar + margin;
  const maxTop = Math.max(minTop, ih - height - margin);
  const minRight = margin;
  const maxRight = Math.max(minRight, iw - width - margin);
  return {
    top: Math.min(Math.max(minTop, pos.top), maxTop),
    right: Math.min(Math.max(minRight, pos.right), maxRight),
  };
}

function clampStoredToViewport() {
  const el = panelRef.value;
  const w = el?.offsetWidth ?? 400;
  const h = el?.offsetHeight ?? 300;
  if (panelPosition.value) {
    panelPosition.value = clampPos(panelPosition.value, w, h);
  }
}

/** Matches `.density-trace-panel` default `top: calc(60px + 8px)` for max-height when not dragged. */
const TRACE_PANEL_DEFAULT_TOP_PX = 60 + 8;
const TRACE_PANEL_BOTTOM_MARGIN_PX = 8;

const panelStyle = computed(() => {
  const pos = panelPosition.value;
  const topPx = pos?.top ?? TRACE_PANEL_DEFAULT_TOP_PX;
  const maxHeight = `calc(100vh - ${topPx}px - ${TRACE_PANEL_BOTTOM_MARGIN_PX}px)`;
  if (!pos) {
    return { maxHeight };
  }
  return {
    top: `${pos.top}px`,
    right: `${pos.right}px`,
    left: 'auto',
    maxHeight,
  };
});

function onHeaderPointerDown(e: PointerEvent) {
  const t = e.target as HTMLElement | null;
  if (t?.closest('.trace-close-btn')) return;
  e.preventDefault();
  const el = panelRef.value;
  if (!el) return;

  const rect = el.getBoundingClientRect();
  const startX = e.clientX;
  const startY = e.clientY;
  const startTop = rect.top;
  const startRight = window.innerWidth - rect.right;

  try {
    el.setPointerCapture(e.pointerId);
  } catch {
    /* ignore */
  }

  const onMove = (ev: PointerEvent) => {
    const dx = ev.clientX - startX;
    const dy = ev.clientY - startY;
    let newTop = startTop + dy;
    let newRight = startRight - dx;
    const w = el.getBoundingClientRect().width;
    const h = el.getBoundingClientRect().height;
    const c = clampPos({ top: newTop, right: newRight }, w, h);
    panelPosition.value = c;
  };

  const onUp = (ev: PointerEvent) => {
    try {
      el.releasePointerCapture(ev.pointerId);
    } catch {
      /* ignore */
    }
    window.removeEventListener('pointermove', onMove);
    window.removeEventListener('pointerup', onUp);
    window.removeEventListener('pointercancel', onUp);
    saveStoredPositions();
  };

  window.addEventListener('pointermove', onMove);
  window.addEventListener('pointerup', onUp);
  window.addEventListener('pointercancel', onUp);
}

const editX = ref(0);
const editY = ref(0);
const editZ = ref(0);

/** Skip auto-apply while syncing inputs from the store (pick / trace response). */
let syncingCoordsFromStore = false;

const DEBOUNCE_MS = 280;
let applyTimer: ReturnType<typeof setTimeout> | null = null;

watch(
  densityTracePickedWorld,
  (pw) => {
    if (!pw) return;
    syncingCoordsFromStore = true;
    editX.value = pw.x;
    editY.value = pw.y;
    editZ.value = pw.z;
    void nextTick(() => {
      syncingCoordsFromStore = false;
    });
  },
  { immediate: true }
);

function applyCoordinates() {
  const id = densityTraceNodeId.value;
  if (!id) return;
  const x = Math.round(Number(editX.value));
  const y = Math.round(Number(editY.value));
  const z = Math.round(Number(editZ.value));
  if (![x, y, z].every((n) => Number.isFinite(n))) return;
  const pw = densityTracePickedWorld.value;
  if (pw && pw.x === x && pw.y === y && pw.z === z) return;
  void store.runDensityTraceAt(id, x, y, z);
}

function scheduleApply() {
  if (syncingCoordsFromStore) return;
  if (applyTimer != null) clearTimeout(applyTimer);
  applyTimer = setTimeout(() => {
    applyTimer = null;
    applyCoordinates();
  }, DEBOUNCE_MS);
}

watch([editX, editY, editZ], () => {
  scheduleApply();
});

function formatTraceNum(v: number): string {
  if (!Number.isFinite(v)) return String(v);
  const a = Math.abs(v);
  if (a !== 0 && (a < 1e-4 || a >= 1e6)) return v.toExponential(4);
  return v.toFixed(4).replace(/\.?0+$/, '');
}

function formatAnchorWorld(a: { x: number; y: number; z: number }): string {
  return `${formatTraceNum(a.x)}, ${formatTraceNum(a.y)}, ${formatTraceNum(a.z)}`;
}

/** Backend steps may omit the graph output node; always show it last with {@link DensityTraceResponse.finalValue}. */
const traceTableSteps = computed((): DensityTraceStep[] => {
  const data = densityTraceData.value;
  const nid = densityTraceNodeId.value;
  if (!data) return [];
  const steps = data.steps.slice();
  if (!nid) return steps;
  let outIdx = -1;
  for (let i = steps.length - 1; i >= 0; i--) {
    if (String(steps[i]!.nodeId) === String(nid)) {
      outIdx = i;
      break;
    }
  }
  if (outIdx < 0) {
    steps.push({
      index: steps.length,
      nodeId: nid,
      nodeType: data.outputNodeType ?? '',
      value: data.finalValue,
    });
  } else if (outIdx !== steps.length - 1) {
    const row = steps[outIdx]!;
    steps.splice(outIdx, 1);
    steps.push(row);
  }
  return steps.map((s, i) => ({ ...s, index: i }));
});

function onTraceRowEnter(nodeId: string) {
  store.setDensityTraceHighlightNodeId(nodeId, 'list');
}

function onTraceRowLeave() {
  store.setDensityTraceHighlightNodeId(null);
}

const showPanel = computed(
  () =>
    densityTracePickedWorld.value != null ||
    densityTraceLoading.value ||
    densityTraceError.value != null ||
    densityTraceData.value != null
);

function onWindowResize() {
  clampStoredToViewport();
  saveStoredPositions();
}

onMounted(() => {
  loadStoredPositions();
  window.addEventListener('resize', onWindowResize);
});

onBeforeUnmount(() => {
  window.removeEventListener('resize', onWindowResize);
});

watch(showPanel, (show) => {
  if (show) {
    void nextTick(() => {
      clampStoredToViewport();
    });
  }
});

/** After chunk generation (SSE → bumpDensityUpdate), keep trace in sync with the 3D mesh refresh. */
watch(densityUpdateSignal, () => {
  const id = densityTraceNodeId.value;
  const pw = densityTracePickedWorld.value;
  if (!id || !pw) return;
  void store.runDensityTraceAt(id, pw.x, pw.y, pw.z, { silent: true });
});

</script>

<template>
  <div
    v-if="showPanel"
    ref="panelRef"
    class="density-trace-panel"
    :class="{ 'density-trace-panel--split': isDensityViewerSplit }"
    :style="panelStyle"
    role="region"
    aria-label="Density evaluation trace"
  >
    <div
      class="density-trace-header density-trace-drag-handle"
      @pointerdown="onHeaderPointerDown"
    >
      <span class="density-trace-title">Density trace</span>
      <button type="button" class="trace-close-btn" @click="store.clearDensityTrace()">Close</button>
    </div>
    <p v-if="densityTraceNodeId" class="trace-node-id" :title="densityTraceNodeId ?? ''">
      {{ densityTraceNodeId }}
    </p>
    <div v-if="densityTracePickedWorld != null || densityTraceNodeId" class="trace-coords-row">
      <div class="trace-coord-group">
        <span class="trace-axis-label">X</span>
        <div class="render-distance-control trace-coord-pill">
          <input
            v-model.number="editX"
            type="number"
            step="1"
            class="render-distance-input"
            @focus="(e) => (e.target as HTMLInputElement).select()"
          />
        </div>
      </div>
      <div class="trace-coord-group">
        <span class="trace-axis-label">Y</span>
        <div class="render-distance-control trace-coord-pill">
          <input
            v-model.number="editY"
            type="number"
            step="1"
            class="render-distance-input"
            @focus="(e) => (e.target as HTMLInputElement).select()"
          />
        </div>
      </div>
      <div class="trace-coord-group">
        <span class="trace-axis-label">Z</span>
        <div class="render-distance-control trace-coord-pill">
          <input
            v-model.number="editZ"
            type="number"
            step="1"
            class="render-distance-input"
            @focus="(e) => (e.target as HTMLInputElement).select()"
          />
        </div>
      </div>
    </div>
    <p v-if="densityTraceLoading" class="trace-status">Loading trace…</p>
    <p v-if="densityTraceError" class="trace-error">{{ densityTraceError }}</p>
    <template v-if="densityTraceData">
      <div class="trace-table-wrap">
        <table class="trace-table">
          <thead>
            <tr>
              <th>#</th>
              <th>Type</th>
              <th>value</th>
            </tr>
          </thead>
          <tbody>
            <template v-for="s in traceTableSteps" :key="s.index + '-' + s.nodeId + '-' + s.nodeType">
              <tr
                class="trace-row"
                :class="{
                  'trace-row--highlight':
                    densityTraceHighlightNodeId != null &&
                    String(densityTraceHighlightNodeId) === String(s.nodeId),
                }"
                @mouseenter="onTraceRowEnter(s.nodeId)"
                @mouseleave="onTraceRowLeave"
              >
                <td>{{ s.index }}</td>
                <td>{{ s.nodeType }}</td>
                <td class="trace-cell-num">{{ formatTraceNum(s.value) }}</td>
              </tr>
              <tr
                v-if="s.anchorSet"
                class="trace-row trace-row--anchor"
                :class="{
                  'trace-row--highlight':
                    densityTraceHighlightNodeId != null &&
                    String(densityTraceHighlightNodeId) === String(s.nodeId),
                }"
                @mouseenter="onTraceRowEnter(s.nodeId)"
                @mouseleave="onTraceRowLeave"
              >
                <td class="trace-cell-anchor-mark" aria-hidden="true" />
                <td colspan="2" class="trace-cell-anchor">Context density anchor (world) {{ formatAnchorWorld(s.anchorSet) }}</td>
              </tr>
            </template>
          </tbody>
        </table>
      </div>
    </template>
  </div>
</template>

<style scoped>
/* Default placement: top-right over the node editor (same as pre-drag behavior). */
.density-trace-panel {
  position: fixed;
  top: calc(60px + 8px);
  right: 12px;
  left: auto;
  max-width: min(440px, calc(100vw - 28px));
  display: flex;
  flex-direction: column;
  gap: 0.35rem;
  padding: 0.45rem 0.55rem;
  background: rgba(8, 10, 12, 0.92);
  border: 1px solid #2a333d;
  border-radius: 6px;
  z-index: 200;
  font-size: 0.68rem;
  color: #c8d0d8;
  box-shadow: 0 8px 28px rgba(0, 0, 0, 0.45);
  min-height: 0;
  overflow: hidden;
}

.density-trace-panel--split {
  right: calc(50vw + 12px);
  max-width: min(440px, calc(50vw - 24px));
}

.density-trace-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.5rem;
  flex-shrink: 0;
}

.density-trace-drag-handle {
  cursor: grab;
  user-select: none;
  touch-action: none;
  margin: -0.15rem -0.1rem 0;
  padding: 0.15rem 0.1rem 0.25rem;
  border-radius: 4px;
}
.density-trace-drag-handle:active {
  cursor: grabbing;
}

.density-trace-title {
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  color: #f0b429;
  font-size: 0.62rem;
}

.trace-close-btn {
  background: #1a1f24;
  border: 1px solid #3a4550;
  color: #aab;
  padding: 0.1rem 0.4rem;
  border-radius: 3px;
  font-size: 0.62rem;
  cursor: pointer;
}
.trace-close-btn:hover {
  color: #fff;
  border-color: #5a6570;
}

.trace-node-id {
  margin: 0;
  flex-shrink: 0;
  font-family: ui-monospace, monospace;
  font-size: 0.62rem;
  color: #7a8a98;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* Match DensityNode3DView `.render-distance-control` / `.render-distance-input` */
.trace-coords-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.35rem 0.45rem;
  font-family: ui-monospace, monospace;
  flex-shrink: 0;
}

.trace-coord-group {
  display: inline-flex;
  align-items: center;
  gap: 0.25rem;
}

.trace-axis-label {
  font-size: 0.65rem;
  font-weight: 700;
  color: #8a9aaa;
  flex-shrink: 0;
}

.render-distance-control {
  display: inline-flex;
  align-items: center;
  background: #101010;
  border-radius: 999px;
  overflow: hidden;
  border: 1px solid #333;
}

.trace-coord-pill .render-distance-input {
  width: 3.25rem;
  min-width: 0;
}

.render-distance-input {
  width: 52px;
  padding: 0.15rem 0.35rem;
  background: transparent;
  border: none;
  color: #e5e5e5;
  font-size: 0.75rem;
  text-align: center;
}

.render-distance-input:focus {
  outline: none;
}

.render-distance-input::-webkit-outer-spin-button,
.render-distance-input::-webkit-inner-spin-button {
  -webkit-appearance: none;
  margin: 0;
}

.render-distance-input[type='number'] {
  -moz-appearance: textfield;
}

.trace-status {
  margin: 0;
  flex-shrink: 0;
  font-family: ui-monospace, monospace;
  color: #9ecfff;
}

.trace-error {
  margin: 0;
  flex-shrink: 0;
  color: #ff8a8a;
}

.trace-table-wrap {
  margin-top: 0.15rem;
  flex: 1 1 auto;
  min-height: 0;
  overflow-x: auto;
  overflow-y: auto;
  -webkit-overflow-scrolling: touch;
}

.trace-table {
  width: 100%;
  border-collapse: collapse;
  font-family: ui-monospace, monospace;
  font-size: 0.62rem;
}

.trace-table th,
.trace-table td {
  padding: 0.15rem 0.28rem;
  border-bottom: 1px solid #222a32;
  text-align: left;
  vertical-align: top;
}

.trace-table th {
  position: sticky;
  top: 0;
  z-index: 1;
  color: #7a8a98;
  font-weight: 600;
  background: rgba(8, 10, 12, 0.97);
}

.trace-cell-num {
  text-align: right;
  white-space: nowrap;
  color: #8fd4a2;
}

.trace-row {
  cursor: default;
}
/* Subtle hover when not the active trace row — same hue as graph nodes */
.trace-row:hover:not(.trace-row--highlight) {
  background: rgba(240, 180, 41, 0.14);
}
/* Same id as graph; list hover uses source `'list'` so the node gets `.trace-highlight` */
.trace-row--highlight {
  background: rgba(240, 180, 41, 0.48) !important;
  box-shadow: inset 3px 0 0 #f0b429;
}
.trace-row--highlight:hover {
  background: rgba(240, 180, 41, 0.58) !important;
}

.trace-row--anchor td {
  border-bottom: 1px solid #222a32;
  padding-top: 0.05rem;
  padding-bottom: 0.2rem;
  color: #b8a0e8;
  font-size: 0.58rem;
}

.trace-cell-anchor-mark {
  width: 0.85rem;
  border-bottom-color: #1a2228;
}

.trace-cell-anchor {
  font-style: italic;
  letter-spacing: 0.01em;
}
</style>

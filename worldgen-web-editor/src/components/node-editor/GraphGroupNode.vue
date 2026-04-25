<script setup lang="ts">
import type { NodeProps } from '@vue-flow/core'
import { useVueFlow } from '@vue-flow/core'
import { ref, watch, onMounted, onUnmounted, computed, nextTick } from 'vue'
import { flowFontPxForScreen } from './flow-chrome-font'

interface Props extends NodeProps {
  /** `locked === true`: dragging the group moves overlapping nodes. Omitted / `false`: group moves alone (default). */
  data: { name: string; locked?: boolean }
}

const props = defineProps<Props>()
const emit = defineEmits<{
  'update:name': [string]
  'update:locked': [boolean]
  'update:size': [{ width: number; height: number }]
}>()

const { viewport, findNode, getNodes } = useVueFlow()

const labelEditing = ref(false)
const nameDraft = ref(props.data?.name ?? '')
const labelInputRef = ref<HTMLInputElement | null>(null)

watch(
  () => props.data?.name,
  (n) => {
    if (!labelEditing.value) nameDraft.value = n ?? ''
  },
)

function readStyleDim(v: unknown, fallback: number): number {
  if (typeof v === 'number' && v > 0) return v
  if (typeof v === 'string') {
    const n = parseFloat(v)
    if (Number.isFinite(n) && n > 0) return n
  }
  return fallback
}

/**
 * Vue Flow applies `node.style` on the wrapper, not on custom node props — read live size from the graph node.
 */
const widthPx = computed(() => {
  void getNodes.value
  const n = findNode(props.id) as { style?: Record<string, unknown> } | undefined
  const w = readStyleDim(n?.style?.width, 0)
  if (w > 0) return w
  const dw = props.dimensions?.width
  if (typeof dw === 'number' && dw > 0) return dw
  return 560
})

const heightPx = computed(() => {
  void getNodes.value
  const n = findNode(props.id) as { style?: Record<string, unknown> } | undefined
  const h = readStyleDim(n?.style?.height, 0)
  if (h > 0) return h
  const dh = props.dimensions?.height
  if (typeof dh === 'number' && dh > 0) return dh
  return 240
})

const resizePreview = ref<{ width: number; height: number } | null>(null)
const showW = computed(() => resizePreview.value?.width ?? widthPx.value)
const showH = computed(() => resizePreview.value?.height ?? heightPx.value)

/** Keeps title near ~13px on screen across zoom (capped in flow px so it does not explode when zoomed in). */
const titleFontFlow = computed(() =>
  flowFontPxForScreen(viewport.value.zoom ?? 1, 13.5, { minFlow: 10, maxFlow: 44 }),
)

const isLocked = computed(() => props.data?.locked === true)

function toggleCarryLock(e: Event) {
  e.stopPropagation()
  emit('update:locked', !isLocked.value)
}

function beginLabelEdit() {
  nameDraft.value = props.data?.name ?? ''
  labelEditing.value = true
  void nextTick(() => labelInputRef.value?.focus())
}

function commitLabel() {
  labelEditing.value = false
  emit('update:name', nameDraft.value.trim())
}

function cancelLabel() {
  nameDraft.value = props.data?.name ?? ''
  labelEditing.value = false
}

let resizeActive = false
let startX = 0
let startY = 0
let startW = 0
let startH = 0
let resizeCaptureEl: HTMLElement | null = null

function onResizePointerDown(e: PointerEvent) {
  e.stopPropagation()
  e.preventDefault()
  resizeActive = true
  startX = e.clientX
  startY = e.clientY
  startW = widthPx.value
  startH = heightPx.value
  resizePreview.value = { width: startW, height: startH }
  resizeCaptureEl = e.target as HTMLElement
  resizeCaptureEl.setPointerCapture(e.pointerId)
}

function onResizePointerMove(e: PointerEvent) {
  if (!resizeActive) return
  const z = viewport.value.zoom || 1
  const dx = (e.clientX - startX) / z
  const dy = (e.clientY - startY) / z
  const w = Math.max(120, Math.round(startW + dx))
  const h = Math.max(96, Math.round(startH + dy))
  resizePreview.value = { width: w, height: h }
}

function endResize(e: PointerEvent) {
  if (!resizeActive) return
  resizeActive = false
  const final = resizePreview.value
  resizePreview.value = null
  if (final) {
    emit('update:size', { width: final.width, height: final.height })
  }
  if (resizeCaptureEl) {
    try {
      resizeCaptureEl.releasePointerCapture(e.pointerId)
    } catch {
      /* ignore */
    }
    resizeCaptureEl = null
  }
}

onMounted(() => {
  window.addEventListener('pointermove', onResizePointerMove)
  window.addEventListener('pointerup', endResize)
  window.addEventListener('pointercancel', endResize)
})

onUnmounted(() => {
  window.removeEventListener('pointermove', onResizePointerMove)
  window.removeEventListener('pointerup', endResize)
  window.removeEventListener('pointercancel', endResize)
})
</script>

<template>
  <div
    class="graph-group-root"
    :class="{ selected: props.selected }"
    :style="{ width: `${showW}px`, height: `${showH}px` }"
  >
    <div
      v-if="labelEditing"
      class="graph-group-header graph-group-header--editing nodrag nopan"
      @mousedown.stop
      @pointerdown.stop
    >
      <input
        ref="labelInputRef"
        v-model="nameDraft"
        class="graph-group-header-input nopan"
        type="text"
        :style="{ fontSize: `${titleFontFlow}px` }"
        @blur="commitLabel"
        @keydown.enter.prevent="commitLabel"
        @keydown.escape.prevent="cancelLabel"
      />
      <button
        type="button"
        class="graph-group-lock-btn nodrag nopan"
        :class="{ 'graph-group-lock-btn--unlocked': !isLocked }"
        :title="
          isLocked
            ? 'Locked: overlapping nodes move with the group. Click to unlock.'
            : 'Unlocked: group moves alone. Click to lock.'
        "
        :aria-pressed="isLocked"
        @click.stop.prevent="toggleCarryLock"
        @pointerdown.stop
      >
        <svg
          v-if="isLocked"
          class="graph-group-lock-icon"
          viewBox="0 0 24 24"
          aria-hidden="true"
        >
          <path
            fill="currentColor"
            d="M18 10h-1V7c0-2.76-2.24-5-5-5S7 4.24 7 7v3H6c-1.1 0-2 .9-2 2v8c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2v-8c0-1.1-.9-2-2-2zm-6 9c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2zm3-9h-6V7c0-1.66 1.34-3 3-3s3 1.34 3 3v3z"
          />
        </svg>
        <svg
          v-else
          class="graph-group-lock-icon"
          viewBox="0 0 24 24"
          aria-hidden="true"
        >
          <path
            fill="currentColor"
            d="M18 10h-1V7c0-2.76-2.24-5-5-5-2.55 0-4.73 1.89-5.06 4.4L7.4 7.31A3.01 3.01 0 0 1 12 4c1.66 0 3 1.34 3 3v3H6c-1.1 0-2 .9-2 2v8c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2v-8c0-1.1-.9-2-2-2zm-6 9c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2z"
          />
        </svg>
      </button>
    </div>
    <div
      v-else
      class="graph-group-header"
      title="Drag to move · Double-click to rename"
      :style="{ fontSize: `${titleFontFlow}px` }"
      @dblclick.self.prevent="beginLabelEdit"
    >
      <span
        class="graph-group-header-text"
        @dblclick.stop.prevent="beginLabelEdit"
      >{{ props.data?.name || 'Group' }}</span>
      <button
        type="button"
        class="graph-group-lock-btn nodrag nopan"
        :class="{ 'graph-group-lock-btn--unlocked': !isLocked }"
        :title="
          isLocked
            ? 'Locked: overlapping nodes move with the group. Click to unlock.'
            : 'Unlocked: group moves alone. Click to lock.'
        "
        :aria-pressed="isLocked"
        @click.stop.prevent="toggleCarryLock"
        @pointerdown.stop
      >
        <svg
          v-if="isLocked"
          class="graph-group-lock-icon"
          viewBox="0 0 24 24"
          aria-hidden="true"
        >
          <path
            fill="currentColor"
            d="M18 10h-1V7c0-2.76-2.24-5-5-5S7 4.24 7 7v3H6c-1.1 0-2 .9-2 2v8c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2v-8c0-1.1-.9-2-2-2zm-6 9c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2zm3-9h-6V7c0-1.66 1.34-3 3-3s3 1.34 3 3v3z"
          />
        </svg>
        <svg
          v-else
          class="graph-group-lock-icon"
          viewBox="0 0 24 24"
          aria-hidden="true"
        >
          <path
            fill="currentColor"
            d="M18 10h-1V7c0-2.76-2.24-5-5-5-2.55 0-4.73 1.89-5.06 4.4L7.4 7.31A3.01 3.01 0 0 1 12 4c1.66 0 3 1.34 3 3v3H6c-1.1 0-2 .9-2 2v8c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2v-8c0-1.1-.9-2-2-2zm-6 9c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2z"
          />
        </svg>
      </button>
    </div>

    <div class="graph-group-body" />

    <div
      class="graph-group-resize nodrag nopan"
      title="Resize"
      @pointerdown="onResizePointerDown"
    />
  </div>
</template>

<style scoped>
.graph-group-root {
  position: relative;
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border-radius: 4px;
  border: 2px solid rgba(100, 160, 255, 0.55);
  background: rgba(40, 90, 200, 0.14);
  box-shadow: inset 0 0 0 1px rgba(255, 255, 255, 0.06);
}

.graph-group-root.selected {
  border-color: rgba(29, 209, 161, 0.75);
  background: rgba(29, 209, 161, 0.08);
}

.graph-group-header {
  flex: 0 0 auto;
  width: 100%;
  box-sizing: border-box;
  padding: 0.35em 0.45em 0.35em 0.55em;
  display: flex;
  align-items: center;
  gap: 0.35em;
  min-height: 1.6em;
  background: rgba(22, 38, 78, 0.92);
  border-bottom: 1px solid rgba(100, 160, 255, 0.45);
  color: rgba(200, 225, 255, 0.98);
  font-weight: 600;
  cursor: grab;
  text-shadow: 0 1px 2px rgba(0, 0, 0, 0.45);
}

.graph-group-header:active {
  cursor: grabbing;
}

.graph-group-header-text {
  flex: 1 1 auto;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  cursor: grab;
}

.graph-group-header--editing {
  cursor: default;
  padding: 0.25em 0.45em;
  gap: 0.35em;
}

.graph-group-header-input {
  flex: 1 1 auto;
  min-width: 0;
  box-sizing: border-box;
  padding: 0.2em 0.45em;
  border-radius: 3px;
  border: 1px solid rgba(120, 170, 255, 0.65);
  background: rgba(12, 18, 32, 0.98);
  color: #e8f0ff;
  font-weight: 600;
}

.graph-group-lock-btn {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  justify-content: center;
  margin: 0;
  padding: 0.12em;
  border: none;
  border-radius: 4px;
  background: rgba(255, 255, 255, 0.08);
  color: rgba(210, 230, 255, 0.92);
  cursor: pointer;
  line-height: 0;
}

.graph-group-lock-btn:hover {
  background: rgba(255, 255, 255, 0.16);
  color: #fff;
}

.graph-group-lock-btn--unlocked {
  color: #f4cc1a;
}

.graph-group-lock-btn--unlocked:hover {
  color: #ffe566;
  background: rgba(244, 204, 26, 0.2);
}

.graph-group-lock-icon {
  width: 1.15em;
  height: 1.15em;
  display: block;
}

.graph-group-body {
  flex: 1 1 auto;
  min-height: 0;
  width: 100%;
  box-sizing: border-box;
  pointer-events: none;
}

.graph-group-resize {
  position: absolute;
  right: -4px;
  bottom: -4px;
  width: 22px;
  height: 22px;
  cursor: nwse-resize;
  touch-action: none;
  background: linear-gradient(
    135deg,
    transparent 52%,
    rgba(160, 200, 255, 0.75) 52%
  );
  border-radius: 0 0 6px 0;
  z-index: 4;
  pointer-events: auto;
  box-sizing: border-box;
}
</style>

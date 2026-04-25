<script setup lang="ts">
import type { NodeProps } from '@vue-flow/core'
import { useVueFlow } from '@vue-flow/core'
import { ref, watch, onMounted, onUnmounted, computed, nextTick } from 'vue'
import { flowFontPxForScreen } from './flow-chrome-font'

export interface EditorCommentNodeData {
  blockName: string
  text: string
  /** Persisted in JSON; not edited in the web UI (default 21 for new nodes). */
  fontSize: number
}

interface Props extends NodeProps {
  data: EditorCommentNodeData
}

const props = defineProps<Props>()
const emit = defineEmits<{
  'update:blockName': [string]
  'update:text': [string]
  'update:size': [{ width: number; height: number }]
}>()

const { viewport, findNode, getNodes } = useVueFlow()

const labelEditing = ref(false)
const textEditing = ref(false)
const nameDraft = ref(props.data?.blockName ?? 'Comment')
const textDraft = ref(props.data?.text ?? '')
const textAreaRef = ref<HTMLTextAreaElement | null>(null)

watch(
  () => props.data?.blockName,
  (n) => {
    if (!labelEditing.value) nameDraft.value = n ?? 'Comment'
  },
)

watch(
  () => props.data?.text,
  (t) => {
    if (!textEditing.value) textDraft.value = t ?? ''
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

const widthPx = computed(() => {
  void getNodes.value
  const n = findNode(props.id) as { style?: Record<string, unknown> } | undefined
  const w = readStyleDim(n?.style?.width, 0)
  if (w > 0) return w
  const dw = props.dimensions?.width
  if (typeof dw === 'number' && dw > 0) return dw
  return 400
})

const heightPx = computed(() => {
  void getNodes.value
  const n = findNode(props.id) as { style?: Record<string, unknown> } | undefined
  const h = readStyleDim(n?.style?.height, 0)
  if (h > 0) return h
  const dh = props.dimensions?.height
  if (typeof dh === 'number' && dh > 0) return dh
  return 160
})

const resizePreview = ref<{ width: number; height: number } | null>(null)
const showW = computed(() => resizePreview.value?.width ?? widthPx.value)
const showH = computed(() => resizePreview.value?.height ?? heightPx.value)

const bodyFontPx = computed(() => {
  const n = Math.round(Number(props.data?.fontSize) || 21)
  return Math.min(96, Math.max(8, n))
})

const titleFontFlow = computed(() =>
  flowFontPxForScreen(viewport.value.zoom ?? 1, 13.5, { minFlow: 10, maxFlow: 44 }),
)

const labelInputRef = ref<HTMLInputElement | null>(null)

function beginLabelEdit() {
  nameDraft.value = props.data?.blockName ?? 'Comment'
  labelEditing.value = true
  void nextTick(() => labelInputRef.value?.focus())
}

function commitLabel() {
  labelEditing.value = false
  emit('update:blockName', nameDraft.value.trim() || 'Comment')
}

function cancelLabel() {
  nameDraft.value = props.data?.blockName ?? 'Comment'
  labelEditing.value = false
}

function commitText() {
  emit('update:text', textDraft.value)
}

function beginTextEdit() {
  textEditing.value = true
  textDraft.value = props.data?.text ?? ''
  void nextTick(() => {
    textAreaRef.value?.focus()
    textAreaRef.value?.select()
  })
}

function endTextEdit() {
  textEditing.value = false
  commitText()
}

function onTextKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape') {
    e.preventDefault()
    textDraft.value = props.data?.text ?? ''
    textEditing.value = false
  }
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
  const w = Math.max(160, Math.round(startW + dx))
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
    class="editor-comment-root"
    :class="{ selected: props.selected }"
    :style="{ width: `${showW}px`, height: `${showH}px` }"
  >
    <div
      v-if="labelEditing"
      class="editor-comment-header editor-comment-header--editing nodrag nopan"
      @mousedown.stop
      @pointerdown.stop
    >
      <input
        ref="labelInputRef"
        v-model="nameDraft"
        class="editor-comment-header-input nopan"
        type="text"
        :style="{ fontSize: `${titleFontFlow}px` }"
        @blur="commitLabel"
        @keydown.enter.prevent="commitLabel"
        @keydown.escape.prevent="cancelLabel"
      />
    </div>
    <div
      v-else
      class="editor-comment-header"
      title="Drag to move · Double-click to rename ($name)"
      :style="{ fontSize: `${titleFontFlow}px` }"
      @dblclick.stop.prevent="beginLabelEdit"
    >
      <span class="editor-comment-header-text">{{ props.data?.blockName || 'Comment' }}</span>
    </div>

    <div
      v-if="!textEditing"
      class="editor-comment-body editor-comment-body-view"
      :class="{ 'is-placeholder': !String(textDraft).trim() }"
      :style="{ fontSize: `${bodyFontPx}px` }"
      title="Drag to move · Double-click to edit text"
      @dblclick.stop.prevent="beginTextEdit"
    >
      {{ String(textDraft).trim() ? textDraft : 'Double click to edit…' }}
    </div>
    <textarea
      v-else
      ref="textAreaRef"
      v-model="textDraft"
      class="editor-comment-body editor-comment-body-input nodrag nopan"
      :style="{ fontSize: `${bodyFontPx}px` }"
      spellcheck="true"
      placeholder="Comment text…"
      @blur="endTextEdit"
      @keydown="onTextKeydown"
    />

    <div
      class="editor-comment-resize nodrag nopan"
      title="Resize"
      @pointerdown="onResizePointerDown"
    />
  </div>
</template>

<style scoped>
.editor-comment-root {
  position: relative;
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border-radius: 4px;
  border: 2px solid rgba(220, 170, 70, 0.55);
  background: rgba(55, 42, 12, 0.42);
  box-shadow: inset 0 0 0 1px rgba(255, 255, 255, 0.05);
}

.editor-comment-root.selected {
  border-color: rgba(255, 210, 100, 0.85);
  background: rgba(70, 52, 14, 0.5);
}

.editor-comment-header {
  flex: 0 0 auto;
  width: 100%;
  box-sizing: border-box;
  padding: 0.35em 0.55em;
  display: flex;
  align-items: center;
  min-height: 1.6em;
  background: rgba(48, 32, 8, 0.95);
  border-bottom: 1px solid rgba(200, 150, 70, 0.5);
  color: rgba(255, 220, 165, 0.98);
  font-weight: 600;
  cursor: grab;
  text-shadow: 0 1px 2px rgba(0, 0, 0, 0.45);
}

.editor-comment-header:active {
  cursor: grabbing;
}

.editor-comment-header-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  width: 100%;
}

.editor-comment-header--editing {
  cursor: default;
  padding: 0.25em 0.45em;
}

.editor-comment-header-input {
  width: 100%;
  box-sizing: border-box;
  padding: 0.2em 0.45em;
  border-radius: 3px;
  border: 1px solid rgba(255, 190, 90, 0.55);
  background: rgba(32, 24, 8, 0.98);
  color: #fff4dc;
  font-weight: 600;
}

.editor-comment-body {
  flex: 1 1 auto;
  min-height: 0;
  width: 100%;
  box-sizing: border-box;
  margin: 0;
  padding: 10px 12px;
  color: rgba(255, 248, 220, 0.96);
  line-height: 1.35;
  font-family: ui-sans-serif, system-ui, sans-serif;
}

.editor-comment-body-view {
  white-space: pre-wrap;
  word-break: break-word;
  overflow: auto;
  cursor: grab;
  user-select: none;
}

.editor-comment-body-view:active {
  cursor: grabbing;
}

.editor-comment-body-view.is-placeholder {
  color: rgba(255, 248, 220, 0.45);
}

.editor-comment-body-input {
  resize: none;
  display: block;
  border: none;
  border-radius: 0;
  background: rgba(40, 30, 10, 0.55);
  outline: none;
}

.editor-comment-resize {
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
    rgba(255, 200, 120, 0.75) 52%
  );
  border-radius: 0 0 6px 0;
  z-index: 4;
  pointer-events: auto;
  box-sizing: border-box;
}
</style>

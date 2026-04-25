<script setup lang="ts">
import { Handle, Position, useVueFlow } from '@vue-flow/core'
import type { NodeProps } from '@vue-flow/core'
import type { NodeDefinition } from '@/services/node-loader'
import { getCategoryColor, getHandleColor } from '@/services/color-utils'
import { useRouter } from 'vue-router'
import { useEditorStore } from '@/stores/editor'
import { storeToRefs } from 'pinia'
import { computed, ref, watch, onMounted, onUnmounted, nextTick } from 'vue'

const { getEdges, findNode } = useVueFlow()
const store = useEditorStore()
const { densityTraceHighlightNodeId, densityTraceHighlightSource } = storeToRefs(store)

/** Read from store here so each node re-renders when metrics update (Vue Flow slot parent may not re-run). */
const debugNodeMetrics = computed(() => store.nodeMetrics[String(props.id)])

interface Props extends NodeProps {
  data: {
    definition: NodeDefinition;
    values: Record<string, string | number | boolean>
    /** User override for the header; when absent, `definition.Title` is shown. */
    displayTitle?: string
  }
}

const props = defineProps<Props>()
const emit = defineEmits<{
  'update:value': [{ id: string; value: string | number | boolean }]
  'update:meta': [{ displayTitle?: string | null }]
}>()

const router = useRouter()

/** Amber node tint only when hover started on the trace list (`source === 'list'`), not from the graph. */
const isTraceNodeTintFromList = computed(
  () =>
    densityTraceHighlightNodeId.value != null &&
    String(densityTraceHighlightNodeId.value) === String(props.id) &&
    densityTraceHighlightSource.value === 'list'
)

function updateValue(id: string, value: string | number | boolean) {
  emit('update:value', { id, value })
}

function selectAllInput(event: Event) {
  const target = event.target as HTMLInputElement | HTMLTextAreaElement | null
  if (target) target.select()
}

/** Local edit buffer; keys only exist while a field is being edited — commit on blur / Enter. */
const valueDrafts = ref<Record<string, string>>({})

function displayFieldDraftOrCommitted(
  id: string,
  committed: string | number | boolean | undefined,
): string {
  const draft = valueDrafts.value[id]
  if (draft !== undefined) return draft
  if (committed === undefined || committed === null) return ''
  return String(committed)
}

function seedFieldDraft(id: string, committed: string | number | boolean | undefined) {
  valueDrafts.value = {
    ...valueDrafts.value,
    [id]: committed === undefined || committed === null ? '' : String(committed),
  }
}

function removeFieldDraft(id: string) {
  if (!Object.prototype.hasOwnProperty.call(valueDrafts.value, id)) return
  const next = { ...valueDrafts.value }
  delete next[id]
  valueDrafts.value = next
}

function cancelFieldDraft(id: string) {
  removeFieldDraft(id)
}

/** Range slider: keep local position while dragging; `updateValue` only on `@change` (release). */
const intSliderRangePreview = ref<Record<string, number>>({})

function intSliderRangeDisplay(id: string, committed: string | number | boolean | undefined): number {
  const preview = intSliderRangePreview.value[id]
  if (preview !== undefined) return preview
  if (typeof committed === 'number' && !Number.isNaN(committed)) return committed
  const n = parseInt(String(committed ?? 0), 10)
  return Number.isNaN(n) ? 0 : n
}

function onIntSliderRangeInput(id: string, e: Event) {
  const v = parseInt((e.target as HTMLInputElement).value, 10)
  intSliderRangePreview.value = { ...intSliderRangePreview.value, [id]: Number.isNaN(v) ? 0 : v }
}

function onIntSliderRangeChange(id: string, e: Event) {
  const v = parseInt((e.target as HTMLInputElement).value, 10)
  const next = { ...intSliderRangePreview.value }
  delete next[id]
  intSliderRangePreview.value = next
  updateValue(id, Number.isNaN(v) ? 0 : v)
}

function commitStringField(id: string) {
  const draft = valueDrafts.value[id]
  if (draft === undefined) return
  removeFieldDraft(id)
  updateValue(id, draft)
}

function onStringFieldFocus(id: string, e: Event) {
  seedFieldDraft(id, props.data.values[id] as string | number | boolean | undefined)
  selectAllInput(e)
}

function onStringFieldInput(id: string, e: Event) {
  valueDrafts.value = {
    ...valueDrafts.value,
    [id]: (e.target as HTMLInputElement).value,
  }
}

function commitNumericField(id: string, kind: 'float' | 'int') {
  const raw = valueDrafts.value[id]
  if (raw === undefined) return
  removeFieldDraft(id)

  if (raw === '' || raw === '-' || raw === '+') {
    updateValue(id, raw)
    return
  }
  if (kind === 'float' && (raw === '.' || raw === '-.' || raw === '+.')) {
    updateValue(id, raw)
    return
  }
  if (kind === 'float') {
    const n = parseFloat(raw)
    if (!Number.isNaN(n)) {
      updateValue(id, n)
    }
    return
  }
  const n = parseInt(raw, 10)
  if (!Number.isNaN(n)) {
    updateValue(id, n)
  }
}

function onNumericFieldFocus(id: string, e: Event) {
  seedFieldDraft(id, props.data.values[id] as string | number | boolean | undefined)
  selectAllInput(e)
}

/** Updates only the local draft while typing; parent value updates on blur / Enter. */
function onNumericFieldDraftInput(id: string, e: Event) {
  const raw = (e.target as HTMLInputElement).value
  valueDrafts.value = { ...valueDrafts.value, [id]: raw }
}

const effectiveTitle = computed(
  () => props.data.displayTitle?.trim() || props.data.definition.Title,
)

/** User-set label from metadata (`$DisplayTitle`); when set, header shows two lines (custom + type). */
const hasCustomDisplayTitle = computed(() => Boolean(props.data.displayTitle?.trim()))

/** Default / template title (second line when renamed, or solo centered label). */
const typeTitle = computed(() => props.data.definition.Title)

const titleEditing = ref(false)
const titleDraft = ref('')
const titleInputRef = ref<HTMLInputElement | null>(null)

/** Shrink-to-fit measured font sizes (px); `null` = use CSS `clamp` / defaults. */
const soloFitFontPx = ref<number | null>(null)
const customFitFontPx = ref<number | null>(null)
const secondaryFitFontPx = ref<number | null>(null)

const nodeHeaderRef = ref<HTMLElement | null>(null)
const soloTitleRef = ref<HTMLElement | null>(null)
const customTitleRef = ref<HTMLElement | null>(null)
const typeSecondaryRef = ref<HTMLElement | null>(null)

let titleFitRaf = 0
let titleResizeObserver: ResizeObserver | null = null

function measureLargestFontPx(
  el: HTMLElement,
  maxWidth: number,
  minPx: number,
  maxPx: number,
): number | null {
  const prev = el.style.fontSize
  try {
    if (maxWidth <= 0 || !Number.isFinite(maxPx) || maxPx < minPx) return null
    el.style.fontSize = `${maxPx}px`
    void el.offsetWidth
    if (el.scrollWidth <= maxWidth) return null

    let lo = minPx
    let hi = Math.floor(maxPx)
    let best = minPx
    while (lo <= hi) {
      const mid = Math.floor((lo + hi) / 2)
      el.style.fontSize = `${mid}px`
      void el.offsetWidth
      if (el.scrollWidth <= maxWidth) {
        best = mid
        lo = mid + 1
      } else {
        hi = mid - 1
      }
    }
    return best
  } finally {
    el.style.fontSize = prev
  }
}

function readDesignMaxFontPx(el: HTMLElement): number {
  el.style.removeProperty('font-size')
  void el.offsetWidth
  return parseFloat(getComputedStyle(el).fontSize)
}

function runTitleFit() {
  if (titleEditing.value) return

  soloFitFontPx.value = null
  customFitFontPx.value = null
  secondaryFitFontPx.value = null

  if (!hasCustomDisplayTitle.value) {
    const el = soloTitleRef.value
    if (!el) return
    const w = el.clientWidth
    if (w <= 0) return
    const maxPx = readDesignMaxFontPx(el)
    if (!Number.isFinite(maxPx)) return
    const fit = measureLargestFontPx(el, w, 9, maxPx)
    soloFitFontPx.value = fit
  } else {
    const c = customTitleRef.value
    if (c) {
      const w = c.clientWidth
      if (w > 0) {
        const maxPx = readDesignMaxFontPx(c)
        if (Number.isFinite(maxPx)) {
          customFitFontPx.value = measureLargestFontPx(c, w, 9, maxPx)
        }
      }
    }
    const s = typeSecondaryRef.value
    if (s) {
      const w = s.clientWidth
      if (w > 0) {
        const maxPx = readDesignMaxFontPx(s)
        if (Number.isFinite(maxPx)) {
          secondaryFitFontPx.value = measureLargestFontPx(s, w, 9, maxPx)
        }
      }
    }
  }
}

function scheduleTitleFit() {
  if (titleFitRaf) cancelAnimationFrame(titleFitRaf)
  titleFitRaf = requestAnimationFrame(() => {
    titleFitRaf = 0
    void nextTick(() => runTitleFit())
  })
}

watch(
  () => [props.id, typeTitle.value, hasCustomDisplayTitle.value, props.data.displayTitle, titleEditing.value],
  () => scheduleTitleFit(),
  { flush: 'post' },
)

function beginTitleEdit() {
  titleDraft.value = effectiveTitle.value
  titleEditing.value = true
  void nextTick(() => titleInputRef.value?.focus())
}

function commitTitleEdit() {
  titleEditing.value = false
  const trimmed = titleDraft.value.trim()
  const base = props.data.definition.Title
  emit('update:meta', {
    displayTitle: trimmed === base || !trimmed ? null : trimmed,
  })
}

function cancelTitleEdit() {
  titleDraft.value = effectiveTitle.value
  titleEditing.value = false
}

function stopInteraction(e: Event) {
  e.stopPropagation()
}

// Logical inputs = nodes in our Inputs array. In the graph loader, Sum.Inputs creates edges source=Sum target=child,
// so our input nodes are the *targets* of our outgoing edges (not incoming: that would be our parent e.g. Terrain).
const inputNodeIds = computed(() => {
  const edges = getEdges.value
  const outgoing = edges.filter((e: { source: string }) => e.source === props.id)
  return outgoing.map((e: { target: string }) => e.target)
})

const inputNodeLabels = computed(() => {
  return inputNodeIds.value.map((targetId) => {
    const node = findNode(targetId) as { data?: { definition?: { Title?: string; Id?: string } } } | undefined
    const def = node?.data?.definition
    return (def?.Title ?? def?.Id ?? '').trim()
  })
})

type NodeInfoField = { label: string; description: string }
type NodeInfo = { process: string; fields: NodeInfoField[] }

// Optional process descriptions (backend `process(...)`) for specific nodes.
const NODE_PROCESS_INFO: Record<string, NodeInfo> = {
  // YOverrideDensity.process(context)
  'YOverride.Density': {
    process: 'Overrides the vertical position: evaluates the input density at the same X/Z coordinates, but with Y forced to a fixed value.',
    fields: [
      { label: 'Value', description: 'The fixed Y coordinate used for sampling the input density.' },
    ],
  },
  'YOverride Density': {
    process: 'Overrides the vertical position: evaluates the input density at the same X/Z coordinates, but with Y forced to a fixed value.',
    fields: [
      { label: 'Value', description: 'The fixed Y coordinate used for sampling the input density.' },
    ],
  },

  // ConstantValueDensity.process(context)
  'ConstantDensityNode': {
    process: 'Always returns the configured constant density value, regardless of position.',
    fields: [
      { label: 'Value', description: 'The density value returned at every sampled position.' },
    ],
  },
  'Constant Density': {
    process: 'Always returns the configured constant density value, regardless of position.',
    fields: [
      { label: 'Value', description: 'The density value returned at every sampled position.' },
    ],
  },

  // SumDensity.process(context)
  'SumDensityNode': {
    process: 'Adds all connected input densities together by evaluating each input at the same sampling position and summing the results.',
    fields: [],
  },

  // SimplexNoise3DDensityAsset -> Noise3dDensity.process(context)
  'SimplexNoise3DDensityNode': {
    process: 'Generates density from 3D simplex noise at the current position (x/y/z) using the configured seed, scale, and octave settings.',
    fields: [
      { label: 'Lacunarity', description: 'Frequency multiplier across octaves.' },
      { label: 'Persistence', description: 'Amplitude multiplier across octaves.' },
      { label: 'ScaleXZ', description: 'Noise scale for X and Z.' },
      { label: 'ScaleY', description: 'Noise scale for Y.' },
      { label: 'Octaves', description: 'How many noise layers (octaves) are combined.' },
      { label: 'Seed', description: 'Seed key controlling the noise field.' },
    ],
  },
  'SimplexNoise3D Density': {
    process: 'Generates density from 3D simplex noise at the current position (x/y/z) using the configured seed, scale, and octave settings.',
    fields: [
      { label: 'Lacunarity', description: 'Frequency multiplier across octaves.' },
      { label: 'Persistence', description: 'Amplitude multiplier across octaves.' },
      { label: 'ScaleXZ', description: 'Noise scale for X and Z.' },
      { label: 'ScaleY', description: 'Noise scale for Y.' },
      { label: 'Octaves', description: 'How many noise layers (octaves) are combined.' },
      { label: 'Seed', description: 'Seed key controlling the noise field.' },
    ],
  },

  // SimplexNoise2dDensityAsset -> MultiCacheDensity( Noise2dDensity ) -> YOverrideDensity(..., 0)
  'SimplexNoise2DDensityNode': {
    process: 'Generates density from 2D simplex noise sampled at (x, z); internally it caches results and forces Y to 0 for sampling.',
    fields: [
      { label: 'Lacunarity', description: 'Frequency multiplier across octaves.' },
      { label: 'Persistence', description: 'Amplitude multiplier across octaves.' },
      { label: 'Scale', description: 'Noise scale for the X/Z axes.' },
      { label: 'Octaves', description: 'How many noise layers (octaves) are combined.' },
      { label: 'Seed', description: 'Seed key controlling the noise field.' },
    ],
  },
  'SimplexNoise2D Density': {
    process: 'Generates density from 2D simplex noise sampled at (x, z); internally it caches results and forces Y to 0 for sampling.',
    fields: [
      { label: 'Lacunarity', description: 'Frequency multiplier across octaves.' },
      { label: 'Persistence', description: 'Amplitude multiplier across octaves.' },
      { label: 'Scale', description: 'Noise scale for the X/Z axes.' },
      { label: 'Octaves', description: 'How many noise layers (octaves) are combined.' },
      { label: 'Seed', description: 'Seed key controlling the noise field.' },
    ],
  },

  // CellNoise2DDensityAsset -> CellNoiseField/Noise2dDensity -> MultiCacheDensity -> YOverrideDensity(..., 0)
  'CellNoise2DDensityNode': {
    process: 'Generates density from 2D cellular noise sampled at (x, z); internally it caches results and forces Y to 0 for sampling.',
    fields: [
      { label: 'Jitter', description: 'Cellular jitter amount (affects how feature points are distributed inside cells).' },
      { label: 'CellType', description: 'Selects which cellular return value is produced (cell value or one of the distance formulas).' },
      { label: 'ScaleX', description: 'Cell noise scale for the X axis.' },
      { label: 'ScaleZ', description: 'Cell noise scale for the Z axis.' },
      { label: 'Octaves', description: 'How many fractal octaves (layers) are combined.' },
      { label: 'Seed', description: 'Seed key controlling the noise field.' },
    ],
  },
  'CellNoise2D Density': {
    process: 'Generates density from 2D cellular noise sampled at (x, z); internally it caches results and forces Y to 0 for sampling.',
    fields: [
      { label: 'Jitter', description: 'Cellular jitter amount (affects how feature points are distributed inside cells).' },
      { label: 'CellType', description: 'Selects which cellular return value is produced (cell value or one of the distance formulas).' },
      { label: 'ScaleX', description: 'Cell noise scale for the X axis.' },
      { label: 'ScaleZ', description: 'Cell noise scale for the Z axis.' },
      { label: 'Octaves', description: 'How many fractal octaves (layers) are combined.' },
      { label: 'Seed', description: 'Seed key controlling the noise field.' },
    ],
  },

  // CellNoise3DDensityAsset -> CellNoiseField/Noise3dDensity
  'CellNoise3DDensityNode': {
    process: 'Generates density from 3D cellular noise sampled at (x/y/z). The coordinates are scaled by ScaleX/ScaleY/ScaleZ before sampling, and the noise output is controlled by Jitter, Octaves, CellType, and Seed.',
    fields: [
      { label: 'Jitter', description: 'Cellular jitter amount (affects how feature points are distributed inside cells).' },
      { label: 'CellType', description: 'Selects which cellular return value is produced (cell value or one of the distance formulas).' },
      { label: 'ScaleX', description: 'Cell noise scale for the X axis.' },
      { label: 'ScaleY', description: 'Cell noise scale for the Y axis.' },
      { label: 'ScaleZ', description: 'Cell noise scale for the Z axis.' },
      { label: 'Octaves', description: 'How many fractal octaves (layers) are combined.' },
      { label: 'Seed', description: 'Seed key controlling the noise field.' },
    ],
  },

  // CurveMapperDensity.process(context)
  'CurveMapper.Density': {
    process: 'Remaps density through the configured curve: evaluates the input density, then feeds the result into the curve function.',
    fields: [
      // Intentionally no connected-node descriptions (Curve is an input connection).
    ],
  },
  'CurveMapper Density': {
    process: 'Remaps density through the configured curve: evaluates the input density, then feeds the result into the curve function.',
    fields: [
      // Intentionally no connected-node descriptions (Curve is an input connection).
    ],
  },

  // OffsetConstantDensity / OffsetDensity
  'OffsetConstantDensityNode': {
    process: 'Adds a constant offset to the connected input density (samples input at the same position, then adds the configured value).',
    fields: [{ label: 'Value', description: 'Constant value added to the input density.' }],
  },
  'OffsetDensityNode': {
    process: 'Adds a Y-dependent offset to the connected input density: evaluates the input at the current position and then adds `FunctionForY(Y)`.',
    fields: [],
  },

  // AmplitudeConstantDensity / AmplitudeDensity
  'AmplitudeConstantDensityNode': {
    process: 'Multiplies the connected input density by a constant amplitude.',
    fields: [{ label: 'Value', description: 'Constant multiplier applied to the input density.' }],
  },
  'AmplitudeDensityNode': {
    process: 'Multiplies the connected input density by an amplitude function of Y (`FunctionForY(Y)`). If the amplitude is ~0 (|amplitude| < 1e-9), the output becomes 0.',
    fields: [],
  },

  // Multiply / Min / Max
  'MultiplierDensityNode': {
    process: 'Multiplies all connected input densities together (evaluated at the same position). If there are no inputs, returns 0.',
    fields: [],
  },
  'MinDensityNode': {
    process: 'Outputs the minimum value among all connected input densities (evaluated at the same position).',
    fields: [],
  },
  'MaxDensityNode': {
    process: 'Outputs the maximum value among all connected input densities (evaluated at the same position).',
    fields: [],
  },

  // Smooth min/max
  'SmoothMinDensityNode': {
    process: 'Smooth minimum of the first two connected inputs using `Calculator.smoothMin(range, A, B)`. (If fewer than two inputs are connected, returns 0.)',
    fields: [{ label: 'Range', description: 'Smoothing range around the intersection of the two inputs.' }],
  },
  'SmoothMaxDensityNode': {
    process: 'Smooth maximum of the first two connected inputs using `Calculator.smoothMax(range, A, B)`. (If fewer than two inputs are connected, returns 0.)',
    fields: [{ label: 'Range', description: 'Smoothing range around the intersection of the two inputs.' }],
  },

  // Clamp variants
  'CeilingDensityNode': {
    process: 'Clamps from above: outputs `min(input, Limit)`.',
    fields: [{ label: 'Limit', description: 'Upper bound for the output density.' }],
  },
  'FloorDensityNode': {
    process: 'Clamps from below: outputs `max(input, Limit)`.',
    fields: [{ label: 'Limit', description: 'Lower bound for the output density.' }],
  },
  'SmoothCeilingDensityNode': {
    process: 'Smooth ceiling: smoothly transitions toward the ceiling within `Range` using `Calculator.smoothMin(range, input, Limit)`.',
    fields: [
      { label: 'Limit', description: 'Target ceiling limit.' },
      { label: 'Range', description: 'Smoothing range near the ceiling limit.' },
    ],
  },
  'SmoothFloorDensityNode': {
    process: 'Smooth floor: smoothly transitions toward the floor within `Range` using `Calculator.smoothMax(range, input, Limit)`.',
    fields: [
      { label: 'Limit', description: 'Target floor limit.' },
      { label: 'Range', description: 'Smoothing range near the floor limit.' },
    ],
  },
  'ClampDensityNode': {
    process: "Clamps to the interval `[WallA, WallB]` (order doesn't matter), using `Calculator.clamp(WallA, input, WallB)`.",
    fields: [
      { label: 'WallA', description: 'One wall of the clamp interval (acts as floor or ceiling depending on order).' },
      { label: 'WallB', description: 'Other wall of the clamp interval (acts as floor or ceiling depending on order).' },
    ],
  },
  'SmoothClampDensityNode': {
    process: 'Smooth clamp: applies smooth versions of both walls within `Range` (blends near boundaries instead of hard clamping).',
    fields: [
      { label: 'WallA', description: 'One wall of the clamp interval.' },
      { label: 'WallB', description: 'Other wall of the clamp interval.' },
      { label: 'Range', description: 'Smoothing range around both clamp boundaries.' },
    ],
  },

  // Sign-preserving math
  'AbsDensityNode': {
    process: 'Outputs `abs(input)` (absolute value).',
    fields: [],
  },
  'InverterDensityNode': {
    process: 'Outputs the negated input density: `-input`.',
    fields: [],
  },
  'SqrtDensityNode': {
    process: 'Sign-preserving square root: if `input >= 0` returns `sqrt(input)`, otherwise returns `-sqrt(-input)`.',
    fields: [],
  },
  'PowDensityNode': {
    process: 'Sign-preserving power: computes `pow(|input|, Exponent)` and restores sign (negative inputs become negative outputs).',
    fields: [{ label: 'Exponent', description: 'Exponent applied to the input magnitude.' }],
  },
  'NormalizerDensityNode': {
    process: 'Linearly normalizes the input density from `[FromMin, FromMax]` to `[ToMin, ToMax]` using `Normalizer.normalize(...)`.',
    fields: [
      { label: 'FromMin', description: 'Lower bound of the input range.' },
      { label: 'FromMax', description: 'Upper bound of the input range.' },
      { label: 'ToMin', description: 'Lower bound of the output range.' },
      { label: 'ToMax', description: 'Upper bound of the output range.' },
    ],
  },

  // Coordinate transforms / warps
  'XValue.Density': { process: 'Returns the current sample X coordinate (`context.position.x`).', fields: [] },
  'YValue.Density': { process: 'Returns the current sample Y coordinate (`context.position.y`).', fields: [] },
  'ZValue.Density': { process: 'Returns the current sample Z coordinate (`context.position.z`).', fields: [] },
  'XOverride.Density': {
    process: 'Overrides the X coordinate for sampling: evaluates input density using the same Y/Z, but with X set to the configured value.',
    fields: [{ label: 'Value', description: 'Fixed X coordinate used for sampling.' }],
  },
  'ZOverride.Density': {
    process: 'Overrides the Z coordinate for sampling: evaluates input density using the same X/Y, but with Z set to the configured value.',
    fields: [{ label: 'Value', description: 'Fixed Z coordinate used for sampling.' }],
  },

  'Scale.Density': {
    process: 'Rescales sampling coordinates: evaluates the connected input density at `position / scale` (and returns 0 if any scale component is 0).',
    fields: [],
  },
  'Slider.Density': {
    process: 'Translates sampling coordinates: evaluates the connected input density at `position - slide`.',
    fields: [],
  },
  'Gradient.Density': {
    process: 'Computes a slope angle from the connected input density by sampling it at +/- `SlopeRange` along each axis and estimating the gradient direction; outputs the angle between the configured `Axis` and that gradient direction in degrees.',
    fields: [],
  },
  'GradientWarp.Density': {
    process: 'Gradient-warp: uses a second “warp” density input to estimate a gradient (via forward differences over `SampleRange`), then offsets the sample position by `WarpFactor * gradient` before evaluating the main input density.',
    fields: [],
  },
  'FastGradientWarp.Density': {
    process: 'Fast domain warp: warps the sampling position using a seeded fractal FastNoiseLite domain warp, then evaluates the input density at the warped coordinates.',
    fields: [
      { label: 'Seed', description: 'Seed for the noise field.' },
      { label: 'WarpScale', description: 'Domain warp frequency scale.' },
      { label: 'WarpFactor', description: 'How strongly positions are displaced.' },
    ],
  },
  'VectorWarp.Density': {
    process: 'Vector warp: if a warp input is connected, evaluates `warp = WarpFactor * warpInput`, then samples the main input at `position + normalize(WarpVector) * warp`. If no warp input is connected, it samples the input directly.',
    fields: [
      { label: 'WarpFactor', description: 'Multiplier applied to the warp input.' },
      { label: 'WarpVector', description: 'Direction of the warp displacement.' },
    ],
  },
  'Rotator.Density': {
    process: 'Rotates the sampling coordinates: rotates the point around an axis derived from `NewYAxis` by `Spin` degrees, then evaluates the connected input density at the rotated position.',
    fields: [],
  },

  // Position-warping densities (twist/pinch)
  'PositionsTwist.Density': {
    process: 'Twists sampling based on nearby Positions points: for points within `MaxDistance`, computes a twist angle from `TwistCurve(distance)` (optionally normalized), rotates each point around `TwistAxis`, blends multiple candidate warped positions by weights `(1 - distance/maxDistance)`, then evaluates the connected input density at the blended position.',
    fields: [
      { label: 'MaxDistance', description: 'Maximum distance to consider for twisting.' },
      { label: 'NormalizeDistance', description: 'If enabled, the curve is evaluated using normalized distance (distance / MaxDistance).' },
    ],
  },
  'PositionsPinch.Density': {
    process: 'Pinches sampling based on nearby Positions points: for points within `MaxDistance`, sets the radial displacement length from `PinchCurve(distance)` (optionally normalized) while keeping direction from the sample point. If multiple candidate points are found, blends them by weights `(1 - normalizedDistance)`, then evaluates the connected input density at the blended position. (When `HorizontalPinch` is enabled, the horizontal pinch variant is used instead.)',
    fields: [
      { label: 'MaxDistance', description: 'Maximum distance to consider for pinching.' },
      { label: 'NormalizeDistance', description: 'If enabled, the curve is evaluated using normalized distance (distance / MaxDistance).' },
    ],
  },

  // Custom (plugin) densities
  'SolidLine.Density': {
    process: 'Bridge graph: voxel Bresenham segments between any two positions whose Euclidean distance is ≤ MaxDistance (Width/Height thicken each spine). Runtime: gather from a ±MaxDistance axis box, keep points within Euclidean MaxDistance of the sample, then connect all qualifying pairs. Static List/Imported→List precomputes the same rule over the full list.',
    fields: [
      { label: 'Positions (output)', description: 'Connect the positions subchain (right port). List/Imported→List bakes a HashSet. Dynamic providers are queried in a local box each sample.' },
      { label: 'Width (X/Z)', description: 'Odd width centers expansion on the Bresenham spine.' },
      { label: 'Height (Y)', description: 'Vertical expansion around each spine voxel.' },
      { label: 'MaxDistance (query + max bridge span)', description: 'Single radius: axis-aligned query half-edge around each sample, and maximum world distance for drawing a bridge between two gathered points.' },
    ],
  },

  // Distance/shape densities
  'Cube.Density': {
    process: 'Cube distance field: computes `d = max(abs(x), abs(y), abs(z))` relative to the origin and outputs `Curve(d)`. Rotation/scaling is applied via surrounding assets.',
    fields: [],
  },
  'Ellipsoid.Density': {
    process: 'Ellipsoid distance field: computes Euclidean distance from origin (`DistanceDensity`), then scales and rotates the space before applying the connected curve to that distance.',
    fields: [],
  },
  'Cuboid.Density': {
    process: 'Cuboid distance field: computes cube-like distance `d = max(abs(x), abs(y), abs(z))` and applies the connected curve to it, after scaling and rotating the space.',
    fields: [],
  },
  'Shell.Density': {
    process: 'Shell density based on radial distance + angle: evaluates `distance = |position|`, computes `amplitude = DistanceCurve(distance)`, then multiplies by `AngleCurve(angle)` where `angle` is derived from the angle between the radial vector and `Axis` (with optional mirroring).',
    fields: [{ label: 'Mirror', description: 'If enabled, mirrors the angle so values beyond 90 degrees are flipped.' }],
  },
  'Axis.Density': {
    process: 'Axis distance field: computes distance from the sample point to the configured line axis (or to a line relative to `densityAnchor` when `IsAnchored` is enabled) and outputs `Curve(distance)`.',
    fields: [{ label: 'IsAnchored', description: 'If enabled, measures distances relative to the current density anchor.' }],
  },
  'Plane.Density': {
    process: 'Plane distance field: computes the perpendicular distance from the sample point to a plane through the origin along `PlaneNormal` (or relative to `densityAnchor` when `IsAnchored` is enabled), then outputs `Curve(distance)`.',
    fields: [{ label: 'IsAnchored', description: 'If enabled, uses the current density anchor to position the plane.' }],
  },
  'Cylinder.Density': {
    process: 'Cylinder density: computes `radialDistance = distance(x,z to 0)` and returns `AxialCurve(y) * RadialCurve(radialDistance)`. Rotation/scaling is applied via surrounding assets.',
    fields: [],
  },

  // Cell/terrain context inputs
  'CellWallDistance.Density': {
    process: 'Reads the current cell-wall distance from the evaluation context (`context.distanceFromCellWall`).',
    fields: [],
  },
  'Terrain.Density': {
    process: 'Reads terrain density from `context.terrainDensityProvider` at the current voxel position; returns 0 if no provider is available.',
    fields: [],
  },
  'DistanceToBiomeEdge.Density': {
    process: 'Reads distance-to-biome-edge from the evaluation context (`context.distanceToBiomeEdge`).',
    fields: [],
  },

  // Sampling & caching helpers
  'YSampled.Density': {
    process: 'Y-sampling + linear interpolation: samples the input density at two Y positions separated by `SampleDistance`, then linearly interpolates to approximate density at the current Y while keeping X/Z constant.',
    fields: [
      { label: 'SampleDistance', description: 'Distance between sample layers along Y.' },
      { label: 'SampleOffset', description: 'Offset used to align the sampling grid.' },
    ],
  },
  'Cache.Density': {
    process: 'In-memory caching for exact positions: memoizes the connected input density value for up to `Capacity` recently evaluated distinct positions (exact x/y/z match).',
    fields: [{ label: 'Capacity', description: 'Number of distinct positions to keep in the cache.' }],
  },
  'Cache2D.Density': {
    process: 'Deprecated 2D cache: forces sampling at a fixed Y value, then caches the connected input density for a few most-recent positions (so repeated x/z queries reuse the same cached value).',
    fields: [{ label: 'Y', description: 'Fixed Y coordinate used for sampling.' }],
  },

  // Basic coordinates / anchors
  'Anchor.Density': {
    process: 'Samples the input relative to the current `densityAnchor` (if provided by the evaluation context). If no anchor exists, it passes through the input unmodified.',
    fields: [],
  },
  'BaseHeight.Density': {
    process: 'Outputs a base-height value derived from a named constant: if `Distance` is enabled, returns `position.y - baseHeight`; otherwise returns `baseHeight`.',
    fields: [
      { label: 'BaseHeightName', description: 'Name of the referenced base-height constant.' },
      { label: 'Distance', description: 'If enabled, outputs vertical distance from base height.' },
    ],
  },
  'Distance.Density': {
    process: 'Distance falloff: computes Euclidean distance from the origin and applies the configured falloff curve to it.',
    fields: [],
  },

  // Position provider sampling
  'Positions3DDensityNode': {
    process: 'Samples from connected Positions provider(s) inside `MaxDistance` and converts the nearest-point distance to density using the connected Curve (`CurveReturnType`).',
    fields: [],
  },
  'PositionsCellNoiseDensityNode': {
    process: 'Samples distances to the closest points returned by the connected Positions provider (inside `MaxDistance`) and converts those distances into density using the configured return type (curve/density/cell-value).',
    fields: [],
  },

  // Switching & blending
  'Switch.Density': {
    process: 'State-based switch: selects exactly one connected case whose `CaseState` hash matches `context.switchState`, and evaluates that case density. If no case matches, returns 0.',
    fields: [],
  },
  'Case.Switch.Density': {
    process: 'A single case entry used by `Switch Density`. `CaseState = "Default"` maps to switch state hash 0; other strings are hashed and compared to `context.switchState` by the parent.',
    fields: [{ label: 'CaseState', description: 'State label used by the parent Switch to decide which case is active.' }],
  },
  'SwitchState.Density': {
    process: 'Forces a switch state while sampling: sets `context.switchState` to the configured value and evaluates the connected input density using that modified context.',
    fields: [{ label: 'SwitchState', description: 'Switch-state value used during evaluation.' }],
  },

  'Mix.Density': {
    process: 'Blends between two densities using an influence density: returns A when influence <= 0, returns B when influence >= 1, otherwise does linear interpolation between the two by `influence`.',
    fields: [],
  },
  'MultiMix.Density': {
    process: 'Multi-way blend using key segments: evaluates the influence density (a “gauge”) and selects the segment between the two surrounding keys; it then linearly interpolates between the two key densities for that gauge.',
    fields: [],
  },

  // Export/import nodes (graph wiring)
  'Exported.Density': {
    process: 'Exports its child density node under `ExportAs` so other parts of the graph can import it by name. If `SingleInstance` is enabled, the exported value may be shared per worker.',
    fields: [
      { label: 'ExportAs', description: 'Name under which this density node is exported.' },
      { label: 'SingleInstance', description: 'If enabled, reuse the same built instance per worker.' },
    ],
  },
  'ImportedDensityNode': {
    process: 'Imports an exported density by name and evaluates it. If the name does not exist, it evaluates to 0.',
    fields: [{ label: 'Name', description: 'Export name to import.' }],
  },

  // Angle
  'Angle.Density': {
    process: 'Computes the angle (in degrees) between a configured vector and the vector provided by the connected `VectorProvider`. If `IsAxis` is enabled, the angle is folded so it stays <= 90 degrees.',
    fields: [{ label: 'IsAxis', description: 'If enabled, folds obtuse angles to their acute complement.' }],
  },

  // Curves
  'DistanceExponentialCurve': {
    process: 'Distance exponential curve: for inputs < 0 returns 1, for inputs > Range returns 0, and otherwise decays between them using `pow(in / Range + 1, Exponent)` after normalization.',
    fields: [
      { label: 'Exponent', description: 'Controls how quickly the curve decays.' },
      { label: 'Range', description: 'Distance where the curve reaches 0.' },
    ],
  },
  'DistanceSCurve': {
    process: 'S-shaped distance curve: builds two power curves (ExponentA and ExponentB) over the [0, Range] interval and interpolates between them around the `Transition` fraction; `TransitionSmooth` controls smoothness of the interpolation.',
    fields: [
      { label: 'ExponentA', description: 'Exponent for the first power segment.' },
      { label: 'ExponentB', description: 'Exponent for the second power segment.' },
      { label: 'Range', description: 'Max distance considered by the curve.' },
      { label: 'Transition', description: 'Fraction of the range where the curve transitions between segments.' },
      { label: 'TransitionSmooth', description: 'Controls smoothness of the S-transition.' },
    ],
  },
  'CurvePoint': {
    process: 'A control point used by `ManualCurve`: defines the (In, Out) pair that becomes a node on the piecewise-linear curve.',
    fields: [
      { label: 'In', description: 'Input value on the curve axis.' },
      { label: 'Out', description: 'Output value produced at this input.' },
    ],
  },
  'ManualCurve': {
    process: 'Piecewise linear curve defined by connected `CurvePoint` nodes. Outside the provided In-range, clamps to the first/last Out value; between points it linearly interpolates.',
    fields: [],
  },
  'ImportedCurve': {
    process: 'Curve import by name: evaluates another exported curve with the given `Name`. If the export does not exist, returns 0.',
    fields: [{ label: 'Name', description: 'Export name of the curve to import.' }],
  },
  'Constant.Curve': {
    process: 'Always returns the configured constant output value.',
    fields: [{ label: 'Value', description: 'Constant curve output.' }],
  },
  'Sum.Curve': {
    process: 'Sum of all connected input curves: evaluates each curve at the same input and returns their sum.',
    fields: [],
  },
  'Min.Curve': {
    process: 'Minimum of all connected input curves: evaluates each curve at the same input and returns the smallest value.',
    fields: [],
  },
  'Max.Curve': {
    process: 'Maximum of all connected input curves: evaluates each curve at the same input and returns the largest value.',
    fields: [],
  },
  'Multiplier.Curve': {
    process: 'Product of all connected input curves: evaluates each curve at the same input and returns their product.',
    fields: [],
  },
  'Not.Curve': {
    process: 'Not / inverse-around-1: returns `1 - inputCurve(x)`.',
    fields: [],
  },
  'Inverter.Curve': {
    process: 'Negates the input curve result: returns `-inputCurve(x)`.',
    fields: [],
  },
  'Clamp.Curve': {
    process: "Clamps the connected curve output between `WallA` and `WallB` (order doesn't matter).",
    fields: [
      { label: 'WallA', description: 'One clamp boundary.' },
      { label: 'WallB', description: 'Other clamp boundary.' },
    ],
  },
  'Ceiling.Curve': {
    process: 'Caps the connected curve output from above: returns `min(Ceiling, curve(x))`.',
    fields: [{ label: 'Ceiling', description: 'Upper bound.' }],
  },
  'Floor.Curve': {
    process: 'Caps the connected curve output from below: returns `max(Floor, curve(x))`.',
    fields: [{ label: 'Floor', description: 'Lower bound.' }],
  },
  'SmoothMin.Curve': {
    process: 'Smooth minimum between CurveA and CurveB using `Calculator.smoothMin(range, A, B)`.',
    fields: [{ label: 'Range', description: 'Smoothing range for the transition between A and B.' }],
  },
  'SmoothMax.Curve': {
    process: 'Smooth maximum between CurveA and CurveB using `Calculator.smoothMax(range, A, B)`.',
    fields: [{ label: 'Range', description: 'Smoothing range for the transition between A and B.' }],
  },
  'SmoothCeiling.Curve': {
    process: 'Smooth ceiling (soft cap): applies `Calculator.smoothMin(range, Ceiling, curve(x))` to blend near the ceiling.',
    fields: [
      { label: 'Ceiling', description: 'Target ceiling limit.' },
      { label: 'Range', description: 'Smoothing range near the ceiling.' },
    ],
  },
  'SmoothFloor.Curve': {
    process: 'Smooth floor (soft bottom cap): applies `Calculator.smoothMax(range, Floor, curve(x))` to blend near the floor.',
    fields: [
      { label: 'Floor', description: 'Target floor limit.' },
      { label: 'Range', description: 'Smoothing range near the floor.' },
    ],
  },
  'SmoothClamp.Curve': {
    process: 'Smooth clamp to `[WallA, WallB]`: hard-clamps when `Range = 0`, otherwise blends both boundaries using smoothed min/max behavior and averages the result.',
    fields: [
      { label: 'WallA', description: 'Lower/upper boundary (depending on order).' },
      { label: 'WallB', description: 'Other boundary (depending on order).' },
      { label: 'Range', description: 'Smoothing range; 0 becomes a hard clamp.' },
    ],
  },

  // PositionsCellNoise return-type delimiter bucket
  'Delimiter.DensityPCNReturnType': {
    process: 'Defines a delimiter bucket `[From, To)` used by `PositionsCellNoise` return types: when the sampled “choice value” falls within `[From, To)`, the connected Density is evaluated (with the context anchor set to the closest point).',
    fields: [
      { label: 'From', description: 'Lower bound (inclusive) of the bucket.' },
      { label: 'To', description: 'Upper bound (exclusive) of the bucket.' },
    ],
  },

  // -------------------------
  // Assignments node metadata
  // -------------------------
  'Constant.Assignments': {
    process: 'Always returns the configured constant `Prop` distribution; the sampling position does not affect the result.',
    fields: [
      { label: 'ExportAs', description: 'If set, registers this Assignments node under that name so `Imported.Assignments` can reference it.' },
    ],
  },

  'FieldFunction.Assignments': {
    process: 'Evaluates the connected Density “FieldFunction” at the current position to produce a field value, then selects the first delimiter where `Min <= fieldValue < Max` and delegates to that delimiter’s connected Assignments. If no delimiter matches (or no delimiters are configured), returns noProp.',
    fields: [
      { label: 'ExportAs', description: 'If set, registers this Assignments node under that name so `Imported.Assignments` can reference it.' },
    ],
  },

  'Delimiter.FieldFunction.Assignments': {
    process: 'Delimiter bucket used by `FieldFunction.Assignments`: the delimiter matches when `fieldValue >= Min` and `fieldValue < Max` (inclusive lower, exclusive upper).',
    fields: [
      { label: 'Min', description: 'Lower bound (inclusive) for the field value.' },
      { label: 'Max', description: 'Upper bound (exclusive) for the field value.' },
    ],
  },

  'Sandwich.Assignments': {
    process: 'Selects a delimiter based on the current Y coordinate: returns the first delimiter where `MinY <= y < MaxY` and delegates to that delimiter’s connected Assignments. If no delimiter matches (or no delimiters are configured), returns noProp.',
    fields: [
      { label: 'ExportAs', description: 'If set, registers this Assignments node under that name so `Imported.Assignments` can reference it.' },
    ],
  },

  'Delimiter.Sandwich.Assignments': {
    process: 'Delimiter bucket used by `Sandwich.Assignments`: the delimiter matches when `MinY <= y < MaxY` (inclusive lower, exclusive upper).',
    fields: [
      { label: 'MinY', description: 'Lower bound (inclusive) for Y.' },
      { label: 'MaxY', description: 'Upper bound (exclusive) for Y.' },
    ],
  },

  'Weighted.Assignments': {
    process: 'Picks one of the connected child Assignments using their configured weights, using a deterministic random generator derived from `position` and `Seed`. With probability `SkipChance` it returns noProp; otherwise it selects a child by weight and delegates to it.',
    fields: [
      { label: 'SkipChance', description: 'Probability to return noProp instead of selecting a weighted child.' },
      { label: 'Seed', description: 'Controls deterministic random choice per position.' },
      { label: 'ExportAs', description: 'If set, registers this Assignments node under that name so `Imported.Assignments` can reference it.' },
    ],
  },

  'Weight.Weighted.Assignments': {
    process: 'Specifies the weight value for a single child inside a `Weighted.Assignments` picker (used to scale the probabilities of its sibling children).',
    fields: [
      { label: 'Weight', description: 'Relative selection weight for the connected Assignments.' },
    ],
  },

  'Imported.Assignments': {
    process: 'Looks up an exported Assignments node by `Name` and reuses its built behavior. If no exported node with that name exists, it behaves like a missing distribution (returns noProp).',
    fields: [
      { label: 'Name', description: 'Export name of the Assignments node to import.' },
    ],
  },
}

const nodeInfo = computed(() => {
  const def = props.data.definition
  return NODE_PROCESS_INFO[def.Id] || NODE_PROCESS_INFO[def.Title] || null
})

const nodeInfoOpen = ref(false)

const isDensityOrCurveNode = computed(() => {
  const def = props.data.definition
  const pinTypes = [
    ...def.Inputs.map((i) => i.Type),
    ...def.Outputs.map((o) => o.Type),
  ]
  const hasDensity =
    pinTypes.includes('DensityConnection') ||
    pinTypes.some((t) => t === 'DensityConnection')
  const hasCurve =
    pinTypes.includes('CurveConnection') ||
    pinTypes.includes('CurvePointConnection')
  const hasAssignments = pinTypes.includes('AssignmentsConnection') || pinTypes.some((t) => t === 'AssignmentsConnection')
  return hasDensity || hasCurve || hasAssignments
})

/**
 * Show live min/max + 3D entry for nodes that participate in the density graph.
 * Many Hytale assets expose the outward density wire as an Input pin (`DensityOutput`), not an Output
 * (e.g. SimplexNoise2DDensityNode, PositionsCellNoiseDensityNode); only checking Outputs hid the debug UI.
 */
const hasDensityOutput = computed(() => {
  const def = props.data.definition
  return [...def.Inputs, ...def.Outputs].some((p) => p.Type === 'DensityConnection')
})

function toggleNodeInfo() {
  if (!isDensityOrCurveNode.value) return
  nodeInfoOpen.value = !nodeInfoOpen.value
}

function closeNodeInfo() {
  nodeInfoOpen.value = false
}

function onGlobalClick(e: MouseEvent) {
  const target = e.target as HTMLElement | null
  if (!target) return
  if (nodeInfoOpen.value) {
    if (!target.closest('.node-info-btn') && !target.closest('.node-info-popover')) {
      closeNodeInfo()
    }
  }
}

function onGlobalKeyDown(e: KeyboardEvent) {
  if (e.key !== 'Escape') return
  if (nodeInfoOpen.value) {
    closeNodeInfo()
  }
}

onMounted(() => {
  window.addEventListener('click', onGlobalClick)
  window.addEventListener('keydown', onGlobalKeyDown)
  titleResizeObserver = new ResizeObserver(() => scheduleTitleFit())
  void nextTick(() => {
    const hdr = nodeHeaderRef.value
    if (hdr) titleResizeObserver?.observe(hdr)
    scheduleTitleFit()
  })
})

onUnmounted(() => {
  window.removeEventListener('click', onGlobalClick)
  window.removeEventListener('keydown', onGlobalKeyDown)
  if (titleFitRaf) cancelAnimationFrame(titleFitRaf)
  titleResizeObserver?.disconnect()
  titleResizeObserver = null
})

// Curve distribution: only for ManualCurve, from its connected CurvePoint nodes.
interface CurvePointData { in: number; out: number }
const curvePoints = computed((): CurvePointData[] => {
  if (props.data.definition.Id !== 'ManualCurve') return []
  const edges = getEdges.value
  const pointsHandle = 'Points'
  const pointEdges = edges.filter(
    (e) => e.source === props.id && e.sourceHandle === pointsHandle,
  )
  const points: CurvePointData[] = pointEdges.map((e) => {
    const n = findNode(e.target) as { data?: { values?: Record<string, unknown> } } | undefined
    const v = n?.data?.values
    const inVal = typeof v?.In === 'number' ? v.In : Number(v?.In)
    const outVal = typeof v?.Out === 'number' ? v.Out : Number(v?.Out)
    return { in: Number.isFinite(inVal) ? inVal : 0, out: Number.isFinite(outVal) ? outVal : 0 }
  })
  points.sort((a, b) => a.in - b.in)
  return points
})

const showCurveChart = computed(() => {
  return props.data.definition.Id === 'ManualCurve' && curvePoints.value.length > 0
})

const curveChartSvgRef = ref<SVGSVGElement | null>(null)
const curveHoverX = ref<number | null>(null)
const curveHoverViewX = ref<number | null>(null)

function onCurveChartMouseMove(e: MouseEvent) {
  const svg = curveChartSvgRef.value
  const chart = curveChart.value
  if (!svg || chart.points.length === 0) return
  const rect = svg.getBoundingClientRect()
  const scale = Math.min(rect.width / CHART_W, rect.height / CHART_H)
  const vbWidth = CHART_W * scale
  const vbHeight = CHART_H * scale
  const offsetX = (rect.width - vbWidth) / 2
  const offsetY = (rect.height - vbHeight) / 2
  const viewX = (e.clientX - rect.left - offsetX) / scale
  if (viewX >= PAD.left && viewX <= PAD.left + plotW) {
    const t = (viewX - PAD.left) / plotW
    const dataX = chart.xMin + t * (chart.xMax - chart.xMin)
    curveHoverX.value = dataX
    curveHoverViewX.value = viewX
  } else {
    curveHoverX.value = null
    curveHoverViewX.value = null
  }
}

function onCurveChartMouseLeave() {
  curveHoverX.value = null
  curveHoverViewX.value = null
}

function formatCurveHoverX(): string {
  const x = curveHoverX.value
  if (x == null) return ''
  return typeof x === 'number' && Number.isInteger(x) ? String(x) : x.toFixed(2)
}

// Chart dimensions and scaling for the curve preview
const CHART_W = 320
const CHART_H = 120
const PAD = { left: 28, right: 10, top: 8, bottom: 24 }
const plotW = CHART_W - PAD.left - PAD.right
const plotH = CHART_H - PAD.top - PAD.bottom

const curveChart = computed(() => {
  const pts = curvePoints.value
  if (pts.length === 0) return { path: '', fillPath: '', points: [], xMin: 0, xMax: 1, yMin: 0, yMax: 1, zeroY: null, showZeroLabel: false, zeroCrossings: [] }
  const xs = pts.map(p => p.in)
  const ys = pts.map(p => p.out)
  let xMin = Math.min(...xs)
  let xMax = Math.max(...xs)
  let yMin = Math.min(...ys)
  let yMax = Math.max(...ys)
  const padding = 0.1
  const xRange = xMax - xMin || 1
  const yRange = yMax - yMin || 1
  xMin -= xRange * padding
  xMax += xRange * padding
  yMin -= yRange * padding
  yMax += yRange * padding
  const scaleX = (x: number) => PAD.left + ((x - xMin) / (xMax - xMin)) * plotW
  const scaleY = (y: number) => PAD.top + plotH - ((y - yMin) / (yMax - yMin)) * plotH
  const path = pts.map((p, i) => `${i === 0 ? 'M' : 'L'} ${scaleX(p.in)} ${scaleY(p.out)}`).join(' ')
  const points = pts.map(p => ({ x: scaleX(p.in), y: scaleY(p.out) }))
  const bottom = PAD.top + plotH
  const lastPt = points.length > 1 ? points[points.length - 1] : null
  const fillPath =
    points.length > 1 && lastPt
      ? `M ${PAD.left} ${bottom} L ${points.map((p) => `${p.x} ${p.y}`).join(' L ')} L ${lastPt.x} ${bottom} Z`
      : ''
  const zeroInRange = 0 >= yMin && 0 <= yMax
  const zeroY = zeroInRange ? scaleY(0) : null
  const showZeroLabel = zeroInRange && 0 > yMin && 0 < yMax
  // Where the curve crosses Out = 0 (linear segments between points)
  const zeroCrossings: { inValue: number; x: number }[] = []
  for (let i = 0; i < pts.length - 1; i++) {
    const p0 = pts[i]!
    const p1 = pts[i + 1]!
    const o0 = p0.out
    const o1 = p1.out
    if (o0 === o1) continue
    const t = -o0 / (o1 - o0)
    if (t >= 0 && t <= 1) {
      const inVal = p0.in + t * (p1.in - p0.in)
      zeroCrossings.push({ inValue: inVal, x: scaleX(inVal) })
    }
  }
  return { path, fillPath, points, xMin, xMax, yMin, yMax, zeroY, showZeroLabel, zeroCrossings }
})

function openDensityViewer() {
  const ids = inputNodeIds.value
  const labels = inputNodeLabels.value
  const query: Record<string, string> = {}
  if (ids.length > 0) {
    query.inputIds = ids.join(',')
    if (labels.some(Boolean)) query.inputLabels = labels.join('|')
  }
  router.push({ name: 'density-viewer', params: { nodeId: props.id }, query: Object.keys(query).length ? query : undefined })
}

/** Same visibility as `DensityTracePanel` `showPanel` — only sync when trace UI is open. */
function isDensityTracePanelActive(): boolean {
  return (
    store.densityTracePickedWorld != null ||
    store.densityTraceLoading ||
    store.densityTraceError != null ||
    store.densityTraceData != null
  )
}

/** List rows sync via id; graph hover uses source `'graph'` so the node is not tinted. */
function onDensityTraceGraphNodeEnter() {
  if (!isDensityTracePanelActive()) return
  store.setDensityTraceHighlightNodeId(props.id, 'graph')
}
function onDensityTraceGraphNodeLeave() {
  store.setDensityTraceHighlightNodeId(null)
}
</script>

<template>
  <div
    class="node-container"
    :class="{
      selected: props.selected,
      'trace-highlight': isTraceNodeTintFromList,
    }"
    :style="{ borderColor: props.selected ? '#1dd1a1' : getCategoryColor(props.data.definition.Category, props.data.definition.Id) }"
    @mouseenter="onDensityTraceGraphNodeEnter"
    @mouseleave="onDensityTraceGraphNodeLeave"
  >
    <div
      ref="nodeHeaderRef"
      class="node-header"
      :style="{ backgroundColor: getCategoryColor(props.data.definition.Category, props.data.definition.Id) }"
    >
      <input
        v-if="titleEditing"
        ref="titleInputRef"
        v-model="titleDraft"
        class="node-title-input nodrag nopan"
        :class="{ 'node-title-input--with-info': isDensityOrCurveNode }"
        type="text"
        @blur="commitTitleEdit"
        @keydown.enter.prevent="commitTitleEdit"
        @keydown.escape.prevent="cancelTitleEdit"
        @mousedown.stop
        @pointerdown.stop
        @click.stop
      />
      <div
        v-else
        class="node-title-row"
        :class="{ 'node-title-row--with-info': isDensityOrCurveNode }"
        title="Double-click to rename"
        @dblclick.stop="beginTitleEdit"
      >
        <div
          class="node-title-stack"
          :class="{ 'node-title-stack--two-lines': hasCustomDisplayTitle }"
        >
          <template v-if="hasCustomDisplayTitle">
            <span
              ref="customTitleRef"
              class="node-title-custom"
              :style="customFitFontPx != null ? { fontSize: `${customFitFontPx}px` } : undefined"
            >{{ (props.data.displayTitle ?? '').trim() }}</span>
            <span
              ref="typeSecondaryRef"
              class="node-title-type node-title-type--secondary"
              :style="secondaryFitFontPx != null ? { fontSize: `${secondaryFitFontPx}px` } : undefined"
            >{{ typeTitle }}</span>
          </template>
          <span
            v-else
            ref="soloTitleRef"
            class="node-title-type node-title-type--solo"
            :style="soloFitFontPx != null ? { fontSize: `${soloFitFontPx}px` } : undefined"
          >{{ typeTitle }}</span>
        </div>
      </div>
      <div class="node-header-actions nodrag nopan" @mousedown.stop @pointerdown.stop @click.stop>
        <button
          v-if="isDensityOrCurveNode"
          type="button"
          class="node-info-btn"
          aria-label="Node process description"
          title="What does this node do?"
          @click.stop="toggleNodeInfo"
        >
          i
        </button>
      </div>
      <div
        v-if="nodeInfoOpen && isDensityOrCurveNode"
        class="node-info-popover"
        role="dialog"
        aria-label="Process description"
        @click.stop
      >
        <div class="node-info-process">
          {{ nodeInfo ? nodeInfo.process : 'Process description not implemented yet.' }}
        </div>
        <div v-if="nodeInfo" class="node-info-divider" />
        <div v-if="nodeInfo && nodeInfo.fields.length">
          <div class="node-info-subtitle">Input fields</div>
          <div
            v-for="field in nodeInfo.fields"
            :key="field.label"
            class="node-info-line"
          >
            <span class="node-info-label">{{ field.label }}</span>
            <span class="node-info-desc">{{ field.description }}</span>
          </div>
        </div>
      </div>
    </div>

    <div class="node-body">
      <!-- Targets -->
      <div v-for="(input, index) in props.data.definition.Inputs" :key="'in-' + index" class="handle-wrapper input">
        <Handle
          type="target"
          :position="Position.Left"
          :id="input.Id"
          class="custom-handle target-handle"
          :style="{ backgroundColor: getHandleColor(input.Type, props.data.definition.Category) }"
        />
        <span class="handle-label">{{ input.Label || input.Id }}</span>
      </div>

      <!-- Content -->
      <div
        class="node-content nodrag nopan"
        @mousedown="stopInteraction"
        @pointerdown="stopInteraction"
        @click="stopInteraction"
      >
        <div v-for="item in props.data.definition.Content" :key="item.Id" class="content-item">
          <label v-if="item.Options?.Label" class="input-label">{{ item.Options.Label }}</label>

          <template v-if="item.Type === 'SmallString' || item.Type === 'String'">
            <input
              type="text"
              class="large-input nodrag nopan"
              :value="displayFieldDraftOrCommitted(item.Id, props.data.values[item.Id])"
              @focus="(e) => onStringFieldFocus(item.Id, e)"
              @input="(e) => onStringFieldInput(item.Id, e)"
              @blur="commitStringField(item.Id)"
              @keydown.enter.prevent="commitStringField(item.Id)"
              @keydown.escape.prevent="cancelFieldDraft(item.Id)"
              :style="{ width: item.Options?.Width ? (item.Options.Width * 1.4) + 'px' : '100%' }"
            />
          </template>

          <template v-else-if="item.Type === 'Checkbox'">
            <div class="checkbox-line">
              <input
                type="checkbox"
                class="ultra-large-checkbox nodrag nopan"
                :checked="!!props.data.values[item.Id]"
                @change="(e) => updateValue(item.Id, (e.target as HTMLInputElement).checked)"
              />
            </div>
          </template>

          <template v-else-if="item.Type === 'Float' || item.Type === 'Int'">
            <input
              type="number"
              step="any"
              class="large-input number-field nodrag nopan"
              :value="displayFieldDraftOrCommitted(item.Id, props.data.values[item.Id])"
              @focus="(e) => onNumericFieldFocus(item.Id, e)"
              @input="(e) => onNumericFieldDraftInput(item.Id, e)"
              @blur="commitNumericField(item.Id, item.Type === 'Float' ? 'float' : 'int')"
              @keydown.enter.prevent="commitNumericField(item.Id, item.Type === 'Float' ? 'float' : 'int')"
              @keydown.escape.prevent="cancelFieldDraft(item.Id)"
              :style="{ width: item.Options?.Width ? (item.Options.Width * 1.4) + 'px' : '100%' }"
            />
          </template>

          <template v-else-if="item.Type === 'IntSlider'">
            <div class="slider-group nodrag nopan">
              <input
                type="range"
                class="chunky-slider nodrag nopan"
                :min="item.Options?.Min ?? 0"
                :max="item.Options?.Max ?? 100"
                :step="item.Options?.TickFrequency ?? 1"
                :value="intSliderRangeDisplay(item.Id, props.data.values[item.Id])"
                @input="(e) => onIntSliderRangeInput(item.Id, e)"
                @change="(e) => onIntSliderRangeChange(item.Id, e)"
              />
              <input
                type="number"
                class="slider-number-input nodrag nopan"
                :min="item.Options?.Min ?? 0"
                :max="item.Options?.Max ?? 100"
                :step="item.Options?.TickFrequency ?? 1"
                :value="displayFieldDraftOrCommitted(item.Id, props.data.values[item.Id])"
                @focus="(e) => onNumericFieldFocus(item.Id, e)"
                @input="(e) => onNumericFieldDraftInput(item.Id, e)"
                @blur="commitNumericField(item.Id, 'int')"
                @keydown.enter.prevent="commitNumericField(item.Id, 'int')"
                @keydown.escape.prevent="cancelFieldDraft(item.Id)"
              />
            </div>
          </template>

          <template v-else>
            <div class="unknown-type-badge">{{ item.Type }}</div>
          </template>
        </div>

        <!-- Curve distribution graph (Manual Curve only) -->
        <div
          v-if="showCurveChart"
          class="curve-chart-wrap nodrag nopan"
          @mousemove="onCurveChartMouseMove"
          @mouseleave="onCurveChartMouseLeave"
        >
          <svg
            ref="curveChartSvgRef"
            class="curve-chart-svg"
            :viewBox="`0 0 ${CHART_W} ${CHART_H}`"
            preserveAspectRatio="xMidYMid meet"
            aria-label="Curve distribution"
          >
            <defs>
              <linearGradient :id="'curve-fill-' + props.id" x1="0" y1="1" x2="0" y2="0">
                <stop offset="0%" stop-color="rgba(157, 234, 214, 0)" />
                <stop offset="100%" stop-color="rgba(157, 234, 214, 0.2)" />
              </linearGradient>
            </defs>
            <!-- Grid -->
            <g class="curve-grid">
              <line
                v-for="i in 4"
                :key="'v' + i"
                :x1="PAD.left + (i / 4) * plotW"
                :y1="PAD.top"
                :x2="PAD.left + (i / 4) * plotW"
                :y2="PAD.top + plotH"
              />
              <line
                v-for="i in 4"
                :key="'h' + i"
                :x1="PAD.left"
                :y1="PAD.top + (i / 4) * plotH"
                :x2="PAD.left + plotW"
                :y2="PAD.top + (i / 4) * plotH"
              />
            </g>
            <!-- Filled path (area under curve) -->
            <path
              v-if="curveChart.fillPath"
              :d="curveChart.fillPath"
              :fill="'url(#curve-fill-' + props.id + ')'"
            />
            <!-- Curve line -->
            <path
              v-if="curveChart.path"
              :d="curveChart.path"
              class="curve-line"
              fill="none"
              stroke-width="2"
            />
            <!-- Control points -->
            <circle
              v-for="(pt, idx) in curveChart.points"
              :key="idx"
              :cx="pt.x"
              :cy="pt.y"
              r="4"
              class="curve-dot"
            />
            <!-- Zero reference line (Out = 0) -->
            <line
              v-if="curveChart.zeroY != null"
              :x1="PAD.left"
              :y1="curveChart.zeroY"
              :x2="PAD.left + plotW"
              :y2="curveChart.zeroY"
              class="curve-zero-line"
            />
            <!-- Vertical reference lines where curve crosses 0 -->
            <template v-for="(crossing, idx) in curveChart.zeroCrossings" :key="'zc-' + idx">
              <line
                :x1="crossing.x"
                :y1="PAD.top"
                :x2="crossing.x"
                :y2="PAD.top + plotH"
                class="curve-zero-crossing-line"
              />
              <text
                :x="crossing.x"
                :y="PAD.top + plotH + 14"
                class="curve-zero-crossing-text"
                text-anchor="middle"
              >{{ Number.isInteger(crossing.inValue) ? crossing.inValue : crossing.inValue.toFixed(2) }}</text>
            </template>
            <!-- Hover vertical line -->
            <line
              v-if="curveHoverViewX != null"
              :x1="curveHoverViewX"
              :y1="PAD.top"
              :x2="curveHoverViewX"
              :y2="PAD.top + plotH"
              class="curve-hover-line"
            />
            <!-- Hover x value on the line -->
            <text
              v-if="curveHoverViewX != null && curveHoverX != null"
              :x="curveHoverViewX"
              :y="PAD.top + plotH + 14"
              class="curve-hover-x-text"
              text-anchor="middle"
            >{{ formatCurveHoverX() }}</text>
            <!-- Axis tick values -->
            <text :x="PAD.left" :y="CHART_H - 6" class="curve-axis-tick" text-anchor="middle">{{ curveChart.xMin.toFixed(0) }}</text>
            <text :x="PAD.left + plotW" :y="CHART_H - 6" class="curve-axis-tick" text-anchor="middle">{{ curveChart.xMax.toFixed(0) }}</text>
            <text :x="PAD.left - 4" :y="PAD.top + plotH + 4" class="curve-axis-tick" text-anchor="end">{{ curveChart.yMin.toFixed(0) }}</text>
            <text v-if="curveChart.showZeroLabel && curveChart.zeroY != null" :x="PAD.left - 4" :y="curveChart.zeroY + 4" class="curve-axis-tick curve-zero-tick" text-anchor="end">0</text>
            <text :x="PAD.left - 4" :y="PAD.top + 4" class="curve-axis-tick" text-anchor="end">{{ curveChart.yMax.toFixed(0) }}</text>
          </svg>
        </div>
      </div>

      <!-- Sources -->
      <div v-for="(output, index) in props.data.definition.Outputs" :key="'out-' + index" class="handle-wrapper output">
        <span class="handle-label">{{ output.Label || output.Id }}</span>
        <Handle
          type="source"
          :position="Position.Right"
          :id="output.Id"
          class="custom-handle source-handle"
          :style="{ backgroundColor: getHandleColor(output.Type, props.data.definition.Category) }"
        />
      </div>

      <!-- Debug metrics + 3D preview (density-output nodes only; visible before server sends min/max) -->
      <div v-if="hasDensityOutput" class="debug-section nodrag nopan" @mousedown="stopInteraction">
        <div class="debug-header">LIVE DEBUG METRICS</div>
        <div class="debug-grid">
          <div class="debug-item">
            <label>MIN</label>
            <div class="read-only-field" :class="{ 'read-only-field--pending': !debugNodeMetrics }">
              {{ debugNodeMetrics ? debugNodeMetrics.min.toFixed(4) : '—' }}
            </div>
          </div>
          <div class="debug-item">
            <label>MAX</label>
            <div class="read-only-field" :class="{ 'read-only-field--pending': !debugNodeMetrics }">
              {{ debugNodeMetrics ? debugNodeMetrics.max.toFixed(4) : '—' }}
            </div>
          </div>
        </div>
        <button
          class="view-3d-btn"
          :class="{ 'view-3d-updated': store.densityDataLoadedNodeId === props.id }"
          type="button"
          @click.stop="openDensityViewer"
        >
          View 3D Terrain
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.node-container {
  background: #222f3e; /* Deep Charcoal Blue */
  border: 1px solid #3c4a5a;
  border-radius: 6px;
  width: 350px;
  min-width: 350px;
  max-width: 350px;
  color: white;
  font-family: 'Segoe UI', system-ui, -apple-system, BlinkMacSystemFont, sans-serif;
  overflow: visible;
  box-shadow: 0 6px 18px rgba(0,0,0,0.35);
  font-size: 14px;
  line-height: 1.4;
}

.node-container.selected {
  border-color: #1dd1a1;
  box-shadow: 0 0 20px rgba(29, 209, 161, 0.35);
}

/* Only when hover started from the trace list (`densityTraceHighlightSource === 'list'`). */
.node-container.trace-highlight {
  background: rgba(240, 180, 41, 0.48) !important;
  outline: 2px solid rgba(240, 180, 41, 0.95);
  outline-offset: 0;
}
.node-container.trace-highlight:not(.selected) {
  box-shadow: 0 0 14px rgba(240, 180, 41, 0.35);
}

.node-header {
  padding: 5px 10px;
  font-weight: 700;
  /* Base for `em` in row/stack heights; title lines use larger `clamp` + `cqi` below. */
  font-size: 1.06rem;
  text-align: center;
  color: #ffffff;
  letter-spacing: 0.03em;
  border-bottom: 1px solid rgba(0,0,0,0.25);
  position: relative;
  isolation: isolate;
  container-type: inline-size;
  container-name: nodehdr;
}

.node-header::after {
  content: '';
  position: absolute;
  inset: 0;
  pointer-events: none;
  z-index: 0;
  background: linear-gradient(
    to bottom,
    rgba(0, 0, 0, 0) 0%,
    rgba(0, 0, 0, 0.12) 55%,
    rgba(0, 0, 0, 0.32) 100%
  );
}

.node-title-row {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  box-sizing: border-box;
  min-height: calc(2.55em + 6px);
  position: relative;
  z-index: 1;
  padding-right: 10px;
}

.node-title-row--with-info {
  padding-right: 34px;
}

.node-title-stack {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  justify-content: center;
  width: 100%;
  min-width: 0;
  flex: 1 1 auto;
  min-height: calc(2.55em);
  text-align: center;
}

.node-title-stack--two-lines {
  justify-content: center;
  gap: 0.15em;
  min-height: calc(2.85em);
}

.node-title-custom {
  font-size: clamp(1.02rem, 0.76rem + 3.4cqi, 1.42rem);
  font-weight: 700;
  line-height: 1.12;
  color: #ffffff;
  text-shadow: 0 1px 2px rgba(0, 0, 0, 0.2);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  width: 100%;
}

.node-title-type {
  font-weight: 700;
  line-height: 1.12;
  color: #ffffff;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  width: 100%;
}

/* Single default title: larger type scales with node width; tight line-height uses vertical space without scale transforms. */
.node-title-type--solo {
  font-size: clamp(1.14rem, 0.78rem + 4.2cqi, 1.68rem);
  line-height: 1.08;
  letter-spacing: 0.02em;
}

.node-title-type--secondary {
  font-size: clamp(0.9rem, 0.72rem + 2.5cqi, 1.08rem);
  font-weight: 650;
  letter-spacing: 0.02em;
  line-height: 1.12;
  color: rgba(255, 255, 255, 0.76);
  text-shadow: 0 1px 1px rgba(0, 0, 0, 0.15);
}

.node-title-input {
  width: 100%;
  margin: 0;
  display: block;
  position: relative;
  z-index: 1;
  padding: 6px 10px;
  padding-right: 10px;
  min-height: calc(2.55em + 4px);
  box-sizing: border-box;
  border-radius: 4px;
  border: 1px solid rgba(255, 255, 255, 0.45);
  background: rgba(0, 0, 0, 0.25);
  color: #fff;
  font-family: inherit;
  font-weight: 700;
  font-size: clamp(1.08rem, 0.76rem + 3.8cqi, 1.62rem);
  line-height: 1.08;
  text-align: center;
}

.node-title-input--with-info {
  padding-right: 34px;
}

.node-header-actions {
  position: absolute;
  right: 6px;
  top: 50%;
  transform: translateY(-50%);
  display: inline-flex;
  align-items: center;
  gap: 4px;
  z-index: 6;
}

.node-info-btn {
  width: 18px;
  height: 18px;
  border-radius: 50%;
  border: 1px solid rgba(255, 255, 255, 0.55);
  background: rgba(0, 0, 0, 0.15);
  color: rgba(255, 255, 255, 0.95);
  font-size: 11px;
  line-height: 1;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 0;
  flex-shrink: 0;
}

.node-info-btn:hover {
  background: rgba(0, 0, 0, 0.28);
}

.node-info-popover {
  position: absolute;
  right: 6px;
  top: calc(100% + 6px);
  width: 320px;
  background: rgba(10, 16, 26, 0.98);
  color: #e5e5e5;
  border: 1px solid rgba(255, 255, 255, 0.14);
  border-radius: 6px;
  padding: 10px 10px;
  font-size: 12px;
  line-height: 1.35;
  box-shadow: 0 12px 26px rgba(0, 0, 0, 0.45);
  z-index: 50;
}

.node-info-process {
  font-weight: 600;
  color: rgba(229, 229, 229, 0.95);
  margin-bottom: 8px;
}

.node-info-divider {
  height: 1px;
  background: rgba(255, 255, 255, 0.12);
  margin: 8px 0;
}

.node-info-subtitle {
  font-size: 11px;
  font-weight: 800;
  letter-spacing: 0.06em;
  text-transform: uppercase;
  color: rgba(157, 234, 214, 0.9);
  margin-bottom: 6px;
}

.node-info-line {
  display: flex;
  gap: 10px;
  align-items: flex-start;
  margin: 6px 0;
}

.node-info-label {
  flex: 0 0 110px;
  width: 110px;
  color: rgba(255, 255, 255, 0.85);
  font-weight: 700;
  text-align: right;
}

.node-info-desc {
  color: rgba(229, 229, 229, 0.8);
  line-height: 1.25;
  flex: 1 1 auto;
  text-align: left;
}

.metrics-display {
  font-size: 0.4em;
  background: rgba(0,0,0,0.4);
  padding: 2px 6px;
  border-radius: 4px;
  margin-top: 4px;
  color: #1dd1a1;
  font-family: monospace;
}

.node-body {
  padding: 4px 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.handle-wrapper {
  position: relative;
  height: 28px;
  display: flex;
  align-items: center;
  background: rgba(255,255,255,0.05);
  margin: 1px 0;
}

.handle-wrapper.input {
  justify-content: flex-start;
  padding-left: 26px;
}

.handle-wrapper.output {
  justify-content: flex-end;
  padding-right: 26px;
}

.handle-label {
  font-size: 1.05em;
  font-weight: 500;
  color: #e8eaed;
  user-select: none;
}

:deep(.vue-flow__handle.custom-handle) {
  width: 24px !important;
  height: 24px !important;
  border-radius: 0px !important;
  border: none !important;
  top: 50% !important;
  transform: translateY(-50%) !important;
  z-index: 1;
}

:deep(.vue-flow__handle.target-handle) { left: 0 !important; }
:deep(.vue-flow__handle.source-handle) { right: 0 !important; }

.node-content {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 8px 14px;
  background: rgba(0,0,0,0.2);
}

.content-item {
  display: flex;
  flex-direction: row;
  align-items: center;
  gap: 10px;
  min-height: 0;
}

.input-label {
  flex: 0 0 120px;
  font-size: 0.9em;
  font-weight: 500;
  color: #9eead6;
  white-space: nowrap;
  text-align: right;
}

.content-item:has(.large-input),
.content-item:has(.slider-group),
.content-item:has(.checkbox-line) {
  flex-wrap: nowrap;
}

.content-item .large-input,
.content-item .slider-group,
.content-item .checkbox-line {
  flex: 1;
  min-width: 0;
}

.large-input {
  background: #101720;
  border: 2px solid #576574;
  color: #fff;
  padding: 4px 8px;
  border-radius: 4px;
  font-size: 0.95em;
  font-weight: 600;
  outline: none;
  width: 100%;
  box-sizing: border-box;
}

.large-input:focus {
  border-color: #1dd1a1;
  background: #000;
}

.number-field {
  -moz-appearance: textfield;
}

.number-field::-webkit-inner-spin-button,
.number-field::-webkit-outer-spin-button {
  -webkit-appearance: none;
  margin: 0;
}

.checkbox-line {
  padding: 0;
  display: flex;
  align-items: center;
}

.ultra-large-checkbox {
  width: 18px;
  height: 18px;
  cursor: pointer;
  margin: 0;
}

.slider-group {
  display: flex;
  align-items: center;
  gap: 10px;
  flex: 1;
  min-width: 0;
}

.chunky-slider {
  flex-grow: 1;
  height: 12px;
  min-width: 0;
}

.slider-number-input {
  width: 3.5rem;
  padding: 4px 6px;
  background: #101720;
  border: 2px solid #576574;
  color: #fff;
  border-radius: 4px;
  font-size: 0.9em;
  font-weight: 500;
  text-align: right;
  -moz-appearance: textfield;
  box-sizing: border-box;
}
.slider-number-input:focus {
  border-color: #1dd1a1;
  outline: none;
}
.slider-number-input::-webkit-inner-spin-button,
.slider-number-input::-webkit-outer-spin-button {
  -webkit-appearance: none;
  margin: 0;
}

.unknown-type-badge {
  font-size: 1.2em;
  color: #ee5253;
  font-weight: 900;
  border: 2px solid #ee5253;
  padding: 4px 10px;
  text-align: center;
}

/* Curve distribution graph */
.curve-chart-wrap {
  margin-top: 10px;
  padding: 8px 0;
  border-top: 1px solid rgba(255, 255, 255, 0.1);
}

.curve-chart-svg {
  width: 100%;
  height: 120px;
  display: block;
  background: rgba(0, 0, 0, 0.25);
  border-radius: 4px;
}

.curve-chart-svg .curve-grid line {
  stroke: rgba(255, 255, 255, 0.08);
  stroke-width: 1;
}

.curve-chart-svg .curve-line {
  stroke: #1dd1a1;
  stroke-linecap: round;
  stroke-linejoin: round;
}

.curve-chart-svg .curve-dot {
  fill: #1dd1a1;
  stroke: #0d3d32;
  stroke-width: 1.5;
}

.curve-chart-svg .curve-axis-tick {
  font-size: 9px;
  fill: rgba(255, 255, 255, 0.55);
  font-weight: 500;
}

.curve-chart-svg .curve-zero-line {
  stroke: rgba(255, 255, 255, 0.35);
  stroke-width: 1;
  stroke-dasharray: 4 2;
}

.curve-chart-svg .curve-zero-crossing-line {
  stroke: rgba(255, 255, 255, 0.4);
  stroke-width: 1;
  stroke-dasharray: 3 3;
}

.curve-chart-svg .curve-zero-crossing-text {
  font-size: 10px;
  fill: rgba(255, 255, 255, 0.75);
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

.curve-chart-svg .curve-zero-tick {
  fill: rgba(255, 255, 255, 0.9);
  font-weight: 700;
}

.curve-chart-svg .curve-hover-line {
  stroke: #9eead6;
  stroke-width: 1.5;
  stroke-opacity: 0.9;
  pointer-events: none;
}

.curve-chart-svg .curve-hover-x-text {
  font-size: 11px;
  font-weight: 700;
  fill: #9eead6;
  font-variant-numeric: tabular-nums;
  pointer-events: none;
}

.debug-section {
  margin: 6px 14px;
  padding: 8px 10px;
  background: rgba(255, 159, 67, 0.05);
  border: 2px dashed #ff9f43;
  border-radius: 4px;
}

.debug-header {
  font-size: 0.8rem;
  font-weight: 700;
  color: #ff9f43;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  margin-bottom: 6px;
  text-align: center;
}

.debug-grid {
  display: flex;
  flex-direction: row;
  gap: 12px;
  flex-wrap: wrap;
}

.debug-item {
  display: flex;
  flex-direction: row;
  align-items: center;
  gap: 8px;
  flex: 1;
  min-width: 0;
}

.debug-item label {
  font-size: 0.85em;
  color: #ff9f43;
  font-weight: 500;
  flex: 0 0 auto;
  margin: 0;
}

.read-only-field {
  background: #101720;
  border: 2px solid #3d3d3d;
  color: #fff;
  padding: 4px 8px;
  border-radius: 4px;
  font-family: monospace;
  font-size: 0.95em;
  font-weight: 700;
  text-align: right;
  overflow: hidden;
  text-overflow: ellipsis;
  flex: 1;
  min-width: 0;
}

.read-only-field--pending {
  color: rgba(255, 255, 255, 0.45);
  font-weight: 600;
}

.view-3d-btn {
  margin-top: 10px;
  width: 100%;
  padding: 12px 20px;
  border-radius: 6px;
  border: 2px solid #42d392;
  background: rgba(66, 211, 146, 0.15);
  color: #42d392;
  font-size: 1.1rem;
  font-weight: 700;
  cursor: pointer;
  text-transform: uppercase;
  letter-spacing: 0.08em;
}

.view-3d-btn:hover {
  background: rgba(66, 211, 146, 0.2);
}

.view-3d-btn.view-3d-updated {
  animation: view-3d-updated 1.5s ease-out;
}

@keyframes view-3d-updated {
  0% { box-shadow: 0 0 0 0 rgba(66, 211, 146, 0.5); }
  30% { box-shadow: 0 0 12px 4px rgba(66, 211, 146, 0.35); }
  100% { box-shadow: 0 0 0 0 rgba(66, 211, 146, 0); }
}
</style>

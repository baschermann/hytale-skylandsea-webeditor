<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, unref, watch } from 'vue';
import { useRouter } from 'vue-router';
import type { DensityMeshResponse } from '@/services/density-nodes';
import { fetchDensityMesh } from '@/services/density-nodes';
import { createBasicScene } from '@/three/basicScene';
import { useEditorStore } from '@/stores/editor';
import { toBlob } from 'html-to-image';
import * as THREE from 'three';

const store = useEditorStore();
const router = useRouter();

const targetFpsLocal = ref('60');
const minResLocal = ref('15');

watch(
  () => store.density3dSettingsMenuOpen,
  (open) => {
    if (open) {
      targetFpsLocal.value = String(store.density3dTargetFps);
      minResLocal.value = String(store.density3dMinResolutionPct);
    }
  }
);

function isTargetFpsOutOfRange(): boolean {
  const n = Number(targetFpsLocal.value);
  return !Number.isFinite(n) || n < store.TARGET_FPS_MIN || n > store.TARGET_FPS_MAX;
}
function isMinResOutOfRange(): boolean {
  const n = Number(minResLocal.value);
  return !Number.isFinite(n) || n < store.MIN_RESOLUTION_PCT_MIN || n > store.MIN_RESOLUTION_PCT_MAX;
}
function onTargetFpsBlur() {
  const n = Number(targetFpsLocal.value);
  if (Number.isFinite(n)) {
    store.setDensity3dTargetFps(n);
    targetFpsLocal.value = String(store.density3dTargetFps);
  } else {
    targetFpsLocal.value = String(store.density3dTargetFps);
  }
}
function onMinResBlur() {
  const n = Number(minResLocal.value);
  if (Number.isFinite(n)) {
    store.setDensity3dMinResolutionPct(n);
    minResLocal.value = String(store.density3dMinResolutionPct);
  } else {
    minResLocal.value = String(store.density3dMinResolutionPct);
  }
}

function closeSettingsIfOpen() {
  if (store.density3dSettingsMenuOpen) {
    store.density3dSettingsMenuOpen = false;
  }
}

interface Props {
  nodeId: string;
  /** Node IDs connected to this node's inputs (upstream outputs in graph). */
  inputNodeIds?: string[];
  /** Optional labels per input (e.g. node type), same order as inputNodeIds. */
  inputNodeLabels?: string[];
}

const props = withDefaults(defineProps<Props>(), {
  inputNodeIds: () => [],
  inputNodeLabels: () => [],
});

const containerRef = ref<HTMLElement | null>(null);
/** Snapshot root: WebGL canvas plus all absolute-positioned overlays (labels, layers, threshold, etc.). */
const canvasWrapperRef = ref<HTMLElement | null>(null);
const screenshotBusy = ref(false);
const CHUNK_SIZE = 32;

/** World Y planes matching Hytale `ChunkUtil.MIN_Y` / `ChunkUtil.HEIGHT` (sampled volume uses this range). */
const WORLD_Y_BOTTOM = 0;
const WORLD_Y_TOP = 320;

/** Single shared camera for all density 3D views (not per-node). */
const DENSITY_3D_CAMERA_STORE_KEY = 'density-3d-view-camera';
const LAYER_VISIBILITY_KEY = 'density-3d-view-layers';
const CHUNK_RENDER_MAX = store.CHUNK_RENDER_RADIUS_MAX;

interface SavedCameraState {
  position: { x: number; y: number; z: number };
  rotation: { x: number; y: number; z: number };
}

/** Old per-node keys; read once to migrate into `DENSITY_3D_CAMERA_STORE_KEY`. */
function legacyPerNodeCameraKey(nodeId: string): string {
  return `${DENSITY_3D_CAMERA_STORE_KEY}:${nodeId}`;
}

function getSavedCameraState(): SavedCameraState | null {
  try {
    let raw = localStorage.getItem(DENSITY_3D_CAMERA_STORE_KEY);
    if (!raw) {
      try {
        raw = sessionStorage.getItem(DENSITY_3D_CAMERA_STORE_KEY);
      } catch {
        raw = null;
      }
    }
    if (!raw) {
      raw = localStorage.getItem(legacyPerNodeCameraKey(props.nodeId));
      if (raw) {
        try {
          localStorage.setItem(DENSITY_3D_CAMERA_STORE_KEY, raw);
        } catch {
          /* ignore */
        }
      }
    }
    if (!raw) return null;
    const data = JSON.parse(raw) as SavedCameraState;
    if (!data?.position || !data?.rotation) return null;
    return data;
  } catch {
    return null;
  }
}

function saveCameraState() {
  if (!sceneHandle) return;
  const cam = sceneHandle.camera;
  const state: SavedCameraState = {
    position: { x: cam.position.x, y: cam.position.y, z: cam.position.z },
    rotation: { x: cam.rotation.x, y: cam.rotation.y, z: cam.rotation.z },
  };
  try {
    localStorage.setItem(DENSITY_3D_CAMERA_STORE_KEY, JSON.stringify(state));
  } catch {
    /* ignore */
  }
}

function scheduleSaveCameraState() {
  if (cameraSaveTimer != null) return;
  cameraSaveTimer = setTimeout(() => {
    cameraSaveTimer = null;
    saveCameraState();
  }, 400);
}

function flushCameraSave() {
  if (cameraSaveTimer != null) {
    clearTimeout(cameraSaveTimer);
    cameraSaveTimer = null;
  }
  saveCameraState();
}

function applyCameraFromStorageOrFit() {
  if (!sceneHandle) return;
  const saved = getSavedCameraState();
  if (saved) {
    applySavedCameraState(sceneHandle.camera, saved);
  } else {
    resetCamera();
  }
}

function applySavedCameraState(camera: THREE.PerspectiveCamera, saved: SavedCameraState) {
  camera.position.set(saved.position.x, saved.position.y, saved.position.z);
  camera.rotation.set(saved.rotation.x, saved.rotation.y, saved.rotation.z);
}

function getSavedLayerVisibility(nodeId: string): boolean[] | null {
  try {
    const raw = sessionStorage.getItem(LAYER_VISIBILITY_KEY);
    if (!raw) return null;
    const data = JSON.parse(raw) as Record<string, boolean[]>;
    const arr = data[nodeId];
    return Array.isArray(arr) ? arr : null;
  } catch {
    return null;
  }
}

function saveLayerVisibility(nodeId: string, visible: boolean[]) {
  try {
    const raw = sessionStorage.getItem(LAYER_VISIBILITY_KEY);
    const data: Record<string, boolean[]> = raw ? JSON.parse(raw) : {};
    data[nodeId] = visible;
    sessionStorage.setItem(LAYER_VISIBILITY_KEY, JSON.stringify(data));
  } catch {
    // ignore
  }
}

function resetCamera() {
  if (!sceneHandle) return;
  const box = new THREE.Box3();
  for (const mesh of layerMeshRefs) {
    if (mesh.visible) box.expandByObject(mesh);
  }
  if (layerMeshRefs.length === 0 || box.isEmpty()) return;
  const center = new THREE.Vector3();
  box.getCenter(center);
  const size = new THREE.Vector3();
  box.getSize(size);
  const maxDim = Math.max(size.x, size.y, size.z);
  if (maxDim <= 0) return;
  sceneHandle.setTarget(center);
  const fov = sceneHandle.camera.fov * (Math.PI / 180);
  const cameraZ = Math.abs(maxDim / 2 / Math.tan(fov / 2)) * 1.5;
  sceneHandle.camera.position.set(center.x + cameraZ, center.y + cameraZ, center.z + cameraZ);
  sceneHandle.camera.lookAt(center);
  saveCameraState();
}

/** Single layer: result or one input. loading=spinner only for visible layers; pending=hidden layer not resolved yet. */
interface LayerState {
  id: string;
  label: string;
  field: DensityMeshResponse | null;
  visible: boolean;
  color: number;
  available: boolean;
  loading: boolean;
  /** True: hidden layer not yet requested (waits until visible layers complete). */
  pending: boolean;
}

const R0 = store.chunkRenderRadius;
const cx0 = store.viewportCenterChunkX;
const cz0 = store.viewportCenterChunkZ;
/** Match server viewport: (2R+1) chunks per axis, anchored at chunk (cx−R, cz−R). */
const spanChunks0 = 2 * R0 + 1;
const initialFieldSize = spanChunks0 * CHUNK_SIZE;
const initialX = (cx0 - R0) * CHUNK_SIZE;
const initialZ = (cz0 - R0) * CHUNK_SIZE;

const state = reactive<{
  error: string | null;
  field: DensityMeshResponse | null;
  width: number;
  depth: number;
  x: number;
  z: number;
  resolution: number;
  renderDistanceChunks: number;
  threshold: number;
  layers: LayerState[];
}>({
  error: null,
  field: null,
  width: initialFieldSize,
  depth: initialFieldSize,
  x: initialX,
  z: initialZ,
  resolution: initialFieldSize >= 256 ? 2 : 1,
  renderDistanceChunks: R0,
  threshold: 0,
  layers: [],
});

const viewportCenterChunkXModel = computed({
  get: () => store.viewportCenterChunkX,
  set: (v: number) => {
    const n = Math.round(Number(v));
    if (Number.isFinite(n)) store.setViewportCenterChunkX(n);
  },
});
const viewportCenterChunkZModel = computed({
  get: () => store.viewportCenterChunkZ,
  set: (v: number) => {
    const n = Math.round(Number(v));
    if (Number.isFinite(n)) store.setViewportCenterChunkZ(n);
  },
});

const thresholdMin = computed(() => store.nodeMetrics[props.nodeId]?.min ?? -100);
const thresholdMax = computed(() => store.nodeMetrics[props.nodeId]?.max ?? 100);

const LAYER_COLORS = [0x42d392, 0x647eff, 0xe84c5c, 0xf0b429, 0x9b59b6];
let sceneHandle: ReturnType<typeof createBasicScene> | null = null;
let currentVoxelGroup: THREE.Group | null = null;
let layerMeshRefs: THREE.Group[] = [];
/** Grids at y=0 / y=320 + vertical corner posts for render-distance bounds; visibility in `renderLayersWithPerLayerDepth`. */
let helpersRoot: THREE.Group | null = null;
let animationFrame: number | null = null;
/** Incremented on each `loadField` so in-flight fetches ignore stale completions. */
let loadFieldSeq = 0;
/** Pending coalesced loadField — only one runs at a time; rapid calls are merged. */
let loadFieldPending = false;
let loadFieldRunning = false;
/** After `nodeId` changes, re-apply stored camera (or fit) once — not on every density refresh. */
let cameraNeedsReapplyForNode = false;
let cameraSaveTimer: ReturnType<typeof setTimeout> | null = null;

const raycaster = new THREE.Raycaster();
const pointerNdc = new THREE.Vector2();
let pickMarker: THREE.Mesh | null = null;
/** Axis-aligned lines through the pick (X/Y/Z) within the current render footprint. */
let pickDebugLines: THREE.LineSegments | null = null;
let pickListenerCleanup: (() => void) | null = null;

/** Full-screen help overlay: instructions (toggle with H). */
const helpOverlayOpen = ref(false);

function isTextLikeInput(target: EventTarget | null): boolean {
  const el = target as HTMLElement | null;
  if (!el) return false;
  if (el.isContentEditable) return true;
  if (el.tagName === 'TEXTAREA') return true;
  if (el.tagName !== 'INPUT') return false;
  const type = (el as HTMLInputElement).type;
  return type === 'text' || type === 'search' || type === 'url' || type === 'password';
}

function onHelpHotkey(e: KeyboardEvent) {
  if (e.key !== 'h' && e.key !== 'H') return;
  if (e.ctrlKey || e.metaKey || e.altKey) return;
  if (isTextLikeInput(e.target)) return;
  e.preventDefault();
  helpOverlayOpen.value = !helpOverlayOpen.value;
}

function disposeHeightHelpers() {
  if (helpersRoot && sceneHandle) {
    sceneHandle.scene.remove(helpersRoot);
  }
  if (helpersRoot) {
    helpersRoot.traverse((o) => {
      if (o instanceof THREE.Mesh || o instanceof THREE.LineSegments) {
        o.geometry?.dispose();
        const m = o.material as THREE.Material | THREE.Material[] | undefined;
        if (Array.isArray(m)) m.forEach((mat) => mat.dispose());
        else if (m) m.dispose();
      }
    });
  }
  helpersRoot = null;
}

/** XZ grids at `y` sized to the current render-distance footprint (GridHelper + non-uniform scale). */
function addXZGrid(
  group: THREE.Group,
  w: number,
  d: number,
  cx: number,
  y: number,
  cz: number,
  colorCenter: number,
  colorGrid: number
) {
  const maxH = Math.max(w, d);
  const divisions = Math.max(4, Math.round(maxH / CHUNK_SIZE));
  const grid = new THREE.GridHelper(maxH, divisions, colorCenter, colorGrid);
  grid.scale.set(w / maxH, 1, d / maxH);
  grid.position.set(cx, y, cz);
  group.add(grid);
}

function buildOrUpdateHeightHelpers() {
  disposeHeightHelpers();
  if (!sceneHandle || !store.density3dShowHeightHelpers) return;

  const w = Math.max(state.width, 1);
  const d = Math.max(state.depth, 1);
  const cx = state.x + w / 2;
  const cz = state.z + d / 2;
  const x0 = state.x;
  const x1 = state.x + w;
  const z0 = state.z;
  const z1 = state.z + d;

  const group = new THREE.Group();
  group.name = 'reference-helpers';

  addXZGrid(group, w, d, cx, WORLD_Y_BOTTOM, cz, 0x5f7a8f, 0x3a4d5c);
  addXZGrid(group, w, d, cx, WORLD_Y_TOP, cz, 0xb8926e, 0x6b5440);

  const edgeGeom = new THREE.BufferGeometry();
  edgeGeom.setAttribute(
    'position',
    new THREE.BufferAttribute(
      new Float32Array([
        x0, WORLD_Y_BOTTOM, z0, x0, WORLD_Y_TOP, z0,
        x1, WORLD_Y_BOTTOM, z0, x1, WORLD_Y_TOP, z0,
        x0, WORLD_Y_BOTTOM, z1, x0, WORLD_Y_TOP, z1,
        x1, WORLD_Y_BOTTOM, z1, x1, WORLD_Y_TOP, z1,
      ]),
      3
    )
  );
  const edges = new THREE.LineSegments(
    edgeGeom,
    new THREE.LineBasicMaterial({
      color: 0x9aa3ad,
      transparent: true,
      opacity: 0.65,
      depthWrite: false,
    })
  );
  group.add(edges);

  helpersRoot = group;
  helpersRoot.visible = false;
  sceneHandle.scene.add(helpersRoot);
}

// Performance stats + adaptive resolution (target FPS from store; resolution down to 5%)
const perfFps = ref(0);
const perfTriangles = ref(0);
const perfMovementSpeed = ref(0);
const FPS_UPDATE_INTERVAL_MS = 200;
const FPS_WINDOW_MS = 1000;
const FPS_OK_HYSTERESIS = 2;
const RESOLUTION_CHANGE_COOLDOWN_MS = 500;
const RESOLUTION_STEPS = [1, 0.75, 0.5, 0.375, 0.25, 0.125, 0.1, 0.075, 0.05];
let lastFrameTime = 0;
let frameDeltas: number[] = [];
let lastFpsUpdateTime = 0;
let lastResolutionChangeTime = 0;
let resolutionScaleIndex = 0;
let resolutionIndexLow = 0;
let resolutionIndexHigh = RESOLUTION_STEPS.length - 1;
let lowFpsFrames = 0;
let okFpsFrames = 0;

const params = () => ({
  width: state.width,
  depth: state.depth,
  x: state.x,
  z: state.z,
  threshold: state.threshold,
});

function resetThresholdFromMetrics() {
  const min = thresholdMin.value;
  const max = thresholdMax.value;
  const zero = 0;
  if (zero >= min && zero <= max) {
    state.threshold = zero;
  } else if (zero < min) {
    state.threshold = min;
  } else if (zero > max) {
    state.threshold = max;
  } else {
    state.threshold = zero;
  }
}

function selectAllInput(event: Event) {
  const target = event.target as HTMLInputElement | null;
  if (target) target.select();
}

/** Ensures state.layers matches (result + inputs). Only visible layers use loading (spinner); hidden layers stay pending until fetched. */
function ensureLayersStructure() {
  const inputIds = props.inputNodeIds ?? [];
  const labels = props.inputNodeLabels ?? [];
  const savedVisibility = getSavedLayerVisibility(props.nodeId);
  const slots: { id: string; label: string; color: number }[] = [
    { id: 'result', label: 'Result', color: LAYER_COLORS[0]! },
    ...inputIds.map((id, i) => {
      const typeName = (labels[i] ?? '').trim();
      return { id: `input-${i}`, label: typeName ? `${i} ${typeName}` : `Input ${i}`, color: LAYER_COLORS[(i % (LAYER_COLORS.length - 1)) + 1]! };
    }),
  ];
  const prevLayers = state.layers;
  if (prevLayers.length !== slots.length) {
    state.layers = slots.map((slot, i) => {
      const visible = savedVisibility?.[i] ?? i === 0;
      return {
        id: slot.id,
        label: slot.label,
        field: null,
        visible,
        color: slot.color,
        available: false,
        loading: visible,
        pending: !visible,
      };
    });
    return;
  }
  slots.forEach((slot, i) => {
    const prev = prevLayers[i];
    if (prev && (prev.id !== slot.id || prev.label !== slot.label)) {
      prev.id = slot.id;
      prev.label = slot.label;
      prev.color = slot.color;
      prev.field = null;
      prev.available = false;
      prev.pending = !prev.visible;
      prev.loading = prev.visible;
    } else if (prev) {
      prev.pending = !prev.visible;
      prev.loading = prev.visible;
    }
  });
}

/** Rebuilds Three.js meshes from state.layers and updates the scene. Call after layer data changes. */
function removePickMarker() {
  if (!sceneHandle) return;
  if (pickDebugLines) {
    sceneHandle.scene.remove(pickDebugLines);
    pickDebugLines.geometry.dispose();
    const lm = pickDebugLines.material as THREE.Material;
    if (Array.isArray(lm)) lm.forEach((mat) => mat.dispose());
    else lm.dispose();
    pickDebugLines = null;
  }
  if (!pickMarker) return;
  sceneHandle.scene.remove(pickMarker);
  pickMarker.geometry.dispose();
  const m = pickMarker.material as THREE.Material;
  if (Array.isArray(m)) m.forEach((mat) => mat.dispose());
  else m.dispose();
  pickMarker = null;
}

function placePickMarker(x: number, y: number, z: number) {
  if (!sceneHandle) return;
  removePickMarker();
  const x0 = state.x;
  const x1 = state.x + state.width;
  const z0 = state.z;
  const z1 = state.z + state.depth;
  const lineGeom = new THREE.BufferGeometry();
  const pos = new Float32Array([
    x0,
    y,
    z,
    x1,
    y,
    z,
    x,
    WORLD_Y_BOTTOM,
    z,
    x,
    WORLD_Y_TOP,
    z,
    x,
    y,
    z0,
    x,
    y,
    z1,
  ]);
  lineGeom.setAttribute('position', new THREE.BufferAttribute(pos, 3));
  const col = new Float32Array([
    1, 0.35, 0.35, 1, 0.35, 0.35,
    0.35, 1, 0.45, 0.35, 1, 0.45,
    0.45, 0.65, 1, 0.45, 0.65, 1,
  ]);
  lineGeom.setAttribute('color', new THREE.BufferAttribute(col, 3));
  const lineMat = new THREE.LineBasicMaterial({
    vertexColors: true,
    depthTest: true,
    transparent: true,
    opacity: 0.9,
  });
  const lines = new THREE.LineSegments(lineGeom, lineMat);
  lines.renderOrder = 998;
  pickDebugLines = lines;
  sceneHandle.scene.add(pickDebugLines);

  const geom = new THREE.SphereGeometry(0.4, 14, 12);
  const mat = new THREE.MeshBasicMaterial({
    color: 0xf0b429,
    depthTest: true,
    transparent: true,
    opacity: 0.95,
  });
  const mesh = new THREE.Mesh(geom, mat);
  mesh.position.set(x, y, z);
  mesh.renderOrder = 999;
  pickMarker = mesh;
  sceneHandle.scene.add(pickMarker);
  renderLayersWithPerLayerDepth();
}

function onPickAtWorld(wx: number, wy: number, wz: number) {
  placePickMarker(wx, wy, wz);
  store.recordDensityTracePick(props.nodeId, wx, wy, wz);
}

function syncPickMarkerFromStore() {
  if (!sceneHandle) return;
  const pw = store.densityTracePickedWorld;
  if (pw) {
    placePickMarker(pw.x, pw.y, pw.z);
  }
}

function attachDensityPickListener() {
  pickListenerCleanup?.();
  pickListenerCleanup = null;
  if (!sceneHandle) return;
  const canvas = sceneHandle.renderer.domElement;
  const onMouseDown = (event: MouseEvent) => {
    if (!event.shiftKey || event.button !== 0 || !sceneHandle) return;
    const visibleMeshes: THREE.Mesh[] = [];
    layerMeshRefs.forEach((group, i) => {
      if (!state.layers[i]?.visible) return;
      group.traverse((c) => {
        if (c instanceof THREE.Mesh) visibleMeshes.push(c);
      });
    });
    if (visibleMeshes.length === 0) return;
    event.preventDefault();
    event.stopImmediatePropagation();

    const rect = canvas.getBoundingClientRect();
    pointerNdc.x = ((event.clientX - rect.left) / rect.width) * 2 - 1;
    pointerNdc.y = -((event.clientY - rect.top) / rect.height) * 2 + 1;
    raycaster.setFromCamera(pointerNdc, sceneHandle.camera);
    const hits = raycaster.intersectObjects(visibleMeshes, false);
    if (hits.length === 0) return;
    const p = hits[0]!.point;
    const wx = Math.round(p.x);
    const wy = Math.round(p.y);
    const wz = Math.round(p.z);
    onPickAtWorld(wx, wy, wz);
  };
  canvas.addEventListener('mousedown', onMouseDown, true);
  pickListenerCleanup = () => canvas.removeEventListener('mousedown', onMouseDown, true);
}

function refreshSceneMeshes() {
  if (!sceneHandle || !containerRef.value) return;
  for (const mesh of layerMeshRefs) {
    sceneHandle.scene.remove(mesh);
    if (mesh instanceof THREE.Group) {
      mesh.traverse((c) => {
        if (c instanceof THREE.Mesh && c.geometry) c.geometry.dispose();
        if (c instanceof THREE.Mesh && c.material) {
          const m = c.material as THREE.Material;
          if (Array.isArray(m)) m.forEach((mat) => mat.dispose());
          else m.dispose();
        }
      });
    }
  }
  layerMeshRefs = [];
  for (let i = 0; i < state.layers.length; i++) {
    const layer = state.layers[i]!;
    if (!layer.field) continue;
    const mesh = buildMeshFromResponse(layer.field, layer.color);
    mesh.visible = layer.visible;
    mesh.renderOrder = state.layers.length - 1 - i;
    sceneHandle.scene.add(mesh);
    layerMeshRefs.push(mesh as THREE.Group);
  }
  currentVoxelGroup = layerMeshRefs[0] ?? new THREE.Group();
  buildOrUpdateHeightHelpers();
  renderLayersWithPerLayerDepth();
}

/**
 * Schedule a field load.  Multiple calls that arrive while a fetch cycle is in
 * progress are coalesced: the running cycle finishes (its stale-check makes the
 * results harmless), then exactly ONE follow-up runs with the latest state.
 */
function loadField() {
  if (loadFieldRunning) {
    loadFieldPending = true;
    return;
  }
  void doLoadField();
}

async function doLoadField() {
  loadFieldRunning = true;
  loadFieldPending = false;

  try {
    const seq = ++loadFieldSeq;
    const stale = () => seq !== loadFieldSeq;

    state.error = null;
    ensureLayersStructure();
    if (containerRef.value && !sceneHandle) {
      initScene(containerRef.value);
    }
    if (sceneHandle) {
      syncPickMarkerFromStore();
    }
    const p = params();
    const inputIds = props.inputNodeIds ?? [];

    const setResult = (index: number, field: DensityMeshResponse | null, available: boolean) => {
      if (stale()) return;
      const layer = state.layers[index];
      if (!layer) return;
      layer.field = field;
      layer.available = available;
      layer.loading = false;
      layer.pending = false;
      if (index === 0) state.field = field;
      refreshSceneMeshes();
      if (field != null) {
        const nodeId = index === 0 ? props.nodeId : (props.inputNodeIds ?? [])[index - 1];
        if (nodeId) store.signalDensityDataLoaded(nodeId);
      }
    };

    const fetchLayer = (index: number): Promise<void> => {
      if (index === 0) {
        return fetchDensityMesh(props.nodeId, p)
          .then((m) => ({ field: m, ok: true as const }))
          .catch(() => ({ field: null, ok: false as const }))
          .then((res) => setResult(0, res.field, res.ok));
      }
      const id = inputIds[index - 1];
      if (id == null || id === '') {
        setResult(index, null, false);
        return Promise.resolve();
      }
      return fetchDensityMesh(id, p)
        .then((m) => ({ field: m, ok: true as const }))
        .catch(() => ({ field: null, ok: false as const }))
        .then((res) => setResult(index, res.field, res.ok));
    };

    const activeIndices: number[] = [];
    for (let i = 0; i < state.layers.length; i++) {
      if (state.layers[i]!.visible) {
        activeIndices.push(i);
      } else {
        const layer = state.layers[i]!;
        layer.loading = false;
        layer.pending = true;
      }
    }

    await Promise.all(activeIndices.map((i) => fetchLayer(i)));
    if (stale()) return;

    if (!state.layers[0]?.available) {
      state.error = 'No density field for this node.';
    }

    if (!stale() && sceneHandle && cameraNeedsReapplyForNode) {
      cameraNeedsReapplyForNode = false;
      applyCameraFromStorageOrFit();
    }

    if (!stale() && store.densityTracePickedWorld) {
      const pw = store.densityTracePickedWorld;
      void store.runDensityTraceAt(props.nodeId, pw.x, pw.y, pw.z, { silent: true });
    }
  } finally {
    loadFieldRunning = false;
    if (loadFieldPending) {
      loadFieldPending = false;
      void doLoadField();
    }
  }
}

/** World-space field extent from shared viewport center + radius (same as Configure Viewport). */
function applyFieldExtentFromStore() {
  const R = store.chunkRenderRadius;
  const cx = store.viewportCenterChunkX;
  const cz = store.viewportCenterChunkZ;
  const span = 2 * R + 1;
  const size = span * CHUNK_SIZE;
  const newX = (cx - R) * CHUNK_SIZE;
  const newZ = (cz - R) * CHUNK_SIZE;
  const newRes = size >= 256 ? 2 : 1;
  const changed =
    state.width !== size ||
    state.depth !== size ||
    state.x !== newX ||
    state.z !== newZ ||
    state.resolution !== newRes;
  state.width = size;
  state.depth = size;
  state.x = newX;
  state.z = newZ;
  state.resolution = newRes;
  if (state.renderDistanceChunks !== R) {
    state.renderDistanceChunks = R;
  }
  if (changed) {
    loadField();
  }
}

/** Build a Three.js mesh from server-supplied greedy-meshed data (no client-side meshing). */
function buildMeshFromResponse(meshData: DensityMeshResponse, layerColor: number): THREE.Object3D {
  const geometry = new THREE.BufferGeometry();
  geometry.setAttribute('position', new THREE.BufferAttribute(meshData.positions, 3));
  geometry.setAttribute('normal', new THREE.BufferAttribute(meshData.normals, 3));
  geometry.setIndex(Array.from(meshData.indices));
  geometry.computeBoundingSphere();

  const material = new THREE.MeshLambertMaterial({
    color: layerColor,
    transparent: true,
    opacity: 0.72,
    depthTest: true,
    depthWrite: true,
    side: THREE.DoubleSide,
  });

  const mesh = new THREE.Mesh(geometry, material);
  const group = new THREE.Group();
  group.add(mesh);
  return group;
}

function initScene(container: HTMLElement) {
  pickListenerCleanup?.();
  pickListenerCleanup = null;
  if (sceneHandle) {
    removePickMarker();
    saveCameraState();
    disposeHeightHelpers();
    sceneHandle.dispose();
    sceneHandle = null;
    currentVoxelGroup = null;
    layerMeshRefs = [];
  }
  if (animationFrame !== null) {
    cancelAnimationFrame(animationFrame);
    animationFrame = null;
  }

  function requestFrame() {
    if (animationFrame !== null) return;
    lastFrameTime = performance.now();
    animationFrame = requestAnimationFrame(animate);
  }

  sceneHandle = createBasicScene(container, { onRequestFrame: requestFrame });
  sceneHandle.renderer.autoClear = false;
  sceneHandle.renderer.setClearColor(0x050608);

  for (let i = 0; i < state.layers.length; i++) {
    const layer = state.layers[i]!;
    if (!layer.field) continue;
    const mesh = buildMeshFromResponse(layer.field, layer.color);
    mesh.visible = layer.visible;
    mesh.renderOrder = state.layers.length - 1 - i;
    sceneHandle.scene.add(mesh);
    layerMeshRefs.push(mesh as THREE.Group);
  }
  currentVoxelGroup = layerMeshRefs[0] ?? new THREE.Group();

  applyCameraFromStorageOrFit();

  lastFrameTime = performance.now();
  lastFpsUpdateTime = performance.now();
  const animate = () => {
    if (!sceneHandle) return;
    animationFrame = null;
    const now = performance.now();
    const deltaMs = Math.min(now - lastFrameTime, 100);
    lastFrameTime = now;

    const shouldRender = sceneHandle.update(deltaMs);
    if (shouldRender) {
      scheduleSaveCameraState();
      const autoResolution = unref(store.density3dAutoResolution) !== false;
      const minResolutionPct = unref(store.density3dMinResolutionPct) ?? 5;
      const minScale = Math.max(0.05, Math.min(1, minResolutionPct / 100));
      const target = unref(store.density3dTargetFps) ?? 60;
      const lowThreshold = target;
      const okThreshold = target + FPS_OK_HYSTERESIS;
      const instantFps = deltaMs > 0 ? 1000 / deltaMs : target;
      const canChangeResolution = now - lastResolutionChangeTime >= RESOLUTION_CHANGE_COOLDOWN_MS;

      if (autoResolution) {
        let maxIndex = RESOLUTION_STEPS.length - 1;
        while (maxIndex > 0 && RESOLUTION_STEPS[maxIndex]! < minScale) maxIndex--;
        if (instantFps < lowThreshold) {
          lowFpsFrames++;
          okFpsFrames = 0;
          if (canChangeResolution && lowFpsFrames >= 2) {
            resolutionIndexLow = Math.max(resolutionIndexLow, resolutionScaleIndex + 1);
            resolutionIndexHigh = Math.min(resolutionIndexHigh, maxIndex);
            if (resolutionIndexLow <= resolutionIndexHigh) {
              const mid = Math.floor((resolutionIndexLow + resolutionIndexHigh) / 2);
              resolutionScaleIndex = mid;
              sceneHandle.setResolutionScale(RESOLUTION_STEPS[mid]!);
              lastResolutionChangeTime = now;
              lowFpsFrames = 0;
            }
          }
        } else if (instantFps >= okThreshold) {
          okFpsFrames++;
          lowFpsFrames = 0;
          if (canChangeResolution && okFpsFrames >= 3) {
            resolutionIndexHigh = Math.min(resolutionIndexHigh, resolutionScaleIndex - 1);
            if (resolutionIndexLow <= resolutionIndexHigh) {
              const mid = Math.floor((resolutionIndexLow + resolutionIndexHigh) / 2);
              resolutionScaleIndex = mid;
              sceneHandle.setResolutionScale(RESOLUTION_STEPS[mid]!);
              lastResolutionChangeTime = now;
              okFpsFrames = 0;
            }
          }
        } else {
          lowFpsFrames = 0;
          okFpsFrames = 0;
        }
      }
      renderLayersWithPerLayerDepth();
      perfMovementSpeed.value = sceneHandle.getMovementSpeed();
      frameDeltas.push(deltaMs);
      let total = frameDeltas.reduce((a, b) => a + b, 0);
      while (frameDeltas.length > 1 && total > FPS_WINDOW_MS) {
        const first = frameDeltas.shift();
        total -= first ?? 0;
      }
      if (now - lastFpsUpdateTime >= FPS_UPDATE_INTERVAL_MS && frameDeltas.length > 0) {
        lastFpsUpdateTime = now;
        const totalMs = frameDeltas.reduce((a, b) => a + b, 0);
        const avgDelta = totalMs / frameDeltas.length;
        perfFps.value = avgDelta > 0 ? Math.round(1000 / avgDelta) : 0;
      }
      animationFrame = requestAnimationFrame(animate);
    } else {
      resolutionScaleIndex = 0;
      resolutionIndexLow = 0;
      resolutionIndexHigh = RESOLUTION_STEPS.length - 1;
      lowFpsFrames = 0;
      okFpsFrames = 0;
      lastResolutionChangeTime = 0;
      sceneHandle.setResolutionScale(1);
      renderLayersWithPerLayerDepth();
    }
  };

  buildOrUpdateHeightHelpers();
  renderLayersWithPerLayerDepth();
  perfMovementSpeed.value = sceneHandle.getMovementSpeed();
  attachDensityPickListener();
}

function countTriangles(obj: THREE.Object3D): number {
  let n = 0;
  obj.traverse((child) => {
    if (child instanceof THREE.Mesh && child.geometry) {
      const g = child.geometry;
      if (g.index) n += g.index.count / 3;
      else if (g.attributes.position) n += g.attributes.position.count / 3;
    }
  });
  return n;
}

function renderLayersWithPerLayerDepth() {
  if (!sceneHandle) return;
  if (layerMeshRefs.length === 0) {
    if (pickMarker == null && pickDebugLines == null) return;
    perfTriangles.value = 0;
    sceneHandle.renderer.clear(true, true, false);
    const origBackground = sceneHandle.scene.background;
    sceneHandle.scene.background = null;
    if (helpersRoot) helpersRoot.visible = store.density3dShowHeightHelpers;
    sceneHandle.renderer.render(sceneHandle.scene, sceneHandle.camera);
    sceneHandle.scene.background = origBackground;
    return;
  }
  const visibleIndices = state.layers
    .map((layer, i) => (layer.visible ? i : -1))
    .filter((i) => i >= 0);
  if (visibleIndices.length === 0) {
    perfTriangles.value = 0;
    sceneHandle.renderer.clear(true, true, false);
    const origBackground = sceneHandle.scene.background;
    sceneHandle.scene.background = null;
    layerMeshRefs.forEach((m) => {
      m.visible = false;
    });
    if (helpersRoot) helpersRoot.visible = store.density3dShowHeightHelpers;
    sceneHandle.renderer.render(sceneHandle.scene, sceneHandle.camera);
    sceneHandle.scene.background = origBackground;
    state.layers.forEach((layer, i) => {
      const mesh = layerMeshRefs[i];
      if (mesh) mesh.visible = layer.visible;
    });
    return;
  }
  const backToFront = [...visibleIndices].sort((a, b) =>
    a === 0 ? 1 : b === 0 ? -1 : b - a
  );
  sceneHandle.renderer.clear(true, true, false);
  const origBackground = sceneHandle.scene.background;
  sceneHandle.scene.background = null;
  for (let p = 0; p < backToFront.length; p++) {
    const i = backToFront[p]!;
    sceneHandle.renderer.clear(false, true, false);
    layerMeshRefs.forEach((mesh, j) => {
      mesh.visible = j === i;
    });
    if (helpersRoot) {
      helpersRoot.visible =
        store.density3dShowHeightHelpers && p === backToFront.length - 1;
    }
    sceneHandle.renderer.render(sceneHandle.scene, sceneHandle.camera);
  }
  sceneHandle.scene.background = origBackground;
  state.layers.forEach((layer, i) => {
    const mesh = layerMeshRefs[i];
    if (mesh) mesh.visible = layer.visible;
  });
  if (helpersRoot) {
    helpersRoot.visible = store.density3dShowHeightHelpers;
  }
  // Triangles = sum over visible layers only (reflects what was actually rendered)
  let totalTriangles = 0;
  for (const i of visibleIndices) {
    const mesh = layerMeshRefs[i];
    if (mesh) totalTriangles += countTriangles(mesh);
  }
  perfTriangles.value = totalTriangles;
}

function applyLayerVisibility() {
  state.layers.forEach((layer, i) => {
    const mesh = layerMeshRefs[i];
    if (mesh) mesh.visible = layer.visible;
  });
  saveLayerVisibility(props.nodeId, state.layers.map((l) => l.visible));
  if (sceneHandle) {
    renderLayersWithPerLayerDepth();
  }
}

function onLayerCheckboxChange(layer: LayerState) {
  layer.visible = !layer.visible;
  if (layer.visible && layer.pending) {
    void fetchSingleLayer(layer);
  } else {
    applyLayerVisibility();
  }
}

/** Fetch one specific layer without re-running the full loadField cycle. */
async function fetchSingleLayer(layer: LayerState) {
  const layerIndex = state.layers.indexOf(layer);
  if (layerIndex < 0) return;
  layer.loading = true;
  layer.pending = false;
  const p = params();
  const inputIds = props.inputNodeIds ?? [];
  let nodeId: string;
  if (layerIndex === 0) {
    nodeId = props.nodeId;
  } else {
    const id = inputIds[layerIndex - 1];
    if (id == null || id === '') {
      layer.field = null;
      layer.available = false;
      layer.loading = false;
      refreshSceneMeshes();
      return;
    }
    nodeId = id;
  }
  try {
    const mesh = await fetchDensityMesh(nodeId, p);
    layer.field = mesh;
    layer.available = true;
  } catch {
    layer.field = null;
    layer.available = false;
  } finally {
    layer.loading = false;
    layer.pending = false;
  }
  if (layerIndex === 0) state.field = layer.field;
  refreshSceneMeshes();
  if (layer.field != null && nodeId) {
    store.signalDensityDataLoaded(nodeId);
  }
}

async function copy3dViewScreenshot() {
  const wrap = canvasWrapperRef.value;
  if (!wrap || !sceneHandle) {
    store.showToast('3D view is not ready yet.');
    return;
  }
  if (!navigator.clipboard?.write) {
    store.showToast('Clipboard is not available.');
    return;
  }
  if (typeof ClipboardItem === 'undefined') {
    store.showToast('Copying images is not supported in this browser.');
    return;
  }
  screenshotBusy.value = true;
  try {
    renderLayersWithPerLayerDepth();
    await new Promise<void>((resolve) =>
      requestAnimationFrame(() => requestAnimationFrame(() => resolve()))
    );
    const blob = await toBlob(wrap, {
      pixelRatio: Math.min(window.devicePixelRatio || 1, 2),
      backgroundColor: '#050608',
      cacheBust: true,
    });
    if (!blob) {
      store.showToast('Could not create screenshot.');
      return;
    }
    const type = blob.type || 'image/png';
    await navigator.clipboard.write([new ClipboardItem({ [type]: blob })]);
    store.showToast('Screenshot copied to clipboard');
  } catch (err: unknown) {
    const msg = err instanceof Error ? err.message : String(err);
    store.showToast('Could not copy screenshot: ' + msg);
  } finally {
    screenshotBusy.value = false;
  }
}

function onPageHideOrUnload() {
  flushCameraSave();
}

/** Debounce for viewport-refresh requests triggered by toolbar Center X/Z or Radius edits. */
const VIEWPORT_REFRESH_DEBOUNCE_MS = 300;
let viewportRefreshTimer: ReturnType<typeof setTimeout> | null = null;

function scheduleViewportRefresh(force = false) {
  if (viewportRefreshTimer != null) clearTimeout(viewportRefreshTimer);
  viewportRefreshTimer = setTimeout(() => {
    viewportRefreshTimer = null;
    void store.triggerViewportRefreshIfNeeded(force ? { force: true } : undefined);
  }, VIEWPORT_REFRESH_DEBOUNCE_MS);
}

onMounted(() => {
  store.incDensityViewActive();
  resetThresholdFromMetrics();
  loadField();
  // First viewport refresh for this 3D view. Dedupe inside the store prevents a repeat when opening a
  // second 3D view for the same world/center/radius, and skips the server call entirely when the world
  // is not yet selected.
  void store.triggerViewportRefreshIfNeeded();
  window.addEventListener('keydown', onHelpHotkey, true);
  window.addEventListener('beforeunload', onPageHideOrUnload);
  window.addEventListener('pagehide', onPageHideOrUnload);
});

/**
 * While a 3D view is mounted, every change to the viewport footprint (world/center/radius) re-runs
 * server-side generation so the 3D view never shows stale data. Debounced for Center X/Z / Radius
 * spinners; dedupe inside the store elides repeat calls for the same footprint.
 */
watch(
  () => [
    store.selectedWorldName,
    store.chunkRenderRadius,
    store.viewportCenterChunkX,
    store.viewportCenterChunkZ,
  ] as const,
  () => {
    scheduleViewportRefresh();
  },
);

watch(
  () => props.nodeId,
  () => {
    cameraNeedsReapplyForNode = true;
    removePickMarker();
    resetThresholdFromMetrics();
    // Restore this node's saved pose immediately so the view does not stay on the
    // previous node until layers finish loading (avoids a snap that coincides with
    // pick-marker reapplication at end of loadField).
    if (sceneHandle) {
      const saved = getSavedCameraState();
      if (saved) {
        applySavedCameraState(sceneHandle.camera, saved);
        cameraNeedsReapplyForNode = false;
      }
    }
    loadField();
  }
);

watch(
  () => store.densityTracePickedWorld,
  (v) => {
    if (v == null) {
      removePickMarker();
      return;
    }
    if (sceneHandle) {
      placePickMarker(v.x, v.y, v.z);
    }
  },
  { deep: true }
);

watch(
  () => state.threshold,
  (val) => {
    const min = thresholdMin.value;
    const max = thresholdMax.value;
    if (!Number.isFinite(val)) {
      return;
    }
    if (val < min) {
      state.threshold = min;
    } else if (val > max) {
      state.threshold = max;
    }
  }
);

// SSE updates are sent only when all chunks are generated (backend debounce), so one message -> one load
watch(
  () => store.densityUpdateSignal,
  () => {
    if (!containerRef.value) return;
    loadField();
  }
);

watch(
  () => state.layers,
  () => applyLayerVisibility(),
  { deep: true }
);

// Auto-close 3D view when no debug data is available (e.g. after clear cache or node has no density)
watch(
  () => [state.field, state.error, state.layers[0]?.loading] as const,
  ([field, error, resultLoading]) => {
    if (field == null && error != null && !resultLoading) {
      router.push('/');
    }
  }
);

watch(
  () => store.density3dShowHeightHelpers,
  () => {
    buildOrUpdateHeightHelpers();
    if (sceneHandle) {
      renderLayersWithPerLayerDepth();
    }
  }
);

watch(
  () => state.renderDistanceChunks,
  (chunks) => {
    const n = Math.round(Number(chunks));
    if (!Number.isFinite(n)) return;
    const c = Math.max(1, Math.min(CHUNK_RENDER_MAX, n));
    if (c !== n) {
      state.renderDistanceChunks = c;
    }
    if (c !== store.chunkRenderRadius) {
      store.setChunkRenderRadius(c);
    }
  }
);

watch(
  () => [store.chunkRenderRadius, store.viewportCenterChunkX, store.viewportCenterChunkZ] as const,
  () => {
    applyFieldExtentFromStore();
  }
);

watch(
  () => [state.width, state.depth, state.x, state.z] as const,
  () => {
    buildOrUpdateHeightHelpers();
    if (sceneHandle) {
      renderLayersWithPerLayerDepth();
    }
    const pw = store.densityTracePickedWorld;
    if (sceneHandle && pw) {
      placePickMarker(pw.x, pw.y, pw.z);
    }
  }
);

onBeforeUnmount(() => {
  store.decDensityViewActive();
  if (viewportRefreshTimer != null) {
    clearTimeout(viewportRefreshTimer);
    viewportRefreshTimer = null;
  }
  window.removeEventListener('keydown', onHelpHotkey, true);
  window.removeEventListener('beforeunload', onPageHideOrUnload);
  window.removeEventListener('pagehide', onPageHideOrUnload);
  pickListenerCleanup?.();
  pickListenerCleanup = null;
  if (animationFrame !== null) {
    cancelAnimationFrame(animationFrame);
  }
  if (cameraSaveTimer != null) {
    clearTimeout(cameraSaveTimer);
    cameraSaveTimer = null;
  }
  if (sceneHandle) {
    removePickMarker();
    flushCameraSave();
    disposeHeightHelpers();
    sceneHandle.dispose();
  }
});
</script>

<template>
  <div class="density-viewer" @click="closeSettingsIfOpen">
    <div v-if="state.error && !helpOverlayOpen" class="density-error-banner" role="alert">
      <span class="error">Error: {{ state.error }}</span>
    </div>

    <div class="content">
      <div ref="canvasWrapperRef" class="canvas-wrapper">
        <div class="density-toolbar">
          <div class="control-row density-toolbar-controls">
            <button
              type="button"
              class="reset-camera-btn"
              title="Reset camera to default view"
              :disabled="!state.field"
              @click="resetCamera"
            >
              Reset camera
            </button>
            <button
              type="button"
              class="reset-camera-btn copy-screenshot-btn"
              title="Copy the 3D view and on-screen labels to the clipboard (PNG)"
              :disabled="screenshotBusy || !state.field"
              @click="copy3dViewScreenshot"
            >
              {{ screenshotBusy ? 'Copying…' : 'Copy screenshot' }}
            </button>
            <span class="label">Center X (chunk)</span>
            <div class="render-distance-control">
              <input
                type="number"
                step="1"
                v-model.number="viewportCenterChunkXModel"
                class="render-distance-input"
              />
            </div>
            <span class="label">Center Z (chunk)</span>
            <div class="render-distance-control">
              <input
                type="number"
                step="1"
                v-model.number="viewportCenterChunkZModel"
                class="render-distance-input"
              />
            </div>
            <span class="label">Radius (chunks)</span>
            <div class="render-distance-control">
              <button
                type="button"
                class="render-distance-btn"
                @click="state.renderDistanceChunks > 1 && state.renderDistanceChunks--"
              >
                −
              </button>
              <input
                type="number"
                min="1"
                :max="CHUNK_RENDER_MAX"
                v-model.number="state.renderDistanceChunks"
                class="render-distance-input"
              />
              <button
                type="button"
                class="render-distance-btn"
                @click="state.renderDistanceChunks < CHUNK_RENDER_MAX && state.renderDistanceChunks++"
              >
                +
              </button>
            </div>
          </div>
          <div class="density-toolbar-end">
            <p class="density-toolbar-hint" aria-hidden="true">
              Press <kbd class="help-kbd">H</kbd> for help
            </p>
            <div class="viewer-3d-header-actions">
              <button
                type="button"
                class="viewer-menu-btn"
                title="3D view settings"
                aria-label="Settings"
                :aria-expanded="store.density3dSettingsMenuOpen"
                @click.stop="store.density3dSettingsMenuOpen = !store.density3dSettingsMenuOpen"
              >
                <span class="viewer-menu-icon" aria-hidden="true">⋮</span>
              </button>
              <router-link to="/" class="close-btn" title="Close 3D view">×</router-link>
              <div v-if="store.density3dSettingsMenuOpen" class="viewer-settings-panel" @click.stop>
                <label class="viewer-settings-checkbox">
                  <input
                    type="checkbox"
                    :checked="store.density3dAutoResolution"
                    @change="(e: Event) => store.setDensity3dAutoResolution((e.target as HTMLInputElement).checked)"
                  />
                  <span>Auto resolution scaling</span>
                </label>
                <div class="viewer-settings-row">
                  <label for="density-viewer-target-fps">Target FPS</label>
                  <input
                    id="density-viewer-target-fps"
                    type="number"
                    :value="targetFpsLocal"
                    :class="{ 'out-of-range': isTargetFpsOutOfRange() }"
                    @input="(e: Event) => { targetFpsLocal = (e.target as HTMLInputElement).value }"
                    @blur="onTargetFpsBlur"
                  />
                </div>
                <div class="viewer-settings-row">
                  <label for="density-viewer-min-res">Min resolution %</label>
                  <input
                    id="density-viewer-min-res"
                    type="number"
                    :value="minResLocal"
                    :class="{ 'out-of-range': isMinResOutOfRange() }"
                    @input="(e: Event) => { minResLocal = (e.target as HTMLInputElement).value }"
                    @blur="onMinResBlur"
                  />
                </div>
                <label class="viewer-settings-checkbox">
                  <input
                    type="checkbox"
                    :checked="store.density3dShowHeightHelpers"
                    @change="(e: Event) =>
                      store.setDensity3dShowHeightHelpers((e.target as HTMLInputElement).checked)"
                  />
                  <span>Show chunk reference</span>
                </label>
                <p class="viewer-settings-hint">
                  When auto is on, resolution scales to keep FPS ≥ target, down to the min %.
                </p>
              </div>
            </div>
          </div>
        </div>

        <div class="density-canvas-stack">
          <div
            v-if="helpOverlayOpen"
            class="help-overlay"
            role="dialog"
            aria-modal="true"
            aria-labelledby="help-overlay-title"
          >
            <div class="help-overlay-inner">
              <div class="help-overlay-top">
                <h2 id="help-overlay-title" class="help-overlay-title">Controls &amp; help</h2>
                <p class="help-overlay-close-hint">Press <kbd class="help-kbd">H</kbd> to close</p>
              </div>
              <div v-if="state.error" class="help-overlay-error">
                <span class="error">Error: {{ state.error }}</span>
              </div>
              <div class="help-overlay-body">
                <p>
                  <strong>Show references</strong> is in the ⋮ settings menu (grids at y=0 / y=320 and corner posts).
                </p>
                <p>
                  <strong>Viewport:</strong> Pick a world in the top-bar dropdown. Center X/Z (chunk) and Radius (chunks)
                  in this toolbar drive server-side chunk generation: opening a 3D view, changing the world, or editing
                  Center/Radius all re-runs generation (debounced, deduplicated).
                </p>
                <p>
                  <strong>Pick &amp; trace:</strong>
                  Shift+click the mesh to pick a voxel; shift+click again elsewhere to move the trace point. The trace panel
                  sits to the right of the graph (or top-right when this 3D view is closed).
                </p>
                <p>
                  <strong>Camera:</strong>
                  Click the view to fly. <kbd class="help-kbd">Esc</kbd> exits pointer lock.
                  <kbd class="help-kbd">W</kbd><kbd class="help-kbd">A</kbd><kbd class="help-kbd">S</kbd><kbd class="help-kbd">D</kbd>,
                  Space (up), Alt or <kbd class="help-kbd">C</kbd> (down); mouse wheel = speed.
                  Chrome and Firefox reserve Ctrl+letter (e.g. Ctrl+W closes the tab), so the page cannot use Ctrl for descend together with WASD.
                </p>
                <p>
                  <strong>Threshold</strong> (slider at the bottom of the view) controls the density isosurface for the mesh.
                </p>
              </div>
            </div>
          </div>

          <div ref="containerRef" class="canvas-container"></div>
        <div class="threshold-bar" aria-label="Density threshold">
          <span class="range-bound">{{ thresholdMin.toFixed(1) }}</span>
          <input
            type="range"
            :min="thresholdMin"
            :max="thresholdMax"
            :step="(thresholdMax - thresholdMin) / 1000 || 0.1"
            v-model.number="state.threshold"
            @change="loadField"
          />
          <span class="range-bound">{{ thresholdMax.toFixed(1) }}</span>
          <input
            type="number"
            step="any"
            v-model.number="state.threshold"
            @change="loadField"
            @focus="selectAllInput"
            class="threshold-input"
          />
          <button
            type="button"
            class="threshold-reset-btn"
            title="Reset threshold to 0 (or closest allowed)"
            @click="resetThresholdFromMetrics(); loadField()"
          >
            ⟲
          </button>
        </div>
        <div class="perf-stats" aria-hidden="true">
          <span>{{ perfFps }} FPS</span>
          <span>{{ (perfTriangles / 1000).toFixed(1) }}k tris</span>
          <span class="perf-speed">speed {{ Math.round(perfMovementSpeed) }}</span>
        </div>
        <div class="density-node-id-overlay" :title="nodeId" role="status" :aria-label="'Node ID: ' + nodeId">
          {{ nodeId }}
        </div>
        <div v-if="state.layers.length > 0" class="layer-panel">
          <label
            v-for="layer in state.layers"
            :key="layer.id"
            class="layer-toggle"
            :class="{
              'layer-pending': layer.pending,
              'layer-unavailable': !layer.available && !layer.loading && !layer.pending,
            }"
            :title="
              layer.available
                ? 'Toggle ' + layer.label
                : layer.loading
                  ? 'Loading…'
                  : layer.pending
                    ? 'Will load after visible layers finish'
                    : 'Not a density node'
            "
          >
            <input
              type="checkbox"
              :checked="layer.visible"
              @change="onLayerCheckboxChange(layer)"
            />
            <span class="layer-dot" :style="{ background: '#' + layer.color.toString(16).padStart(6, '0') }"></span>
            <span class="layer-label">{{
              layer.label +
              (layer.available ? '' : layer.loading ? '…' : layer.pending ? '' : ' (n/a)')
            }}</span>
            <span v-if="layer.loading" class="layer-loading" aria-hidden="true"></span>
            <span v-else-if="layer.pending" class="layer-pending-dot" aria-hidden="true" title="Queued"></span>
          </label>
        </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.density-viewer {
  display: flex;
  flex-direction: column;
  width: 100%;
  height: 100%;
  background: #050608;
  color: #e5e5e5;
}

.density-error-banner {
  flex-shrink: 0;
  padding: 0.35rem 0.75rem;
  background: rgba(40, 12, 12, 0.95);
  border-bottom: 1px solid #522;
  font-size: 0.72rem;
  z-index: 10;
}

.control-row {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.btn-group {
  display: flex;
  gap: 0.25rem;
}

.label {
  color: #777;
  font-weight: 600;
  text-transform: uppercase;
  font-size: 0.65rem;
  margin-left: 0.5rem;
}

.checkbox-label {
  display: flex;
  align-items: center;
  gap: 0.25rem;
  font-size: 0.7rem;
  color: #aaa;
  cursor: pointer;
}

.perf-stats {
  position: absolute;
  top: 6px;
  left: 6px;
  font-size: 0.7rem;
  font-family: ui-monospace, monospace;
  color: #8a8;
  display: flex;
  flex-direction: column;
  gap: 0.04rem;
  z-index: 5;
  pointer-events: none;
  text-align: left;
}
.perf-stats .perf-speed {
  color: #6a8;
}

.density-node-id-overlay {
  position: absolute;
  top: 6px;
  right: 6px;
  max-width: min(420px, calc(100% - 24px));
  font-size: 0.7rem;
  font-family: ui-monospace, monospace;
  color: #8b95a3;
  z-index: 5;
  pointer-events: none;
  text-align: right;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  line-height: 1.35;
}

.layer-panel {
  position: absolute;
  bottom: 34px;
  left: 12px;
  display: flex;
  flex-direction: column;
  gap: 0.35rem;
  z-index: 5;
  pointer-events: auto;
}
.layer-panel .layer-toggle {
  margin-right: 0;
}

.threshold-bar {
  position: absolute;
  left: 50%;
  bottom: 28px;
  transform: translateX(-50%);
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.4rem 0.9rem;
  background: transparent;
  border-radius: 999px;
  z-index: 6;
  position: absolute;
}

.threshold-bar::before {
  content: '';
  position: absolute;
  inset: -8px -18px;
  border-radius: inherit;
  background: radial-gradient(
    ellipse at center,
    rgba(0, 0, 0, 0.55) 0%,
    rgba(0, 0, 0, 0.45) 40%,
    rgba(0, 0, 0, 0.0) 100%
  );
  filter: blur(6px);
  z-index: -1;
}

.threshold-bar input[type="range"] {
  width: 260px;
}
.layer-toggle {
  display: flex;
  align-items: center;
  gap: 0.35rem;
  font-size: 0.72rem;
  color: #ccc;
  cursor: pointer;
}
.layer-toggle:hover {
  color: #fff;
}
.layer-toggle.layer-pending {
  color: #a8c4d4;
  cursor: pointer;
}
.layer-toggle.layer-pending:hover {
  color: #c8dce8;
}
.layer-toggle.layer-unavailable {
  opacity: 0.5;
  cursor: default;
  color: #666;
}
.layer-toggle.layer-unavailable:hover {
  color: #666;
}
.layer-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  flex-shrink: 0;
}
.layer-label {
  max-width: 140px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.layer-loading {
  width: 10px;
  height: 10px;
  margin-left: 4px;
  border: 2px solid #333;
  border-top-color: #42d392;
  border-radius: 50%;
  animation: layer-spin 0.6s linear infinite;
  flex-shrink: 0;
}
@keyframes layer-spin {
  to { transform: rotate(360deg); }
}

.layer-pending-dot {
  width: 9px;
  height: 9px;
  margin-left: 4px;
  border-radius: 50%;
  flex-shrink: 0;
  border: 1.5px dashed #6a9aaa;
  opacity: 0.95;
  box-sizing: border-box;
}

select.preset-btn {
  appearance: none;
  padding-right: 1rem;
  background-image: url("data:image/svg+xml;charset=US-ASCII,%3Csvg%20xmlns%3D%22http%3A//www.w3.org/2000/svg%22%20width%3D%22292.4%22%20height%3D%22292.4%22%3E%3Cpath%20fill%3D%22%23666%22%20d%3D%22M287%2069.4a17.6%2017.6%200%200%200-13-5.4H18.4c-5%200-9.3%201.8-12.9%205.4A17.6%2017.6%200%200%200%200%2082.2c0%205%201.8%209.3%205.4%2012.9l128%20127.9c3.6%203.6%207.8%205.4%2012.8%205.4s9.2-1.8%2012.8-5.4L287%2095c3.5-3.5%205.4-7.8%205.4-12.8%200-5-1.9-9.2-5.5-12.8z%22/%3E%3C/svg%3E");
  background-repeat: no-repeat;
  background-position: right%200.3rem%20top%2050%;
  background-size: 0.5rem%20auto;
}

.reset-camera-btn {
  background: #111;
  border: 1px solid #333;
  color: #aaa;
  padding: 0.2rem 0.5rem;
  border-radius: 2px;
  font-size: 0.7rem;
  cursor: pointer;
  transition: all 0.2s;
}
.reset-camera-btn:hover:not(:disabled) {
  color: #ddd;
  border-color: #555;
}
.reset-camera-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.preset-btn {
  background: #111;
  border: 1px solid #333;
  color: #ddd;
  padding: 0.15rem 0.4rem;
  border-radius: 2px;
  font-size: 0.7rem;
  cursor: pointer;
  transition: all 0.2s;
}

.preset-btn.active {
  background: #42d392;
  border-color: #42d392;
  color: #062414;
}

.range-bound {
  font-size: 0.7rem;
  font-family: ui-monospace, monospace;
  color: #ddd;
  min-width: 2.5em;
  text-align: center;
}

.threshold-input {
  width: 60px;
  background: #111;
  border: 1px solid #333;
  color: #ddd;
  padding: 0.15rem 0.3rem;
  border-radius: 2px;
  font-size: 0.7rem;
  font-family: ui-monospace, monospace;
  text-align: right;
}
.threshold-input:focus {
  border-color: #42d392;
  outline: none;
}

.threshold-input::-webkit-outer-spin-button,
.threshold-input::-webkit-inner-spin-button {
  -webkit-appearance: none;
  margin: 0;
}

.threshold-input[type="number"] {
  -moz-appearance: textfield;
}

.render-distance-control {
  display: inline-flex;
  align-items: center;
  background: #101010;
  border-radius: 999px;
  overflow: hidden;
  border: 1px solid #333;
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

.render-distance-input[type="number"] {
  -moz-appearance: textfield;
}

.render-distance-btn {
  background: transparent;
  border: none;
  color: #bbb;
  padding: 0.1rem 0.4rem;
  font-size: 0.85rem;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
}

.render-distance-btn:hover {
  background: rgba(255, 255, 255, 0.06);
  color: #fff;
}

.threshold-reset-btn {
  background: transparent;
  border: none;
  color: #bbb;
  padding: 0;
  margin: 0;
  font-size: 0.95rem;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
}

.threshold-reset-btn:hover {
  background: rgba(255, 255, 255, 0.06);
  color: #fff;
}

input[type="range"] {
  width: 80px;
  height: 4px;
  background: #333;
  border-radius: 2px;
  appearance: none;
  outline: none;
}

input[type="range"]::-webkit-slider-thumb {
  appearance: none;
  width: 12px;
  height: 12px;
  background: #42d392;
  border-radius: 50%;
  cursor: pointer;
}

.status .error {
  color: #ff6b6b;
}

.content {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
}

.canvas-wrapper {
  position: relative;
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.density-toolbar {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.5rem;
  padding: 0.35rem 0.5rem;
  border-bottom: 1px solid #1e252c;
  background: rgba(8, 10, 12, 0.92);
  z-index: 8;
}

.density-toolbar-controls {
  flex: 1;
  flex-wrap: wrap;
  min-width: 0;
}

.density-toolbar-end {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  flex-shrink: 0;
}

.density-toolbar-hint {
  margin: 0;
  flex-shrink: 0;
  font-size: 0.65rem;
  color: #6a7580;
  pointer-events: none;
  white-space: nowrap;
}

.viewer-3d-header-actions {
  position: relative;
  display: flex;
  align-items: center;
  gap: 6px;
  z-index: 20;
}

.viewer-menu-btn {
  width: 32px;
  height: 32px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(0, 0, 0, 0.45);
  border: none;
  border-radius: 50%;
  color: #fff;
  cursor: pointer;
  font-size: 18px;
  line-height: 1;
  opacity: 0.9;
  transition: all 0.2s;
}
.viewer-menu-btn:hover {
  opacity: 1;
  background: rgba(255, 255, 255, 0.12);
}
.viewer-menu-icon {
  font-weight: bold;
  letter-spacing: -0.1em;
}

.viewer-settings-panel {
  position: absolute;
  top: calc(100% + 6px);
  right: 0;
  padding: 0.5rem 0.6rem;
  background: rgba(10, 12, 14, 0.97);
  border: 1px solid #333;
  border-radius: 6px;
  min-width: 180px;
  font-size: 0.75rem;
  color: #ccc;
  z-index: 1001;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.45);
}
.viewer-settings-checkbox {
  display: flex;
  align-items: center;
  gap: 0.4rem;
  margin-bottom: 0.4rem;
  cursor: pointer;
  color: #ccc;
}
.viewer-settings-checkbox input {
  margin: 0;
}
.viewer-settings-row {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin-bottom: 0.35rem;
}
.viewer-settings-row label {
  flex: 0 0 auto;
  color: #999;
}
.viewer-settings-row input {
  width: 4rem;
  padding: 0.2rem 0.3rem;
  background: #1a1a1a;
  border: 1px solid #444;
  border-radius: 3px;
  color: #eee;
  font-size: 0.75rem;
}
.viewer-settings-row input.out-of-range {
  border-color: #c33;
  box-shadow: 0 0 0 1px #c33;
}
.viewer-settings-hint {
  margin: 0;
  font-size: 0.65rem;
  color: #666;
  line-height: 1.3;
}

.close-btn {
  width: 32px;
  height: 32px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(0, 0, 0, 0.45);
  border-radius: 50%;
  font-size: 24px;
  color: #fff;
  text-decoration: none;
  line-height: 1;
  opacity: 0.9;
  transition: all 0.2s;
}
.close-btn:hover {
  opacity: 1;
  background: #ff6b6b;
  color: #fff;
}

.density-canvas-stack {
  position: relative;
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.canvas-container {
  width: 100%;
  flex: 1;
  min-height: 0;
}

.canvas-container :deep(canvas) {
  width: 100%;
  height: 100%;
  display: block;
}

.help-overlay {
  position: absolute;
  inset: 8px;
  z-index: 1100;
  display: flex;
  flex-direction: column;
  background: rgba(5, 8, 10, 0.97);
  border: 1px solid #2f3a44;
  border-radius: 10px;
  box-shadow: 0 12px 48px rgba(0, 0, 0, 0.55);
  overflow: hidden;
  pointer-events: auto;
}

.help-overlay-inner {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 1rem 1.25rem 1.25rem;
  display: flex;
  flex-direction: column;
  gap: 1rem;
}

.help-overlay-top {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  justify-content: space-between;
  gap: 0.5rem 1rem;
  padding-bottom: 0.75rem;
  border-bottom: 1px solid #2a333d;
}

.help-overlay-title {
  margin: 0;
  font-size: 1.05rem;
  font-weight: 700;
  color: #e8edf2;
  letter-spacing: 0.02em;
}

.help-overlay-close-hint {
  margin: 0;
  font-size: 0.75rem;
  color: #8a95a3;
}

.help-overlay-error {
  padding: 0.45rem 0.6rem;
  background: rgba(60, 20, 20, 0.6);
  border-radius: 6px;
  font-size: 0.75rem;
}

.help-overlay-body {
  font-size: 0.82rem;
  line-height: 1.55;
  color: #b8c4d0;
}
.help-overlay-body p {
  margin: 0 0 0.85rem;
}
.help-overlay-body p:last-child {
  margin-bottom: 0;
}
.help-overlay-body strong {
  color: #dce4ec;
  font-weight: 600;
}

.help-kbd {
  display: inline-block;
  padding: 0.08rem 0.35rem;
  margin: 0 0.1rem;
  font-family: ui-monospace, monospace;
  font-size: 0.72em;
  background: #1a2228;
  border: 1px solid #3a4550;
  border-radius: 4px;
  color: #c5d0d8;
}

</style>


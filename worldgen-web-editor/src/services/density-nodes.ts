import { nodeEditorBackendOrigin } from '@/services/node-editor-backend'

/** Pre-meshed data from backend (greedy mesher applied server-side). No density shading. */
export interface DensityMeshResponse {
  origin: { x: number; y: number; z: number };
  voxelSize: number;
  positions: Float32Array;
  normals: Float32Array;
  indices: Uint32Array;
}

export type DensityFieldParams = {
  width?: number;
  depth?: number;
  x?: number;
  z?: number;
  threshold?: number;
};

const DEFAULT_WIDTH = 128;
const DEFAULT_DEPTH = 128;
const DEFAULT_X = -64;
const DEFAULT_Z = -64;

/** Max total cache size in RAM (1 GB). Evict oldest entries by insertion order until under limit. */
const MAX_CACHE_BYTES = 1024 * 1024 * 1024;

/** In-memory cache: key -> { data, version, sizeBytes }. Eviction by total size (oldest first). */
const chunkCache = new Map<string, { data: DensityMeshResponse; version: number; sizeBytes: number }>();
let cacheTotalBytes = 0;

function estimateEntryBytes(data: DensityMeshResponse): number {
  return data.positions.byteLength + data.normals.byteLength + data.indices.byteLength;
}

/** Evict oldest entries until total size would be at most limitAfterAdd (or until cache empty). */
function evictUntilUnderLimit(limitAfterAdd: number): void {
  for (const key of chunkCache.keys()) {
    if (cacheTotalBytes <= limitAfterAdd) break;
    const entry = chunkCache.get(key);
    if (entry) {
      cacheTotalBytes -= entry.sizeBytes;
      chunkCache.delete(key);
    }
  }
}

/** Call when the server has new chunk/debug data (e.g. after chunk generation) so cached fields are refetched. */
export function invalidateDensityCache(): void {
  chunkCache.clear();
  cacheTotalBytes = 0;
}

function cacheKey(nodeId: string, params: DensityFieldParams | undefined): string {
  const w = params?.width ?? DEFAULT_WIDTH;
  const d = params?.depth ?? DEFAULT_DEPTH;
  const x = params?.x ?? DEFAULT_X;
  const z = params?.z ?? DEFAULT_Z;
  const t = params?.threshold ?? 0;
  return `${nodeId}|${w}|${d}|${x}|${z}|${t}`;
}

/** Parse binary mesh response (originX,Y,Z, voxelSize, numVertices, numIndices, positions, normals, indices). */
function parseBinaryMesh(buffer: ArrayBuffer): DensityMeshResponse {
  const view = new DataView(buffer);
  let offset = 0;
  const originX = view.getFloat32(offset, true); offset += 4;
  const originY = view.getFloat32(offset, true); offset += 4;
  const originZ = view.getFloat32(offset, true); offset += 4;
  const voxelSize = view.getFloat32(offset, true); offset += 4;
  const numVertices = view.getInt32(offset, true); offset += 4;
  const numIndices = view.getInt32(offset, true); offset += 4;
  const positions = new Float32Array(buffer, offset, numVertices * 3); offset += numVertices * 3 * 4;
  const normals = new Float32Array(buffer, offset, numVertices * 3); offset += numVertices * 3 * 4;
  const indices = new Uint32Array(buffer, offset, numIndices);
  return {
    origin: { x: originX, y: originY, z: originZ },
    voxelSize,
    positions,
    normals,
    indices,
  };
}

/**
 * Fetches pre-meshed density (greedy mesher applied on backend). Use for 3D view to avoid client-side meshing.
 */
export async function fetchDensityMesh(
  nodeId: string,
  params?: DensityFieldParams
): Promise<DensityMeshResponse> {
  const key = cacheKey(nodeId, params);
  const cached = chunkCache.get(key);
  if (cached != null) {
    chunkCache.delete(key);
    chunkCache.set(key, cached);
    return cached.data as DensityMeshResponse;
  }

  const url = new URL(`${nodeEditorBackendOrigin()}/density-nodes/${encodeURIComponent(nodeId)}/field`);
  if (params) {
    if (params.width !== undefined) url.searchParams.set('width', params.width.toString());
    if (params.depth !== undefined) url.searchParams.set('depth', params.depth.toString());
    if (params.x !== undefined) url.searchParams.set('x', params.x.toString());
    if (params.z !== undefined) url.searchParams.set('z', params.z.toString());
    if (params.threshold !== undefined) url.searchParams.set('threshold', params.threshold.toString());
  }
  url.searchParams.set('format', 'mesh');

  const reqId = Math.random().toString(36).slice(2, 10);
  const tFetchStart = performance.now();
  if (process.env.NODE_ENV !== 'production') {
    console.log(`[density-nodes] [${reqId}] mesh ${nodeId} fetch start`);
  }
  const res = await fetch(url.toString());
  const ttfbMs = Math.round(performance.now() - tFetchStart);
  if (process.env.NODE_ENV !== 'production') {
    console.log(`[density-nodes] [${reqId}] mesh ${nodeId} TTFB ${ttfbMs} ms`);
  }
  if (!res.ok) {
    const text = await res.text().catch(() => '');
    throw new Error(`Failed to fetch density mesh (${res.status}): ${text || res.statusText}`);
  }

  const versionHeader = res.headers.get('X-Density-Version');
  const version = versionHeader != null ? parseInt(versionHeader, 10) : 0;
  const tBodyStart = performance.now();
  const buffer = await res.arrayBuffer();
  if (process.env.NODE_ENV !== 'production') {
    console.log(`[density-nodes] [${reqId}] mesh ${nodeId} body ${(buffer.byteLength / 1024).toFixed(1)} KB in ${Math.round(performance.now() - tBodyStart)} ms, total ${Math.round(performance.now() - tFetchStart)} ms`);
  }
  const data = parseBinaryMesh(buffer);
  const sizeBytes = estimateEntryBytes(data);
  if (sizeBytes <= MAX_CACHE_BYTES) {
    evictUntilUnderLimit(MAX_CACHE_BYTES - sizeBytes);
    cacheTotalBytes += sizeBytes;
    chunkCache.set(key, { data, version, sizeBytes });
  }
  return data;
}

export interface DensityTraceStep {
  index: number;
  nodeId: string;
  nodeType: string;
  value: number;
  /** Present when this step's evaluation context had a non-null density anchor different from the previous traced step (plugin observes Context.densityAnchor; no engine changes). */
  anchorSet?: { x: number; y: number; z: number };
}

export interface DensityTraceResponse {
  x: number;
  y: number;
  z: number;
  finalValue: number;
  /** Root evaluated density class (same naming as each step's `nodeType`). */
  outputNodeType?: string;
  steps: DensityTraceStep[];
}

/** Same shape as SSE density metric entries; from `GET /debug/density-snapshot`. */
export interface DensityDebugSnapshot {
  version: number;
  registeredNodeIds: string[];
  nodeMetrics: Array<{ id: string; min: number; max: number }>;
}

/** Pull current debug min/max metrics (and version) after connect or reload. */
export async function fetchDensityDebugSnapshot(): Promise<DensityDebugSnapshot | null> {
  try {
    const res = await fetch(`${nodeEditorBackendOrigin()}/debug/density-snapshot`);
    if (!res.ok) return null;
    return (await res.json()) as DensityDebugSnapshot;
  } catch {
    return null;
  }
}

/** Single-point evaluation trace: each wrapped density node contributes one step (evaluation order). */
export async function fetchDensityTrace(
  nodeId: string,
  x: number,
  y: number,
  z: number
): Promise<DensityTraceResponse> {
  const url = new URL(`${nodeEditorBackendOrigin()}/density-nodes/${encodeURIComponent(nodeId)}/trace`);
  url.searchParams.set('x', String(x));
  url.searchParams.set('y', String(y));
  url.searchParams.set('z', String(z));
  const res = await fetch(url.toString());
  if (!res.ok) {
    const text = await res.text().catch(() => '');
    throw new Error(`Density trace failed (${res.status}): ${text || res.statusText}`);
  }
  return res.json() as Promise<DensityTraceResponse>;
}


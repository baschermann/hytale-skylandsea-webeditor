import type { Node, Edge } from '@vue-flow/core'
import type { NodeDefinition } from './node-loader'

function vueFlowEdgeKey(e: Pick<Edge, 'source' | 'target' | 'sourceHandle' | 'targetHandle'>) {
  return `${e.source}|${e.sourceHandle ?? ''}|${e.target}|${e.targetHandle ?? ''}`
}

/**
 * Vue Flow sometimes persists edges without `sourceHandle`. `serializeGraph` only
 * attaches children when `sourceHandle` matches the schema pin (`Entries`, etc.),
 * so missing handles drop all but one child — Weighted.Prop then saves a single
 * `Entries` branch and generation always picks one variant.
 */
function inferMissingSourceHandle(e: Edge, nodeMap: Map<string, Node>): Edge {
  const sh = e.sourceHandle
  if (sh != null && sh !== '') return e
  const src = nodeMap.get(e.source)
  if (!src || !isHytaleFlowNode(src)) return e
  const outs = (src.data as { definition: NodeDefinition }).definition.Outputs
  if (outs.length === 1) {
    return { ...e, sourceHandle: outs[0].Id }
  }
  const multi = outs.filter((o) => o.Multiple === true)
  if (multi.length === 1) {
    return { ...e, sourceHandle: multi[0].Id }
  }
  return e
}

/**
 * Editor-only metadata under `$NodeEditorMetadata` (ignored by the game runtime):
 * - `$Nodes[id].$Position` — canvas layout
 * - `$Nodes[id].$DisplayTitle` — user override for the node header (optional)
 * - `$Nodes[id].$Description` — free-form note for that node instance (optional)
 * - `$Groups[]` — labeled layout frames (`$Position`, `$width`, `$height`, `$name`; optional `$locked: true` = carry overlapping nodes when dragging the frame; omitted = unlocked)
 * - `$Comments[]` — free-form comment blocks (official editor); loaded as `editorComment` nodes and serialized back
 * - `$Links`, `$WorkspaceID` — preserved round-trip when present
 * - `$GraphEdges[]` — extra edges not implied by nested JSON
 */
function mergePersistedVueFlowEdges(nodes: Node[], edges: Edge[], metadata: Record<string, unknown>) {
  const raw = metadata.$GraphEdges
  if (!Array.isArray(raw) || raw.length === 0) return

  const ids = new Set(nodes.map((n) => n.id))
  const nodeMap = new Map(nodes.map((n) => [n.id, n]))
  const seen = new Set(edges.map((e) => vueFlowEdgeKey(e)))

  for (const row of raw) {
    if (!row || typeof row !== 'object') continue
    const o = row as Record<string, unknown>
    const source = typeof o.source === 'string' ? o.source : ''
    const target = typeof o.target === 'string' ? o.target : ''
    if (!source || !target || !ids.has(source) || !ids.has(target)) continue

    const sh = o.sourceHandle
    const th = o.targetHandle
    const sourceHandle = typeof sh === 'string' && sh.length > 0 ? sh : undefined
    const targetHandle = typeof th === 'string' && th.length > 0 ? th : undefined

    let e: Edge = {
      id: `e-persist-${source}-${sourceHandle ?? ''}-${target}-${targetHandle ?? ''}`,
      source,
      sourceHandle,
      target,
      targetHandle,
    }
    e = inferMissingSourceHandle(e, nodeMap)
    const k = vueFlowEdgeKey(e)
    if (seen.has(k)) continue
    seen.add(k)
    edges.push(e)
  }
}

/** Round-trip fields from loaded JSON (not rebuilt from the vue-flow graph). */
export interface GraphMetadataPassthrough {
  $WorkspaceID?: string
  $Links?: Record<string, unknown>
}

export interface GraphData {
  nodes: Node[]
  edges: Edge[]
  metadataPassthrough: GraphMetadataPassthrough
}

interface HytaleNode {
  $NodeId?: string;
  Type?: string;
  [key: string]: unknown;
}

interface NodeMetadata {
  $Position?: { $x: number; $y: number };
  /** @deprecated Prefer `$DisplayTitle`. Older saves used this; ignored for display if `$DisplayTitle` is absent. */
  $Title?: string;
  /** User-defined label shown in the node header instead of the template title. */
  $DisplayTitle?: string;
  /** User note for this node instance (editor-only). */
  $Description?: string;
}

/** Official editor layout group (e.g. “Large Trees” frames). */
interface GroupEditorJson {
  $Position?: { $x: number; $y: number };
  $width?: number;
  $height?: number;
  $name?: string;
  /** Web editor: when `true`, dragging the frame moves overlapping nodes; omitted or `false` = frame moves alone. */
  $locked?: boolean;
}

interface LegacyStickyNoteJson {
  $id?: string;
  Text?: string;
  $Position?: { $x: number; $y: number };
  Width?: number;
  Height?: number;
}

/** Official `$NodeEditorMetadata.$Comments[]` entry (ScriptedBrush, HytaleGenerator, etc.). */
interface NodeEditorCommentJson {
  $Position?: { $x: number; $y: number };
  $width?: number;
  $height?: number;
  $name?: string;
  $text?: string;
  $fontSize?: number;
}

interface GraphJson {
  $NodeId?: string;
  $NodeEditorMetadata?: {
    $Nodes?: Record<string, NodeMetadata>;
    $FloatingNodes?: HytaleNode[];
    $Groups?: GroupEditorJson[];
    $StickyNotes?: LegacyStickyNoteJson[];
    $Comments?: unknown[];
    $Links?: Record<string, unknown>;
    $WorkspaceID?: string;
    /** Saved vue-flow edges (including cross-links not implied by nested JSON). */
    $GraphEdges?: Array<{
      source: string;
      target: string;
      sourceHandle?: string | null;
      targetHandle?: string | null;
    }>;
  };
  [key: string]: unknown;
}

function readMetadataPassthrough(metadata: Record<string, unknown>): GraphMetadataPassthrough {
  const out: GraphMetadataPassthrough = {}
  if (typeof metadata.$WorkspaceID === 'string') out.$WorkspaceID = metadata.$WorkspaceID
  if (
    metadata.$Links &&
    typeof metadata.$Links === 'object' &&
    !Array.isArray(metadata.$Links)
  ) {
    out.$Links = metadata.$Links as Record<string, unknown>
  }
  return out
}

function readGroupScalar(o: Record<string, unknown>, ...keys: string[]): number | null {
  for (const k of keys) {
    const v = o[k]
    if (typeof v === 'number' && Number.isFinite(v)) return v
  }
  return null
}

export function parseGraph(
  json: GraphJson | any[],
  nodeDefinitions: Record<string, NodeDefinition>
): GraphData {
  const nodes: Node[] = [];
  const edges: Edge[] = [];
  const processedIds = new Set<string>();

  const isArrayRoot = Array.isArray(json);
  const metadata = !isArrayRoot ? (json as GraphJson).$NodeEditorMetadata || {} : {};
  const nodeMetadata = metadata.$Nodes || {};
  const metadataPassthrough: GraphMetadataPassthrough = isArrayRoot
    ? {}
    : readMetadataPassthrough(metadata as Record<string, unknown>);

  function findDefinition(data: HytaleNode, nodeId: string): NodeDefinition | undefined {
    // 1. Try exact match on ID prefix
    const parts = nodeId.split('-');
    for (let i = parts.length - 1; i >= 1; i--) {
      const candidate = parts.slice(0, i).join('-');
      if (nodeDefinitions[candidate]) return nodeDefinitions[candidate];

      const dotCandidate = parts.slice(0, i).join('.');
      if (nodeDefinitions[dotCandidate]) return nodeDefinitions[dotCandidate];
    }

    // 2. Try Type match
    if (data.Type) {
      // Find exact Type match or partial match (e.g. "Constant" matching "Constant_Assignments")
      const defs = Object.values(nodeDefinitions);
      let def = defs.find(d => d.Schema.Type === data.Type || d.Id === data.Type);
      
      if (!def) {
        // Try partial match if it's a known pattern like Constant -> Constant_Assignments
        def = defs.find(d => d.Id.startsWith(data.Type + '_') || d.Id.endsWith('_' + data.Type));
      }
      
      if (def) return def;
    }

    // 3. Heuristic: check if keys match Schema keys (e.g. for Material which has no Type)
    const dataKeys = Object.keys(data).filter(k => !k.startsWith('$') && k !== 'Type');
    if (dataKeys.length > 0) {
      const def = Object.values(nodeDefinitions).find(d => {
        const schemaKeys = Object.keys(d.Schema);
        // Only consider it a match if it has at least 2 matching keys or is a very specific match
        return dataKeys.length >= 2 && dataKeys.every(k => schemaKeys.includes(k));
      });
      if (def) return def;
    }

    // 4. Try first part of ID as last resort
    const firstPart = parts[0];
    if (firstPart && nodeDefinitions[firstPart]) return nodeDefinitions[firstPart];

    return undefined;
  }

  // Create a fallback definition for unknown nodes so we don't break the graph
  function getFallbackDefinition(data: HytaleNode, id: string): NodeDefinition {
    return {
      Id: id.split('-')[0] || 'Unknown',
      Title: `[Unknown] ${data.Type || 'Node'}`,
      Color: 'Red',
      Content: Object.keys(data)
        .filter(k => !k.startsWith('$') && k !== 'Type' && typeof data[k] !== 'object')
        .map(k => ({ Id: k, Type: 'SmallString', Options: { Label: k } })),
      Inputs: [{ Id: 'Input', Type: 'Unknown' }],
      Outputs: [{ Id: 'Output', Type: 'Unknown', Multiple: true }],
      Schema: { Type: data.Type || 'Unknown' }
    };
  }

  function processNode(data: HytaleNode | unknown, parentId?: string, parentHandle?: string) {
    if (!data || typeof data !== 'object') return;

    const hytaleData = data as HytaleNode;
    let nodeId = hytaleData.$NodeId;
    
    if (!nodeId) {
      const type = hytaleData.Type || 'Unknown';
      nodeId = `${type}-${Math.random().toString(36).substr(2, 9)}`;
      hytaleData.$NodeId = nodeId; 
    }

    if (processedIds.has(nodeId)) {
      if (parentId && parentHandle) {
        createEdge(parentId, parentHandle, nodeId);
      }
      return;
    }

    let definition = findDefinition(hytaleData, nodeId);
    if (!definition) {
      console.warn(`Could not find definition for node ${nodeId}, using fallback.`, hytaleData);
      definition = getFallbackDefinition(hytaleData, nodeId);
    }

    processedIds.add(nodeId);

    const values: Record<string, string | number | boolean> = {};
    definition.Content.forEach(item => {
      const val = hytaleData[item.Id];
      if (val !== undefined && (typeof val === 'string' || typeof val === 'number' || typeof val === 'boolean')) {
        values[item.Id] = val;
      } else if (item.Options?.Default !== undefined) {
        values[item.Id] = item.Options.Default as string | number | boolean;
      }
    });

    const meta = nodeMetadata[nodeId] || {};
    let position = { x: Math.random() * 500, y: Math.random() * 500 }; // Random offset for skipped positions
    
    if (meta.$Position) {
      position = { x: meta.$Position.$x, y: meta.$Position.$y };
    } else if ((hytaleData as any).$Position) {
      const inlinePos = (hytaleData as any).$Position;
      position = { x: inlinePos.$x, y: inlinePos.$y };
    }

    const displayTitle =
      typeof meta.$DisplayTitle === 'string' && meta.$DisplayTitle.trim()
        ? meta.$DisplayTitle.trim()
        : undefined
    const description =
      typeof meta.$Description === 'string' ? meta.$Description : ''

    nodes.push({
      id: nodeId,
      type: 'custom',
      position,
      data: {
        definition,
        values,
        ...(displayTitle ? { displayTitle } : {}),
        ...(description ? { description } : {}),
      }
    });

    if (parentId && parentHandle) {
      createEdge(parentId, parentHandle, nodeId, definition);
    }

    // Process children based on schema pins
    for (const [key, schemaValue] of Object.entries(definition.Schema)) {
      if (schemaValue && typeof schemaValue === 'object' && (schemaValue as any).Pin) {
        const pinId = (schemaValue as any).Pin as string;
        const childData = hytaleData[key];

        if (Array.isArray(childData)) {
          childData.forEach(item => processNode(item, nodeId, pinId));
        } else if (childData && typeof childData === 'object') {
          processNode(childData, nodeId, pinId);
        }
      }
    }

    // Also check for any other object/array properties that might be nodes but aren't in Schema
    // (This helps with "Unknown" nodes or incomplete definitions)
    if (definition.Id === 'Unknown' || Object.keys(definition.Schema).length === 1) {
       Object.entries(hytaleData).forEach(([key, value]) => {
         if (key.startsWith('$') || key === 'Type') return;
         if (value && typeof value === 'object') {
           if (Array.isArray(value)) {
             value.forEach(item => processNode(item, nodeId, 'Output'));
           } else {
             processNode(value, nodeId, 'Output');
           }
         }
       });
    }
  }

  function createEdge(parentId: string, parentHandle: string, childId: string, childDef?: NodeDefinition) {
    const edgeId = `e-${parentId}-${parentHandle}-${childId}`;
    if (edges.some(e => e.id === edgeId)) return;

    let targetHandle = 'Output';

    if (childDef) {
      targetHandle = childDef.Inputs[0]?.Id || 'Output';
    }

    edges.push({
      id: edgeId,
      source: parentId,
      sourceHandle: parentHandle,
      target: childId,
      targetHandle: targetHandle
    });
  }

  if (isArrayRoot) {
    (json as any[]).forEach(item => processNode(item));
  } else {
    processNode(json);
  }

  if (metadata.$FloatingNodes) {
    metadata.$FloatingNodes.forEach((node) => processNode(node));
  }

  mergePersistedVueFlowEdges(nodes, edges, metadata as Record<string, unknown>);

  const groupsRaw = metadata.$Groups
  let groupSeq = 0
  if (Array.isArray(groupsRaw)) {
    for (const g of groupsRaw) {
      if (!g || typeof g !== 'object') continue
      const o = g as Record<string, unknown>
      const pos = o.$Position as { $x?: number; $y?: number } | undefined
      const px = typeof pos?.$x === 'number' ? pos.$x : 0
      const py = typeof pos?.$y === 'number' ? pos.$y : 0
      const w = readGroupScalar(o, '$width', 'width') ?? 560
      const h = readGroupScalar(o, '$height', 'height') ?? 240
      const nameRaw = o.$name ?? o.name
      const name = typeof nameRaw === 'string' ? nameRaw : 'Group'
      const locked = o.$locked === true
      nodes.push({
        id: `grp-${groupSeq}-${Math.random().toString(36).slice(2, 9)}`,
        type: 'editorGroup',
        position: { x: px, y: py },
        zIndex: -8,
        style: { width: w, height: h },
        data: { name, locked },
      })
      groupSeq += 1
    }
  }

  /** Older web-editor saves only; converted to `$Groups` on next save. */
  const stickyRaw = metadata.$StickyNotes
  if (Array.isArray(stickyRaw)) {
    for (const sn of stickyRaw) {
      if (!sn || typeof sn !== 'object') continue
      const o = sn as LegacyStickyNoteJson
      const px =
        o.$Position && typeof o.$Position.$x === 'number'
          ? o.$Position.$x
          : 80
      const py =
        o.$Position && typeof o.$Position.$y === 'number'
          ? o.$Position.$y
          : 80
      const w = typeof o.Width === 'number' && o.Width > 80 ? o.Width : 240
      const h = typeof o.Height === 'number' && o.Height > 60 ? o.Height : 140
      const text = typeof o.Text === 'string' ? o.Text.trim() : ''
      const name = text ? text.split(/\r?\n/)[0]!.slice(0, 120) : 'Note'
      nodes.push({
        id: `grp-${groupSeq}-${Math.random().toString(36).slice(2, 9)}`,
        type: 'editorGroup',
        position: { x: px, y: py },
        zIndex: -8,
        style: { width: w, height: h },
        data: { name, locked: false },
      })
      groupSeq += 1
    }
  }

  const commentsRaw = metadata.$Comments
  let commentSeq = 0
  if (Array.isArray(commentsRaw)) {
    for (const c of commentsRaw) {
      if (!c || typeof c !== 'object') continue
      const o = c as Record<string, unknown>
      const pos = o.$Position as { $x?: number; $y?: number } | undefined
      const px = typeof pos?.$x === 'number' ? pos.$x : 80
      const py = typeof pos?.$y === 'number' ? pos.$y : 80
      const w = readGroupScalar(o, '$width', 'width') ?? 400
      const h = readGroupScalar(o, '$height', 'height') ?? 160
      const nameRaw = o.$name ?? o.name
      const blockName = typeof nameRaw === 'string' && nameRaw.trim() ? nameRaw.trim() : 'Comment'
      const textRaw = o.$text ?? o.text
      const text = typeof textRaw === 'string' ? textRaw : ''
      const fsRaw = o.$fontSize ?? o.fontSize
      const fontSize =
        typeof fsRaw === 'number' && Number.isFinite(fsRaw)
          ? Math.min(96, Math.max(8, Math.round(fsRaw)))
          : 21
      nodes.push({
        id: `cmt-${commentSeq++}-${Math.random().toString(36).slice(2, 10)}`,
        type: 'editorComment',
        position: { x: px, y: py },
        zIndex: -6,
        style: { width: w, height: h },
        data: {
          blockName,
          text,
          fontSize,
        },
      })
    }
  }

  return { nodes, edges, metadataPassthrough };
}

function isHytaleFlowNode(n: Node): boolean {
  return n.type === 'custom' || n.type == null
}

function styleDimToNumber(v: unknown, fallback: number): number {
  if (typeof v === 'number' && Number.isFinite(v) && v > 0) return v
  if (typeof v === 'string') {
    const n = parseFloat(v)
    if (Number.isFinite(n) && n > 0) return n
  }
  return fallback
}

function sortEditorGroupNodes(nodes: Node[]): Node[] {
  return nodes
    .filter((n) => n.type === 'editorGroup')
    .sort((a, b) => {
      const dy = a.position.y - b.position.y
      if (dy !== 0) return dy
      const dx = a.position.x - b.position.x
      if (dx !== 0) return dx
      const na = String((a.data as { name?: string }).name ?? '')
      const nb = String((b.data as { name?: string }).name ?? '')
      return na.localeCompare(nb)
    })
}

function sortEditorCommentNodes(nodes: Node[]): Node[] {
  return nodes
    .filter((n) => n.type === 'editorComment')
    .sort((a, b) => {
      const dy = a.position.y - b.position.y
      if (dy !== 0) return dy
      const dx = a.position.x - b.position.x
      if (dx !== 0) return dx
      return a.id.localeCompare(b.id)
    })
}

export function serializeGraph(
  nodes: Node[],
  edges: Edge[],
  metadataPassthrough?: GraphMetadataPassthrough,
): any {
  const nodeMap = new Map<string, Node>();
  nodes.forEach(n => nodeMap.set(n.id, n));

  const hytaleNodes = nodes.filter(isHytaleFlowNode)
  const hytaleIds = new Set(hytaleNodes.map(n => n.id))
  const hytaleEdgesRaw = edges.filter(e => hytaleIds.has(e.source) && hytaleIds.has(e.target))
  const hytaleEdges = hytaleEdgesRaw.map((e) => inferMissingSourceHandle(e, nodeMap))
  const incomingFromHytale = new Set(hytaleEdges.map(e => e.target))
  const roots = hytaleNodes.filter(n => !incomingFromHytale.has(n.id))

  const processedIds = new Set<string>();
  const nodeEditorMetadata: any = {
    $WorkspaceID: metadataPassthrough?.$WorkspaceID ?? 'HytaleGenerator',
    $Comments: sortEditorCommentNodes(nodes).map((n) => {
      const d = n.data as {
        blockName?: string
        text?: string
        fontSize?: number
      }
      const st = n.style as Record<string, unknown> | undefined
      const row: NodeEditorCommentJson = {
        $Position: {
          $x: n.position.x,
          $y: n.position.y,
        },
        $width: styleDimToNumber(st?.width, 400),
        $height: styleDimToNumber(st?.height, 160),
        $name: typeof d?.blockName === 'string' && d.blockName.trim() ? d.blockName.trim() : 'Comment',
        $text: typeof d?.text === 'string' ? d.text : '',
      }
      const fs =
        typeof d?.fontSize === 'number' && Number.isFinite(d.fontSize)
          ? Math.min(96, Math.max(8, Math.round(d.fontSize)))
          : 21
      row.$fontSize = fs
      return row
    }),
    $Links:
      metadataPassthrough?.$Links &&
      typeof metadataPassthrough.$Links === 'object' &&
      !Array.isArray(metadataPassthrough.$Links)
        ? metadataPassthrough.$Links
        : {},
    $Nodes: {},
    $FloatingNodes: [],
    $Groups: [],
  };

  function serializeNode(nodeId: string): any {
    const node = nodeMap.get(nodeId);
    if (!node || !isHytaleFlowNode(node)) return null;

    const data = node.data as {
      definition: NodeDefinition
      values: Record<string, any>
      displayTitle?: string
      description?: string
    };
    const result: any = {
      $NodeId: node.id,
      Type: data.definition.Schema.Type || data.definition.Id,
    };

    // Add values
    Object.entries(data.values).forEach(([key, value]) => {
      if (value !== undefined && value !== '') {
        result[key] = value;
      }
    });

    // Add children via pins
    data.definition.Schema && Object.entries(data.definition.Schema).forEach(([key, schemaValue]: [string, any]) => {
      if (schemaValue && typeof schemaValue === 'object' && schemaValue.Pin) {
        const pinId = schemaValue.Pin;
        const pinDef = data.definition.Outputs.find(o => o.Id === pinId) || data.definition.Inputs.find(i => i.Id === pinId);
        const isMultiple = pinDef?.Multiple === true;

        const connectedEdges = hytaleEdges.filter(e => e.source === nodeId && e.sourceHandle === pinId);

        const sortedForPin = [...connectedEdges].sort((a, b) => {
          const na = nodeMap.get(a.target)
          const nb = nodeMap.get(b.target)
          const ay = na?.position.y ?? 0
          const by = nb?.position.y ?? 0
          if (ay !== by) return ay - by
          const ax = na?.position.x ?? 0
          const bx = nb?.position.x ?? 0
          if (ax !== bx) return ax - bx
          return a.target.localeCompare(b.target)
        })

        if (sortedForPin.length > 0) {
          const children = sortedForPin
            .map(e => serializeNode(e.target))
            .filter(Boolean);
          if (children.length > 0) {
            if (isMultiple) {
               result[key] = children;
            } else {
               result[key] = children[0];
            }
          }
        } else if (isMultiple) {
          result[key] = []; // Ensure empty array for multiple pins
        }
      }
    });

    // Editor-only layout + labels (game ignores $NodeEditorMetadata)
    const meta: Record<string, unknown> = {
      $Position: {
        $x: Math.round(node.position.x),
        $y: Math.round(node.position.y)
      },
    }
    const defTitle = data.definition.Title
    const disp =
      typeof data.displayTitle === 'string' && data.displayTitle.trim()
        ? data.displayTitle.trim()
        : ''
    if (disp && disp !== defTitle) meta.$DisplayTitle = disp
    const desc =
      typeof data.description === 'string' && data.description.trim()
        ? data.description.trim()
        : ''
    if (desc) meta.$Description = desc
    nodeEditorMetadata.$Nodes[node.id] = meta

    processedIds.add(nodeId);
    return result;
  }

  let mainRoot = roots.find(n => n.id.startsWith('RootNode') || n.id.startsWith('Biome')) || roots[0];
  
  const rootJson = mainRoot ? serializeNode(mainRoot.id) : {};

  // Add floating Hygraph nodes to metadata (array order matches layout: top = smaller y first)
  const floatingIds = hytaleNodes.filter(n => !processedIds.has(n.id)).map(n => n.id)
  floatingIds.sort((a, b) => {
    const na = nodeMap.get(a)!
    const nb = nodeMap.get(b)!
    const dy = na.position.y - nb.position.y
    if (dy !== 0) return dy
    const dx = na.position.x - nb.position.x
    if (dx !== 0) return dx
    return a.localeCompare(b)
  })
  for (const id of floatingIds) {
    nodeEditorMetadata.$FloatingNodes.push(serializeNode(id))
  }

  nodeEditorMetadata.$Groups = sortEditorGroupNodes(nodes).map((n) => {
    const d = n.data as { name?: string; locked?: boolean }
    const st = n.style as Record<string, unknown> | undefined
    const row: Record<string, unknown> = {
      $Position: {
        $x: n.position.x,
        $y: n.position.y,
      },
      $width: styleDimToNumber(st?.width, 560),
      $height: styleDimToNumber(st?.height, 240),
      $name: typeof d?.name === 'string' ? d.name : '',
    }
    if (d?.locked === true) row.$locked = true
    return row
  })

  nodeEditorMetadata.$GraphEdges = hytaleEdges.map((e) => ({
    source: e.source,
    target: e.target,
    sourceHandle: e.sourceHandle ?? null,
    targetHandle: e.targetHandle ?? null,
  }))

  rootJson.$NodeEditorMetadata = nodeEditorMetadata;

  return rootJson;
}

export interface NodeContentOptions {
  Label?: string;
  Default?: string | number | boolean;
  Width?: number;
  Min?: number;
  Max?: number;
  TickFrequency?: number;
}

export interface NodeContent {
  Id: string;
  Type: string;
  Options?: NodeContentOptions;
}

export interface NodeConnection {
  Id: string;
  Type: string;
  Color?: string;
  Label?: string;
  Multiple?: boolean;
}

export interface NodeDefinition {
  Id: string;
  Title: string;
  Color: string;
  Category?: string;
  Content: NodeContent[];
  Outputs: NodeConnection[];
  Inputs: NodeConnection[];
  Schema: Record<string, unknown>;
}

export interface Workspace {
  WorkspaceName: string;
  ExportDefaults: boolean;
  Roots: Record<string, { RootNodeType: string; MenuName: string }>;
  NodeCategories: Record<string, string[]>;
  Variants: Record<string, unknown>;
}

export async function loadNodes() {
  const nodeFiles = import.meta.glob('@/assets/nodes/**/*.json');
  const nodes: Record<string, NodeDefinition> = {};
  let workspace: Workspace | null = null;

  // First pass: load all nodes and the workspace
  for (const [path, loader] of Object.entries(nodeFiles)) {
    try {
      const module = await loader() as { default: NodeDefinition | Workspace };
      const data = module.default;

      if (path.endsWith('_Workspace.json')) {
        workspace = data as Workspace;
      } else if (data && typeof data === 'object' && 'Id' in data && 'Title' in data) {
        const def = data as NodeDefinition;
        nodes[def.Id] = def;
      }
    } catch (e) {
      console.error(`Failed to load node at ${path}:`, e);
    }
  }

  // Second pass: assign categories to nodes
  if (workspace) {
    for (const [categoryName, nodeIds] of Object.entries(workspace.NodeCategories)) {
      nodeIds.forEach(id => {
        // Try exact match
        if (nodes[id]) {
          nodes[id].Category = categoryName;
        } else {
          // Try fuzzy match (dots vs underscores in filenames/IDs)
          const normalizedId = id.replace(/\./g, '_');
          const foundKey = Object.keys(nodes).find(k => k === id || k.replace(/\./g, '_') === normalizedId);
          if (foundKey) {
            nodes[foundKey].Category = categoryName;
          }
        }
      });
    }
  }

  // Final pass: safety check for specific nodes that might be missing category
  Object.values(nodes).forEach(node => {
    if (!node.Category) {
      if (node.Id.includes('.Assignments') || node.Id.includes('_Assignments')) {
        node.Category = 'Assignments';
      } else if (node.Id.includes('.Curve') || node.Id.includes('Curve')) {
        node.Category = 'Curves';
      } else if (node.Id.includes('Density')) {
        node.Category = 'Density Nodes';
      }
    }
  });

  return { nodes, workspace };
}

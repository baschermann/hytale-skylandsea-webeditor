// Ultra-dark professional palette for maximum header contrast
const categoryColors: Record<string, string> = {
  'Density Nodes': '#2d248a',      // Darker Purple
  'Biome': '#0a634a',              // Darker Teal
  'MaterialProvider': '#8a3400',   // Darker Rust
  'Assignments': '#1a5276',        // Darker Blue
  'Curves': '#6c3483',             // Darker Amethyst
  'Positions': '#935116',          // Darker Orange/Brown
  'Material': '#9a7d0a',           // Darker Gold/Yellow
  'Props': '#186a3b',              // Darker Green
  'Patterns': '#4a235a',           // Darker Lavender
  'Scanners': '#7b241c',           // Darker Red
  'VectorProviders': '#0e6251',    // Darker Cyan
  'Points': '#7d6608',             // Muted Yellow
  'Bounds': '#7d6608',             // Muted Yellow
  'Range': '#7d6608',              // Muted Yellow
  'MergingFunctions': '#4d5656',   // Darker Grey
  'EnvironmentProvider': '#641e16', // Deep Maroon
  'TintProvider': '#1b2631',       // Deep Charcoal
  'BlockMask': '#17202a',          // Near Black
  'Directionality': '#7d6608',     // Muted Yellow
  'PointGenerator': '#935116',     // Darker Orange
  'SpaceAndDepth MaterialProvider': '#8a3400', // Darker Rust
  'PositionsCellNoise Nodes': '#2d248a', // Darker Purple
};

const handleColors: Record<string, string> = {
  'DensityConnection': '#2d248a',
  'MaterialProviderConnection': '#8a3400',
  'AssignmentsConnection': '#1a5276',
  'Terrain.Connection': '#0a634a',
  'PropConnection': '#186a3b',
  'Scanner.Connection': '#7b241c',
  'VectorProvider.Connection': '#0e6251',
  'CurveVariants': '#6c3483',
  'ManualCurve': '#6c3483',
  'CurveConnection': '#6c3483',
  'Point3D': '#7d6608',
  'Point3D.Connection': '#7d6608',
  'DecimalBounds3d': '#7d6608',
  'Decimal.Range': '#7d6608',
  'Integer.Range': '#7d6608',
  'Biome': '#0a634a',
  'Material': '#9a7d0a',
  'WeightedMaterialConnection': '#9a7d0a',
  'RuntimeConnection': '#186a3b',
  'EnvironmentProvider.Connection': '#641e16',
  'TintProvider.Connection': '#1b2631',
  'BlockMask.Connection': '#17202a',
  'Weight.Weighted.Assignments.Connection': '#1a5276',
  'Delimiter.FieldFunction.Assignments.Connection': '#1a5276',
  'Delimiter.Sandwich.Assignments.Connection': '#1a5276',
  'FunctionForYConnection': '#935116'
};

export function getCategoryColor(category?: string, nodeId?: string): string {
  if (category && categoryColors[category]) {
    return categoryColors[category];
  }
  
  if (nodeId) {
    if (nodeId.includes('Assignments') || nodeId.includes('Weight')) return categoryColors['Assignments'];
    if (nodeId.includes('Density')) return categoryColors['Density Nodes'];
    if (nodeId.includes('Biome') || nodeId.includes('Terrain')) return categoryColors['Biome'];
    if (nodeId.includes('Curve')) return categoryColors['Curves'];
    if (nodeId.includes('MaterialProvider')) return categoryColors['MaterialProvider'];
  }

  return '#333';
}

export function getHandleColor(type?: string, nodeCategory?: string): string {
  if (!type) return getCategoryColor(nodeCategory);
  if (handleColors[type]) return handleColors[type];
  for (const [key, color] of Object.entries(handleColors)) {
    if (type.includes(key) || key.includes(type)) return color;
  }
  return getCategoryColor(nodeCategory);
}

export function parseColor(color: string | undefined): string {
  return color || '#333';
}

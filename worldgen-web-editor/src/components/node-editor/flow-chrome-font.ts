/**
 * Font size in flow (node-local CSS px) so text stays near a target *screen* size as the viewport zoom changes.
 * Vue Flow scales the whole node; compensating 1/zoom keeps labels readable when zoomed out without growing unbounded when zoomed in.
 */
export function flowFontPxForScreen(
  zoom: number,
  targetScreenPx: number,
  opts?: { minFlow?: number; maxFlow?: number; zMin?: number; zMax?: number },
): number {
  const minFlow = opts?.minFlow ?? 10
  const maxFlow = opts?.maxFlow ?? 46
  const zMin = opts?.zMin ?? 0.12
  const zMax = opts?.zMax ?? 6.5
  const z = Math.max(zMin, Math.min(zoom || 1, zMax))
  return Math.min(maxFlow, Math.max(minFlow, targetScreenPx / z))
}

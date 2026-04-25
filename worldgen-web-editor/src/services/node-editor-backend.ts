/** Tomcat node-editor / worldgen debug API port — must match Webserver.java in ShiftingWeald. */
export const NODE_EDITOR_BACKEND_PORT = 15009

/** Backend origin for API/SSE: same host as the page, fixed debug port (Vite dev + LAN). */
export function nodeEditorBackendOrigin(): string {
  if (typeof window !== 'undefined' && window.location?.hostname) {
    const { protocol, hostname } = window.location
    return `${protocol}//${hostname}:${NODE_EDITOR_BACKEND_PORT}`
  }
  return `http://127.0.0.1:${NODE_EDITOR_BACKEND_PORT}`
}

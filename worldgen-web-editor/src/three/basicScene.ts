import * as THREE from 'three';

/** Movement speed in world units per second (base; shift multiplies). */
const MOVEMENT_SPEED_MIN = 5;
const MOVEMENT_SPEED_MAX = 400;
const MOVEMENT_SPEED_DEFAULT = 40;
const MOVEMENT_SPEED_WHEEL_FACTOR = 1.15;

/** Far clip for density 3D view only (large sampled volumes + free flight need a deep frustum). */
const CAMERA_FAR = 100_000;

export interface BasicScene {
  scene: THREE.Scene;
  camera: THREE.PerspectiveCamera;
  renderer: THREE.WebGLRenderer;
  setTarget: (target: THREE.Vector3) => void;
  /**
   * Updates input-driven movement/rotation.
   * @param deltaMs Time since last update in milliseconds (used for framerate-independent movement).
   * Returns true if the camera moved or rotated since the last call.
   */
  update: (deltaMs: number) => boolean;
  getMovementSpeed: () => number;
  /** Set resolution scale (1 = full, 0.5 = half). Used for adaptive quality during movement. */
  setResolutionScale: (scale: number) => void;
  dispose: () => void;
}

export interface BasicSceneOptions {
  /** Called when input or state requires a redraw; use to schedule a single frame instead of a continuous loop. */
  onRequestFrame?: () => void;
}

export function createBasicScene(container: HTMLElement, options?: BasicSceneOptions): BasicScene {
  const onRequestFrame = options?.onRequestFrame;
  let width = container.clientWidth || container.offsetWidth || 800;
  let height = container.clientHeight || container.offsetHeight || 600;

  const scene = new THREE.Scene();
  scene.background = new THREE.Color(0x050608);

  const camera = new THREE.PerspectiveCamera(60, width / height, 0.1, CAMERA_FAR);
  camera.rotation.order = 'YXZ'; // Important for FPS style rotation
  camera.position.set(40, 40, 40);
  
  // We keep a local target reference just for compatibility, but we don't orbit it anymore
  const target = new THREE.Vector3(0, 0, 0);
  camera.lookAt(target);

  const renderer = new THREE.WebGLRenderer({
    antialias: true,
    preserveDrawingBuffer: true,
  });
  let resolutionScale = 1.0;
  const fullPixelRatio = Math.min(typeof window !== 'undefined' ? window.devicePixelRatio : 1, 2);

  const applySize = () => {
    camera.aspect = width / height;
    camera.updateProjectionMatrix();
    const canvas = renderer.domElement;
    if (resolutionScale >= 1) {
      renderer.setSize(width, height);
      renderer.setPixelRatio(fullPixelRatio);
      canvas.style.width = '';
      canvas.style.height = '';
    } else {
      const w = Math.max(1, Math.floor(width * resolutionScale));
      const h = Math.max(1, Math.floor(height * resolutionScale));
      renderer.setSize(w, h);
      renderer.setPixelRatio(1);
      canvas.style.width = width + 'px';
      canvas.style.height = height + 'px';
    }
  };
  applySize();

  container.appendChild(renderer.domElement);
  container.tabIndex = -1;
  container.style.outline = 'none';

  // Lights for MeshLambertMaterial (same setup as the working lighting version)
  const ambient = new THREE.AmbientLight(0xffffff, 0.6);
  scene.add(ambient);
  const dir = new THREE.DirectionalLight(0xffffff, 0.8);
  dir.position.set(30, 50, 30);
  scene.add(dir);

  // Interaction state
  let movementSpeedUnitsPerSec = MOVEMENT_SPEED_DEFAULT;

  const state = {
    isLeftDragging: false,
    isRightDragging: false,
    isPointerLocked: false,
    lastMouseX: 0,
    lastMouseY: 0,
    keys: {
        w: false, a: false, s: false, d: false,
        shift: false, space: false, c: false,
        /**
         * Alt for descend (Space up / Alt down). Browsers do not allow pages to cancel
         * Ctrl+W / Ctrl+S / etc., so Ctrl cannot be used with WASD in a normal tab.
         */
        altFly: false,
    }
  };

  const clearMovementKeys = () => {
    state.keys.w = false;
    state.keys.a = false;
    state.keys.s = false;
    state.keys.d = false;
    state.keys.space = false;
    state.keys.c = false;
    state.keys.altFly = false;
    state.keys.shift = false;
  };

  const canvas = renderer.domElement;

  /** True when keyboard should drive flight: pointer locked on canvas, or user focused the view by clicking it. */
  const isKeyboardFlightActive = (): boolean => {
    if (document.pointerLockElement === canvas) return true;
    const el = document.activeElement;
    return el === container || el === canvas;
  };

  const onMouseDown = (event: MouseEvent) => {
    container.focus({ preventScroll: true });
    if (event.button === 0) {
        state.isLeftDragging = true;
        if (!state.isPointerLocked) canvas.requestPointerLock();
    }
    if (event.button === 2) state.isRightDragging = true;
    state.lastMouseX = event.clientX;
    state.lastMouseY = event.clientY;
    needsRender = true;
    onRequestFrame?.();
  };

  const onMouseUp = (event: MouseEvent) => {
    if (event.button === 0) state.isLeftDragging = false;
    if (event.button === 2) state.isRightDragging = false;
    onRequestFrame?.();
  };

  const onPointerLockChange = () => {
    state.isPointerLocked = document.pointerLockElement === canvas;
  };

  let needsRender = true;

  const onMouseMove = (event: MouseEvent) => {
    const dx = state.isPointerLocked ? event.movementX : (event.clientX - state.lastMouseX);
    const dy = state.isPointerLocked ? event.movementY : (event.clientY - state.lastMouseY);
    
    state.lastMouseX = event.clientX;
    state.lastMouseY = event.clientY;

    if (state.isPointerLocked || state.isLeftDragging) {
        const sensitivity = 0.002;
        camera.rotation.y -= dx * sensitivity;
        camera.rotation.x -= dy * sensitivity;
        camera.rotation.x = Math.max(-Math.PI / 2, Math.min(Math.PI / 2, camera.rotation.x));
        needsRender = true;
        onRequestFrame?.();
    } else if (state.isRightDragging) {
        const panSpeed = 0.5;
        const right = new THREE.Vector3(1, 0, 0).applyQuaternion(camera.quaternion);
        const up = new THREE.Vector3(0, 1, 0).applyQuaternion(camera.quaternion);
        camera.position.addScaledVector(right, -dx * panSpeed);
        camera.position.addScaledVector(up, dy * panSpeed);
        needsRender = true;
        onRequestFrame?.();
    }
  };

  const onWheel = (event: WheelEvent) => {
    event.preventDefault();
    if (event.deltaY < 0) {
      movementSpeedUnitsPerSec = Math.min(MOVEMENT_SPEED_MAX, movementSpeedUnitsPerSec * MOVEMENT_SPEED_WHEEL_FACTOR);
    } else {
      movementSpeedUnitsPerSec = Math.max(MOVEMENT_SPEED_MIN, movementSpeedUnitsPerSec / MOVEMENT_SPEED_WHEEL_FACTOR);
    }
    needsRender = true;
    onRequestFrame?.();
  };

  const onKeyDown = (e: KeyboardEvent) => {
     const flight = isKeyboardFlightActive();
     // While flying, soften tab/window shortcuts where cancelable (digits); Ctrl+W etc.
     // often stay reserved by Chrome/Firefox and cannot be overridden.
     if (flight) {
       const mod = e.ctrlKey || e.metaKey;
       if (mod && /^(Digit[0-9]|Numpad[0-9])$/.test(e.code)) {
         e.preventDefault();
         e.stopImmediatePropagation();
         return;
       }
       if (mod && /^(KeyW|KeyA|KeyS|KeyD)$/.test(e.code)) {
         e.preventDefault();
         e.stopImmediatePropagation();
       }
       const k = e.key.toLowerCase();
       if (k === ' ' || k === 'w' || k === 'a' || k === 's' || k === 'd' || k === 'shift' || k === 'alt' || k === 'c') {
         e.preventDefault();
       }
     }
     if (!flight) {
       const k = e.key.toLowerCase();
       if (k === 'w' || k === 'a' || k === 's' || k === 'd' || e.key === ' ' || k === 'shift' || k === 'c' || k === 'alt') {
         return;
       }
     }
     if (e.code === 'AltLeft' || e.code === 'AltRight') {
       state.keys.altFly = true;
     } else switch (e.key.toLowerCase()) {
         case 'w': state.keys.w = true; break;
         case 'a': state.keys.a = true; break;
         case 's': state.keys.s = true; break;
         case 'd': state.keys.d = true; break;
         case ' ': state.keys.space = true; break;
         case 'shift': state.keys.shift = true; break;
         case 'c': state.keys.c = true; break;
     }
     needsRender = true;
     onRequestFrame?.();
  };

  const onKeyUp = (e: KeyboardEvent) => {
     if (e.code === 'AltLeft' || e.code === 'AltRight') {
       state.keys.altFly = false;
     } else switch (e.key.toLowerCase()) {
         case 'w': state.keys.w = false; break;
         case 'a': state.keys.a = false; break;
         case 's': state.keys.s = false; break;
         case 'd': state.keys.d = false; break;
         case ' ': state.keys.space = false; break;
         case 'shift': state.keys.shift = false; break;
         case 'c': state.keys.c = false; break;
     }
     onRequestFrame?.();
  };

  canvas.addEventListener('mousedown', onMouseDown);
  window.addEventListener('mouseup', onMouseUp);
  window.addEventListener('mousemove', onMouseMove);
  window.addEventListener('pointerlockchange', onPointerLockChange);
  canvas.addEventListener('wheel', onWheel, { passive: false });
  canvas.addEventListener('contextmenu', (e) => e.preventDefault());
  window.addEventListener('keydown', onKeyDown, true);
  window.addEventListener('keyup', onKeyUp, true);

  const onResize = () => {
    width = container.clientWidth || container.offsetWidth;
    height = container.clientHeight || container.offsetHeight;
    applySize();
    needsRender = true;
    onRequestFrame?.();
  };

  const resizeObserver = new ResizeObserver(onResize);
  resizeObserver.observe(container);

  const setTarget = (newTarget: THREE.Vector3) => {
    target.copy(newTarget);
    camera.lookAt(target);
    needsRender = true;
    onRequestFrame?.();
  };

  const update = (deltaMs: number) => {
      if (!isKeyboardFlightActive()) {
        clearMovementKeys();
      } else {
        const sec = Math.min(deltaMs / 1000, 0.2);
        const mult = state.keys.shift ? 2.5 : 1;
        const moveDist = movementSpeedUnitsPerSec * mult * sec;

        const forward = new THREE.Vector3(0, 0, -1).applyQuaternion(camera.quaternion);
        const right = new THREE.Vector3(1, 0, 0).applyQuaternion(camera.quaternion);
        const up = new THREE.Vector3(0, 1, 0);

        let moved = false;
        if (state.keys.w) { camera.position.addScaledVector(forward, moveDist); moved = true; }
        if (state.keys.s) { camera.position.addScaledVector(forward, -moveDist); moved = true; }
        if (state.keys.d) { camera.position.addScaledVector(right, moveDist); moved = true; }
        if (state.keys.a) { camera.position.addScaledVector(right, -moveDist); moved = true; }
        if (state.keys.space) { camera.position.addScaledVector(up, moveDist); moved = true; }
        if (state.keys.c || state.keys.altFly) { camera.position.addScaledVector(up, -moveDist); moved = true; }

        if (moved) needsRender = true;
      }

      const shouldRender = needsRender;
      needsRender = false;
      return shouldRender;
  };

  const getMovementSpeed = () => movementSpeedUnitsPerSec;

  const setResolutionScale = (scale: number) => {
    const next = Math.max(0.05, Math.min(1, scale));
    if (Math.abs(next - resolutionScale) < 0.01) return;
    resolutionScale = next;
    applySize();
  };

  const dispose = () => {
    resizeObserver.disconnect();
    window.removeEventListener('mouseup', onMouseUp);
    window.removeEventListener('mousemove', onMouseMove);
    window.removeEventListener('pointerlockchange', onPointerLockChange);
    window.removeEventListener('keydown', onKeyDown, true);
    window.removeEventListener('keyup', onKeyUp, true);
    canvas.removeEventListener('mousedown', onMouseDown);
    canvas.removeEventListener('wheel', onWheel);
    canvas.removeEventListener('contextmenu', (e) => e.preventDefault());
    
    if (document.pointerLockElement === canvas) {
        document.exitPointerLock();
    }
    
    if (container.contains(renderer.domElement)) {
      container.removeChild(renderer.domElement);
    }
    renderer.dispose();
  };

  return { scene, camera, renderer, setTarget, update, getMovementSpeed, setResolutionScale, dispose };
}

<script setup lang="ts">
import { RouterView, useRoute } from 'vue-router'
import TopBar from "@/components/TopBar.vue";
import RootView from "@/views/RootView.vue";
import DensityTracePanel from "@/components/DensityTracePanel.vue";
import { computed, onMounted, onBeforeUnmount } from 'vue';
import { useEditorStore } from '@/stores/editor';

const route = useRoute();
const isViewingNode = computed(() => route.name === 'density-viewer');
const store = useEditorStore();

function onKeyDown(e: KeyboardEvent) {
  if ((e.ctrlKey || e.metaKey) && (e.key === 's' || e.key === 'S')) {
    e.preventDefault();
    e.stopPropagation();
    store.save();
  }
}

onMounted(() => {
  window.addEventListener('keydown', onKeyDown, true);
});
onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeyDown, true);
});
</script>

<template>
  <div class="app-layout">
    <header>
      <TopBar />
    </header>

    <div class="main-content">
      <DensityTracePanel />
      <RootView />
      <div v-if="isViewingNode" class="overlay-viewer">
        <div class="viewer-window">
          <RouterView />
        </div>
      </div>
    </div>

    <Transition name="toast">
      <div v-if="store.toastMessage" class="toast" role="status">{{ store.toastMessage }}</div>
    </Transition>
  </div>
</template>

<style scoped>
.app-layout {
  display: flex;
  flex-direction: column;
  height: 100vh;
  width: 100vw;
  overflow: hidden;
}

header {
  height: 60px;
  flex-shrink: 0;
}

.main-content {
  flex: 1;
  position: relative;
  overflow: hidden;
}

.overlay-viewer {
  position: absolute;
  top: 0;
  right: 0;
  width: 50%;
  height: 100%;
  background: rgba(0,0,0,0.5);
  display: flex;
  justify-content: flex-end;
  pointer-events: none;
}

.viewer-window {
  width: 100%;
  height: 100%;
  background: #050608;
  box-shadow: -10px 0 30px rgba(0,0,0,0.7);
  pointer-events: auto;
  position: relative;
  border-left: 2px solid #42d392;
}

.toast {
  position: fixed;
  bottom: 24px;
  left: 50%;
  transform: translateX(-50%);
  padding: 10px 20px;
  background: #1e1e1e;
  color: #e5e5e5;
  border-radius: 8px;
  box-shadow: 0 4px 16px rgba(0,0,0,0.4);
  border: 1px solid #333;
  font-size: 0.9rem;
  z-index: 9999;
  pointer-events: none;
}

.toast-enter-active,
.toast-leave-active {
  transition: opacity 0.2s ease, transform 0.2s ease;
}
.toast-enter-from,
.toast-leave-to {
  opacity: 0;
  transform: translateX(-50%) translateY(8px);
}
</style>

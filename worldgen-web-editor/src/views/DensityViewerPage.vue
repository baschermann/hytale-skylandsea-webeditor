<script setup lang="ts">
import DensityNode3DView from '@/components/DensityNode3DView.vue';
import { useRoute } from 'vue-router';
import { computed } from 'vue';

const route = useRoute();
const nodeId = computed(() => route.params.nodeId as string);
const inputNodeIds = computed(() => {
  const raw = route.query.inputIds;
  if (typeof raw !== 'string' || !raw.trim()) return [];
  return raw.split(',').map((id) => id.trim()).filter(Boolean);
});
const inputNodeLabels = computed(() => {
  const raw = route.query.inputLabels;
  if (typeof raw !== 'string' || !raw.trim()) return [];
  return raw.split('|').map((l) => l.trim());
});
</script>

<template>
  <main class="viewer-page">
    <DensityNode3DView :node-id="nodeId" :input-node-ids="inputNodeIds" :input-node-labels="inputNodeLabels" />
  </main>
</template>

<style scoped>
.viewer-page {
  width: 100%;
  height: calc(100vh - 60px);
  overflow: hidden;
}
</style>


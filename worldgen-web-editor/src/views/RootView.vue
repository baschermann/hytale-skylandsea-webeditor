<script setup lang="ts">
import NodeEditor from "@/components/node-editor/NodeEditor.vue";
</script>

<template>
  <main>
    <NodeEditor />
  </main>
</template>

<style scoped>
main {
  width: 100vw;
  height: calc(100vh - 60px); /* Adjusting for TopBar if it's ~60px */
  overflow: hidden;
}
</style>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, nextTick } from 'vue'
import { useVueFlow } from '@vue-flow/core'
import type { NodeDefinition, Workspace } from '@/services/node-loader'
import { getCategoryColor } from '@/services/color-utils'

interface Props {
  nodes: Record<string, NodeDefinition>;
  workspace: Workspace | null;
  position: { x: number, y: number }; // Screen space position
  filterType?: string | null;
  filterSide?: 'source' | 'target' | null;
}

const props = defineProps<Props>()
const emit = defineEmits(['select', 'close'])

const { viewport } = useVueFlow()

const search = ref('')
const inputRef = ref<HTMLInputElement | null>(null)
const containerRef = ref<HTMLElement | null>(null)
const selectedIndex = ref(0)

const clampedPosition = ref({ x: props.position.x, y: props.position.y })
const isVisible = ref(false)

// Helper to find which category a node belongs to
function getCategory(nodeId: string): string {
  if (!props.workspace) return 'Other'
  for (const [category, nodeIds] of Object.entries(props.workspace.NodeCategories)) {
    if (nodeIds.includes(nodeId)) return category
  }
  return 'Other'
}

const groupedNodes = computed(() => {
  const query = search.value.toLowerCase()
  
  const filtered = Object.values(props.nodes).filter(node => {
    const matchesSearch = node.Title.toLowerCase().includes(query) || node.Id.toLowerCase().includes(query)
    
    if (!props.filterType || !props.filterSide) return matchesSearch
    if (props.filterSide === 'source') {
      return matchesSearch && node.Inputs.some(i => i.Type === props.filterType)
    } else {
      return matchesSearch && node.Outputs.some(o => o.Type === props.filterType)
    }
  })

  const groups: Record<string, NodeDefinition[]> = {}
  filtered.forEach(node => {
    const cat = node.Category || getCategory(node.Id)
    if (!groups[cat]) groups[cat] = []
    groups[cat].push(node)
  })

  return Object.entries(groups)
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([name, nodes]) => {
      const sortedNodes = nodes.sort((a, b) => a.Title.localeCompare(b.Title))
      return {
        name,
        nodes: sortedNodes,
        color: getCategoryColor(name)
      }
    })
})

const flatList = computed(() => groupedNodes.value.flatMap(g => g.nodes))

function selectNode(node: NodeDefinition) {
  emit('select', node)
}

function onKeyDown(e: KeyboardEvent) {
  if (flatList.value.length === 0) return
  if (e.key === 'ArrowDown') {
    selectedIndex.value = (selectedIndex.value + 1) % flatList.value.length
    ensureVisible()
    e.preventDefault()
  } else if (e.key === 'ArrowUp') {
    selectedIndex.value = (selectedIndex.value - 1 + flatList.value.length) % flatList.value.length
    ensureVisible()
    e.preventDefault()
  } else if (e.key === 'Enter') {
    if (flatList.value[selectedIndex.value]) {
      selectNode(flatList.value[selectedIndex.value])
    }
  } else if (e.key === 'Escape') {
    emit('close')
  }
}

function ensureVisible() {
  nextTick(() => {
    const selectedEl = document.querySelector('.node-entry.is-selected')
    if (selectedEl) {
      selectedEl.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
    }
  })
}

function focusSearch() {
  nextTick(() => {
    inputRef.value?.focus()
  })
}

onMounted(async () => {
  window.addEventListener('keydown', onKeyDown)
  
  await nextTick()
  if (!containerRef.value) return
  
  const rect = containerRef.value.getBoundingClientRect()
  const padding = 20
  
  let x = props.position.x
  let y = props.position.y
  
  if (x + rect.width > window.innerWidth - padding) {
    x = window.innerWidth - rect.width - padding
  }
  if (x < padding) x = padding
  
  if (y + rect.height > window.innerHeight - padding) {
    y = window.innerHeight - rect.height - padding
  }
  if (y < padding) y = padding
  
  clampedPosition.value = { x, y }
  isVisible.value = true
  focusSearch()
})

onUnmounted(() => {
  window.removeEventListener('keydown', onKeyDown)
})
</script>

<template>
  <div 
    ref="containerRef" 
    class="search-container" 
    :style="{ 
      left: clampedPosition.x + 'px', 
      top: clampedPosition.y + 'px',
      visibility: isVisible ? 'visible' : 'hidden'
    }" 
    @mousedown.stop
  >
    <div class="search-box">
      <div class="search-header" :class="{ 'has-filter': !!filterType }">
        <input 
          ref="inputRef"
          v-model="search" 
          type="text" 
          placeholder="Search nodes..." 
          @input="selectedIndex = 0"
        />
        <div v-if="filterType" class="filter-indicator" :title="'Filtering for ' + filterType">
          {{ filterType }}
        </div>
      </div>
      
      <div class="results-area">
        <div v-if="groupedNodes.length > 0" class="scroll-content">
          <div v-for="group in groupedNodes" :key="group.name" class="group-section">
            <div class="group-header" :style="{ backgroundColor: group.color }">
              {{ group.name }}
            </div>
            <div 
              v-for="node in group.nodes" 
              :key="node.Id"
              class="node-entry"
              :class="{ 'is-selected': flatList[selectedIndex]?.Id === node.Id }"
              @click="selectNode(node)"
              @mouseenter="selectedIndex = flatList.findIndex(n => n.Id === node.Id)"
            >
              <span class="entry-dot" :style="{ backgroundColor: getCategoryColor(node.Category, node.Id) }"></span>
              <span class="entry-label">{{ node.Title }}</span>
            </div>
          </div>
        </div>
        <div v-else class="empty-state">
          No matches found
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.search-container {
  position: fixed;
  z-index: 2000;
  width: 380px;
  pointer-events: auto;
}

.search-box {
  background: #1e1e1e;
  border: 1px solid #3c3c3c;
  border-radius: 6px;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.5);
  display: flex;
  flex-direction: column;
  max-height: 450px;
  overflow: hidden;
  animation: popIn 0.15s ease-out;
}

@keyframes popIn {
  from { opacity: 0; transform: scale(0.95); }
  to { opacity: 1; transform: scale(1); }
}

.search-header {
  flex-shrink: 0;
  padding: 10px 12px;
  background: #2d2d2d;
  border-bottom: 1px solid #3c3c3c;
  display: flex;
  align-items: center;
  gap: 8px;
}

.search-header.has-filter {
  background: #1a2a22;
}

input {
  background: transparent;
  border: none;
  color: #eee;
  width: 100%;
  font-size: 0.95em;
  outline: none;
  padding: 4px 0;
}

.filter-indicator {
  font-size: 0.65em;
  background: #42d392;
  color: #1a1a1a;
  padding: 2px 8px;
  border-radius: 3px;
  font-weight: bold;
  white-space: nowrap;
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
}

.results-area {
  flex-grow: 1;
  overflow-y: auto;
  background: #1e1e1e;
  min-height: 0;
}

.scroll-content {
  padding: 0;
}

.group-section {
  margin-bottom: 4px;
}

.group-header {
  position: sticky;
  top: 0;
  z-index: 10;
  padding: 8px 16px;
  font-size: 0.75em;
  text-transform: uppercase;
  font-weight: 800;
  color: #f5f5f5;
  text-shadow: 0 1px 2px rgba(0,0,0,0.6);
  letter-spacing: 0.05em;
  border-bottom: 1px solid rgba(0,0,0,0.2);
}

.node-entry {
  padding: 8px 16px 8px 24px;
  display: flex;
  align-items: center;
  gap: 10px;
  cursor: pointer;
  transition: background 0.1s;
}

.node-entry:hover, .node-entry.is-selected {
  background: #2a2d2e;
}

.node-entry.is-selected .entry-label {
  color: #42d392;
}

.entry-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  flex-shrink: 0;
}

.entry-label {
  font-size: 0.85em;
  color: #ccc;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.empty-state {
  padding: 40px 20px;
  text-align: center;
  color: #666;
  font-size: 0.8em;
}

.results-area::-webkit-scrollbar {
  width: 8px;
}
.results-area::-webkit-scrollbar-track {
  background: transparent;
}
.results-area::-webkit-scrollbar-thumb {
  background: #3c3c3c;
  border-radius: 4px;
  border: 2px solid #1e1e1e;
}
.results-area::-webkit-scrollbar-thumb:hover {
  background: #4c4c4c;
}
</style>

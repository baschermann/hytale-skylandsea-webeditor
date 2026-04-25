import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router';
import RootView from '@/views/RootView.vue';
import DensityViewerPage from '@/views/DensityViewerPage.vue';

const routes: RouteRecordRaw[] = [
  {
    path: '/',
    name: 'root',
    component: { template: '<div></div>' },
  },
  {
    path: '/density-viewer/:nodeId',
    name: 'density-viewer',
    component: DensityViewerPage,
    props: true,
  },
];

export const router = createRouter({
  history: createWebHistory(),
  routes,
});


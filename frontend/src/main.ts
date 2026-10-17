import { createApp } from 'vue';
import { createPinia } from 'pinia';
import { createRouter, createWebHashHistory } from 'vue-router';
import App from './App.vue';
import ChatView from './views/ChatView.vue';
import DocumentsView from './views/DocumentsView.vue';
import './style.css';

const router = createRouter({
  history: createWebHashHistory('/console/'),
  routes: [
    { path: '/', component: ChatView },
    { path: '/documents', component: DocumentsView },
  ],
});

createApp(App).use(createPinia()).use(router).mount('#app');

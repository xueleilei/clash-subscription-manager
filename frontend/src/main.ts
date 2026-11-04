import { createApp } from 'vue'
import 'element-plus/dist/index.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import './assets/main.css'
import App from './App.vue'
import { createAppRouter } from './router'
createApp(App).use(createAppRouter()).mount('#app')

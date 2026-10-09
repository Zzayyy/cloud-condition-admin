import { fileURLToPath, URL } from 'node:url'
import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { NaiveUiResolver } from 'unplugin-vue-components/resolvers'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), 'VITE_')
  const port = Number(env.VITE_PORT || 5173)
  const apiBase = env.VITE_API_BASE_URL || '/api'
  const apiTarget = env.VITE_API_TARGET || 'http://127.0.0.1:8000'

  return {
    plugins: [
      vue(),
      // Naive UI 组件与 API 按需自动引入，模板里直接写 <n-button> 即可。
      AutoImport({
        imports: [
          'vue',
          'vue-router',
          'pinia',
          {
            'naive-ui': ['useMessage', 'useDialog', 'useNotification', 'useLoadingBar'],
          },
        ],
        dts: 'auto-imports.d.ts',
      }),
      Components({
        resolvers: [NaiveUiResolver()],
        dirs: ['src/components'],
        dts: 'components.d.ts',
      }),
    ],
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url)),
      },
    },
    server: {
      port,
      host: true,
      open: false,
      proxy: {
        // 后端服务尚未实现；先留好代理，接口一落地即可直连。
        [apiBase]: {
          target: apiTarget,
          changeOrigin: true,
        },
      },
    },
    build: {
      outDir: 'dist',
      sourcemap: mode !== 'production',
      chunkSizeWarningLimit: 1500,
      rollupOptions: {
        output: {
          manualChunks: {
            vue: ['vue', 'vue-router', 'pinia'],
            naive: ['naive-ui'],
            // ECharts 只在有图表的页面用到，单独分包避免拖累首屏
            charts: ['echarts', 'echarts/core', 'echarts/charts', 'echarts/components', 'echarts/renderers'],
          },
        },
      },
    },
  }
})

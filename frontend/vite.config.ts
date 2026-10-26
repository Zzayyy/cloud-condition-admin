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

  /**
   * 允许访问开发服务器的域名（Host 头白名单）。
   *
   * Vite 默认只放行 localhost 与 IP 地址，通过内网穿透（natapp / ngrok / frp 等）
   * 用域名访问时会被拦下并报：
   *   Blocked request. This host ("xxx") is not allowed.
   *
   * 规则（Vite 源码 isHostAllowedInternal）：
   *   'demo.com'        → 精确匹配 demo.com
   *   '.natappfree.cc'  → 匹配 natappfree.cc 本身 + 任意子域（含 xxx.natappfree.cc）
   *
   * ⚠️ natapp **免费版**每重启一次域名就会变（形如 da6f583c.natappfree.cc），
   * 所以这里用通配符而不是写死某一个域名，免得每次重启都要改代码。
   * 想收紧或换隧道服务时，改 .env.local 里的 VITE_ALLOWED_HOSTS 即可
   * （逗号分隔），不用动这个文件：
   *   VITE_ALLOWED_HOSTS=da6f583c.natappfree.cc,my.ngrok-free.app
   */
  const allowedHosts = (
    env.VITE_ALLOWED_HOSTS ||
    // 默认值：常见内网穿透服务的通配符
    '.natappfree.cc,.ngrok-free.app,.ngrok.io,.trycloudflare.com,.localtunnel.me,.cpolar.top,.frp.run'
  )
    .split(',')
    .map((h) => h.trim())
    .filter(Boolean)

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
      // 监听所有网卡，局域网 / 内网穿透才能连进来
      host: true,
      allowedHosts,
      open: false,
      proxy: {
        [apiBase]: {
          target: apiTarget,
          // 改 Host 头，让后端认为请求是直接发给它的
          changeOrigin: true,
          /*
           * ⚠️ `xfwd` 必须开，否则后端**永远记不到真实客户端 IP**。
           *
           * changeOrigin 只改 Host，不会加 X-Forwarded-For；
           * 于是后端 request.getRemoteAddr() 拿到的是**本机**（127.0.0.1），
           * 因为请求是 Vite dev server 转发过来的。
           *
           * 实测症状（2026-10-26）：同事从局域网访问 192.168.1.77:5173 登录，
           * T_AdminLoginLog 里记的 IP 全是 127.0.0.1 —— 换几台机器都一样。
           *
           * 开了之后 http-proxy 会带上 X-Forwarded-For / X-Forwarded-Host /
           * X-Forwarded-Proto，后端 AuditService.clientIp() 会优先取 XFF 的第一段。
           *
           * ⚠️ 生产环境（nginx 反代）同理，要配：
           *     proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
           *     proxy_set_header X-Real-IP $remote_addr;
           */
          xfwd: true,
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

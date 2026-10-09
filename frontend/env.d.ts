/// <reference types="vite/client" />

declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<Record<string, unknown>, Record<string, unknown>, unknown>
  export default component
}

interface ImportMetaEnv {
  /** 后端 API 基地址（生产/开发统一读这个） */
  readonly VITE_API_BASE_URL: string
  /** 开发服务器端口 */
  readonly VITE_PORT?: string
  /** 开发代理目标（后端服务地址） */
  readonly VITE_API_TARGET?: string
  /** 页面标题 */
  readonly VITE_APP_TITLE?: string
  /** 演示数据开关：'true' 时直接用 src/api/mock 下的假数据 */
  readonly VITE_USE_MOCK?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}

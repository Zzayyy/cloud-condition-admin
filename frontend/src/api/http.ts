import axios, {
  AxiosError,
  type AxiosInstance,
  type AxiosRequestConfig,
  type AxiosResponse,
  type InternalAxiosRequestConfig,
} from 'axios'
import type { ApiEnvelope } from './types'

/** 业务错误：HTTP 通了但 code != 0。 */
export class BizError extends Error {
  constructor(
    message: string,
    readonly code: number,
    readonly payload?: unknown,
  ) {
    super(message)
    this.name = 'BizError'
  }
}

/** 后端尚未实现该接口时抛出，页面上据此显示「待对接」而不是报错。 */
export class NotImplementedError extends Error {
  constructor(message = '后端接口尚未实现') {
    super(message)
    this.name = 'NotImplementedError'
  }
}

const TOKEN_KEY = 'cca:token'

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}

export function setToken(token: string | null) {
  if (token) localStorage.setItem(TOKEN_KEY, token)
  else localStorage.removeItem(TOKEN_KEY)
}

export const http: AxiosInstance = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
  timeout: 30_000,
  headers: { 'Content-Type': 'application/json' },
})

http.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const token = getToken()
  if (token) {
    config.headers.set('Authorization', `Bearer ${token}`)
  }
  return config
})

function isApiEnvelope(value: unknown): value is ApiEnvelope<unknown> {
  return (
    typeof value === 'object' &&
    value !== null &&
    'code' in value &&
    typeof (value as { code: unknown }).code === 'number'
  )
}

http.interceptors.response.use(
  (response: AxiosResponse) => response,
  (error: AxiosError) => {
    // 404/501 且是 /api 前缀 → 后端确实还没写，交给调用方按「待对接」处理
    if (error.response?.status === 404 || error.response?.status === 501) {
      return Promise.reject(new NotImplementedError())
    }
    const status = error.response?.status
    const detail =
      (error.response?.data as { message?: string } | undefined)?.message || error.message
    return Promise.reject(
      new Error(status ? `请求失败（HTTP ${status}）：${detail}` : `网络错误：${detail}`),
    )
  },
)

/** 从响应里取出业务数据，同时把 code != 0 转成 BizError。 */
function unwrap<T>(response: AxiosResponse): T {
  const body = response.data as unknown
  if (isApiEnvelope(body)) {
    if (body.code !== 0) {
      throw new BizError(body.message || '请求失败', body.code, body.data)
    }
    return body.data as T
  }
  // 后端没包 envelope 时，直接把 body 当作数据
  return body as T
}

export async function request<T>(config: AxiosRequestConfig): Promise<T> {
  const response = await http.request<unknown>(config)
  return unwrap<T>(response)
}

export const api = {
  get: <T>(url: string, params?: Record<string, unknown>, config?: AxiosRequestConfig) =>
    request<T>({ ...config, url, method: 'GET', params }),

  post: <T>(url: string, data?: unknown, config?: AxiosRequestConfig) =>
    request<T>({ ...config, url, method: 'POST', data }),

  put: <T>(url: string, data?: unknown, config?: AxiosRequestConfig) =>
    request<T>({ ...config, url, method: 'PUT', data }),

  patch: <T>(url: string, data?: unknown, config?: AxiosRequestConfig) =>
    request<T>({ ...config, url, method: 'PATCH', data }),

  delete: <T>(url: string, params?: Record<string, unknown>, config?: AxiosRequestConfig) =>
    request<T>({ ...config, url, method: 'DELETE', params }),
}

/**
 * 后端接口还没实现时，用它占位。
 * 页面据此渲染「待对接」提示，而不是抛一个看不懂的错误。
 */
export function pending<T>(hint?: string): Promise<T> {
  return Promise.reject(new NotImplementedError(hint))
}

export default api

/**
 * 统一响应结构。
 *
 * 后端接口还没实现，这里先把「约定」定下来（见 README 的「前后端约定」一节）：
 * 所有接口返回 HTTP 200，业务结果放 body：
 *   { code: 0, message: 'ok', data: T }
 * code 非 0 视为业务错误。若后端最终采用别的结构，
 * 只需要改本文件的 `unwrap` 与 `isApiEnvelope` 两处。
 */
export interface ApiEnvelope<T> {
  code: number
  message: string
  data: T
}

export interface PageQuery {
  page?: number
  pageSize?: number
  /** 关键字模糊搜索 */
  keyword?: string
  /** 排序字段 */
  sortBy?: string
  /** 排序方向 */
  sortOrder?: 'asc' | 'desc'
  /** 任意附加过滤条件 */
  [key: string]: unknown
}

export interface PageResult<T> {
  list: T[]
  total: number
  page: number
  pageSize: number
}

export function emptyPage<T>(page = 1, pageSize = 20): PageResult<T> {
  return { list: [], total: 0, page, pageSize }
}

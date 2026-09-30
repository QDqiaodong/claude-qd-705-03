import axios from 'axios'
import { ElMessage } from 'element-plus'

/**
 * 统一走 nginx 反代到后端 /api：
 *   成功 → 后端包成 { ok:true, message, data }，这里直接把 data 交给页面
 *   失败 → 后端包成 { ok:false, message:"中文业务提示" }，这里统一弹提示并 reject
 */
const http = axios.create({ baseURL: '/api', timeout: 15000 })

http.interceptors.response.use(
  (res) => {
    const body = res.data
    if (body && typeof body === 'object' && Object.prototype.hasOwnProperty.call(body, 'ok')) {
      if (body.ok) return body.data
      ElMessage.error(body.message || '操作失败')
      return Promise.reject(new Error(body.message || '操作失败'))
    }
    return body
  },
  (err) => {
    const body = err.response && err.response.data
    const message = (body && body.message) || err.message || '网络异常，请稍后再试'
    ElMessage.error(message)
    return Promise.reject(new Error(message))
  }
)

/** 模块一：发酵罐阵列 */
export const fermenterApi = {
  list: (status) => http.get('/fermenters', { params: status ? { status } : {} }),
  overview: () => http.get('/fermenters/overview'),
  statusDict: () => http.get('/fermenters/status-dict'),
  create: (data) => http.post('/fermenters', data),
  setStatus: (id, data) => http.post(`/fermenters/${id}/status`, data),
  assign: (id, batchNo) => http.post(`/fermenters/${id}/assign`, { batchNo }),
  release: (id) => http.post(`/fermenters/${id}/release`)
}

/** 模块二：啤酒批次 */
export const batchApi = {
  list: (status) => http.get('/batches', { params: status ? { status } : {} }),
  summary: () => http.get('/batches/summary'),
  get: (id) => http.get(`/batches/${id}`),
  create: (data) => http.post('/batches', data),
  advance: (id, status) => http.post(`/batches/${id}/advance`, status ? { status } : {}),
  scrap: (id) => http.post(`/batches/${id}/scrap`),
  putReading: (id, data) => http.post(`/batches/${id}/readings`, data),
  removeReading: (id, dayKey) => http.delete(`/batches/${id}/readings/${dayKey}`),
  reading: (id, dayKey) => http.get(`/batches/${id}/readings/${dayKey}`)
}

/** 模块六：批次质检放行（送检/放行/驳回/撤销/复检历史） */
export const qcApi = {
  profile: (batchId) => http.get(`/batches/${batchId}/qc`),
  openRound: (batchId, data) => http.post(`/batches/${batchId}/qc/rounds`, data),
  decide: (batchId, roundId, data) => http.post(`/batches/${batchId}/qc/rounds/${roundId}/decision`, data),
  revoke: (batchId, roundId, data) => http.post(`/batches/${batchId}/qc/rounds/${roundId}/revoke`, data)
}

/** 模块三：配方与原料用量（Map<String, RecipeItem> 的增删改查 + 统一计量/缩放） */
export const recipeApi = {
  list: () => http.get('/recipes'),
  stages: () => http.get('/recipes/stages'),
  get: (id) => http.get(`/recipes/${id}`),
  create: (data) => http.post('/recipes', data),
  /** 更新基准批量（L），缩放比例分母 */
  updateBaseBatch: (id, baseBatchL) => http.put(`/recipes/${id}/base-batch`, { baseBatchL }),
  /** Map 查：整张 Map */
  items: (id) => http.get(`/recipes/${id}/items`),
  /** Map 按 key 查 */
  item: (id, stage) => http.get(`/recipes/${id}/items/${stage}`),
  /** Map 增 / 改：key 存在即改，不存在即新增 */
  putItem: (id, stage, data) => http.put(`/recipes/${id}/items/${stage}`, data),
  /** Map 删 */
  removeItem: (id, stage) => http.delete(`/recipes/${id}/items/${stage}`),
  composition: (id) => http.get(`/recipes/${id}/composition`),
  /** 缩放预览（不落库）：原值/换算值/调整后值/余差去向 */
  scalePreview: (id, targetBatchL) => http.post(`/recipes/${id}/scale/preview`, { targetBatchL }),
  /** 确认应用缩放：必须带预览版本，期间改过明细会 409 过期 */
  scaleApply: (id, data) => http.post(`/recipes/${id}/scale/apply`, data)
}

/** 模块四：原料库存 */
export const ingredientApi = {
  list: (type) => http.get('/ingredients', { params: type ? { type } : {} }),
  low: () => http.get('/ingredients/low'),
  create: (data) => http.post('/ingredients', data),
  stock: (id, quantity, mode) => http.post(`/ingredients/${id}/stock`, { quantity, mode })
}

/** 模块五：灌装与出货 */
export const shippingApi = {
  bottlings: () => http.get('/shipping/bottlings'),
  createBottling: (data) => http.post('/shipping/bottlings', data),
  shipments: (status) => http.get('/shipping/shipments', { params: status ? { status } : {} }),
  createShipment: (data) => http.post('/shipping/shipments', data),
  nextStatus: (id) => http.post(`/shipping/shipments/${id}/next`),
  summary: () => http.get('/shipping/summary')
}

import axios from 'axios'
import type { Session, Step } from './types'

export const api = axios.create({ baseURL: '/api', timeout: 12000 })
const tokenKey = 'opsatlas-token'
export const token = () => sessionStorage.getItem(tokenKey)
export function saveToken(value: string | null) {
  if (value) sessionStorage.setItem(tokenKey, value)
  else sessionStorage.removeItem(tokenKey)
}

api.interceptors.request.use(config => {
  const current = token()
  if (current) config.headers.Authorization = 'Bearer ' + current
  return config
})

export function errorMessage(error: unknown) {
  if (axios.isAxiosError(error)) return error.response?.data?.message || error.message
  return error instanceof Error ? error.message : '操作失败，请重试'
}

export async function analyze(body: {question: string; storeId?: number; local: boolean}, onStep: (step: Step) => void): Promise<Session> {
  const response = await fetch('/api/agent/stream', {
    method: 'POST',
    headers: { Authorization: 'Bearer ' + token(), 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(70000),
  })
  if (!response.ok) {
    const problem = await response.json()
    throw Object.assign(new Error(problem.message || '分析请求失败'), {status:response.status})
  }
  const reader = response.body!.pipeThrough(new TextDecoderStream()).getReader()
  let pending = ''
  try {
    while (true) {
      const {value, done} = await reader.read()
      if (done) break
      pending += value
      let boundary = pending.match(/\r?\n\r?\n/)
      while (boundary?.index !== undefined) {
        const frame = pending.slice(0, boundary.index)
        pending = pending.slice(boundary.index + boundary[0].length)
        const [kind, data] = frame.split(/\r?\n/)
        if (data?.startsWith('data:')) {
          const payload = JSON.parse(data.slice(5))
          if (kind === 'event:step') onStep(payload)
          if (kind === 'event:failure') throw new Error(payload.message)
          if (kind === 'event:done') return payload
        }
        boundary = pending.match(/\r?\n\r?\n/)
      }
    }
    throw new Error('分析连接已断开，请重新发起')
  } finally {
    await reader.cancel()
    reader.releaseLock()
  }
}

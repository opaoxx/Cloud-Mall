export type ApiEnvelope<T> = { code: string; message: string; data: T; requestId?: string }
export class ApiError extends Error { constructor(public code: string, message: string, public status: number, public requestId?: string) { super(message) } }

const base = import.meta.env.VITE_API_BASE_URL || '/api'
const tokenKey = 'cloudmall.token'
export const auth = { get: () => localStorage.getItem(tokenKey), set: (v: string) => localStorage.setItem(tokenKey, v), clear: () => localStorage.removeItem(tokenKey) }
export const newIdempotencyKey = () => crypto.randomUUID()

async function request<T>(path: string, init: RequestInit = {}, idempotent = false): Promise<T> {
  const headers = new Headers(init.headers)
  headers.set('Content-Type', 'application/json')
  const token = auth.get(); if (token) headers.set('Authorization', `Bearer ${token}`)
  headers.set('X-Request-Id', crypto.randomUUID())
  if (idempotent) headers.set('Idempotency-Key', newIdempotencyKey())
  const response = await fetch(`${base}${path}`, { ...init, headers })
  let body: ApiEnvelope<T>
  try { body = await response.json() as ApiEnvelope<T> } catch { throw new ApiError('COMMON_INTERNAL_ERROR', '服务返回了无法解析的响应', response.status) }
  if (response.status === 401 || body.code === 'COMMON_UNAUTHORIZED') { auth.clear(); window.dispatchEvent(new Event('auth-expired')) }
  if (!response.ok || body.code !== '0') throw new ApiError(body.code, body.message || '请求失败', response.status, body.requestId)
  return body.data
}
const json = (method: string, body?: unknown): RequestInit => ({ method, body: body === undefined ? undefined : JSON.stringify(body) })
export const api = {
  register: (body: unknown) => request('/auth/register', json('POST', body)), login: (body: unknown) => request<{token: string; user: User}>('/auth/login', json('POST', body)), logout: () => request('/auth/logout', json('POST'), true),
  me: () => request<User>('/users/me'), updateMe: (body: unknown) => request<User>('/users/me', json('PUT', body)), addresses: () => request<Address[]>('/users/me/addresses'), addAddress: (body: unknown) => request<Address>('/users/me/addresses', json('POST'), true),
  products: (query = '') => request<Page<Product>>(`/products${query ? `?${query}` : ''}`), product: (id: string) => request<ProductDetail>(`/products/${id}`), categories: () => request<Category[]>('/categories'),
  cart: () => request<CartItem[]>('/cart'), addCart: (body: unknown) => request('/cart/items', json('POST', body), true), updateCart: (skuId: string, body: unknown) => request(`/cart/items/${skuId}`, json('PUT', body), true), removeCart: (skuId: string) => request(`/cart/items/${skuId}`, { method: 'DELETE' }, true), preview: (body: unknown) => request<Settlement>('/cart/settlement/preview', json('POST', body), true),
  orders: (query = '') => request<Page<Order>>(`/orders${query ? `?${query}` : ''}`), order: (no: string) => request<OrderDetail>(`/orders/${no}`), createOrder: (body: unknown) => request<Order>('/orders', json('POST', body), true), cancelOrder: (no: string, body: unknown) => request(`/orders/${no}/cancel`, json('POST', body), true),
  createPayment: (body: unknown) => request<Payment>('/payments', json('POST', body), true), payment: (no: string) => request<Payment>(`/payments/orders/${no}`), mockSuccess: (payNo: string) => request(`/payments/${payNo}/mock-success`, json('POST'), true), mockFail: (payNo: string, body: unknown) => request(`/payments/${payNo}/mock-fail`, json('POST', body), true),
  seckill: (id: string) => request<SeckillActivity>(`/seckill/activities/${id}`), seckillOrder: (body: unknown) => request<Order>('/seckill/orders', json('POST', body), true)
}
export type User = { id: number; username: string; nickname?: string; phone?: string; avatarUrl?: string; roles: string[] }
export type Category = { id: number; parentId: number; name: string; sortNo: number; status: number }
export type Product = { id: number; name: string; mainImage?: string; price: string; status: number; categoryId: number }
export type ProductDetail = Product & { description?: string; skus: Sku[]; parameters?: {name: string; value: string}[] }
export type Sku = { id: number; skuCode: string; specJson: Record<string,string>; price: string; status: number }
export type Page<T> = { items: T[]; page: number; pageSize: number; total: number }
export type CartItem = { skuId: number; productId: number; productName: string; skuSnapshot?: Record<string,string>; unitPrice: string; quantity: number; checked: boolean; mainImage?: string }
export type Settlement = { items: CartItem[]; invalidItems?: CartItem[]; totalAmount: string; payAmount: string }
export type Address = { id: number; receiver: string; phone: string; detailAddress: string; isDefault: boolean }
export type Order = { orderNo: string; status: string; payAmount: string; createdAt: string; expireAt?: string }
export type OrderDetail = Order & { items: {productNameSnapshot: string; skuSnapshot?: Record<string,string>; unitPrice: string; quantity: number; lineAmount: string}[]; addressSnapshot: Address }
export type Payment = { payNo: string; orderNo: string; amount: string; status: string }
export type SeckillActivity = { activityId: number; skuId: number; status: string; startAt: string; endAt: string; remainingStock: number; perUserLimit: number; product?: Product }

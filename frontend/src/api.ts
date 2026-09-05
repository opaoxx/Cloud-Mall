export type ApiEnvelope<T> = { code: string; message: string; data: T; requestId?: string }
export class ApiError extends Error { constructor(public code: string, message: string, public status: number, public requestId?: string) { super(message); this.name = 'ApiError' } }
const base = (import.meta.env.VITE_API_BASE_URL || '/api').replace(/\/$/, '')
const tokenKey = 'cloudmall.token'
export const auth = { get: () => localStorage.getItem(tokenKey), set: (value: string) => localStorage.setItem(tokenKey, value), clear: () => localStorage.removeItem(tokenKey) }
export const newIdempotencyKey = () => crypto.randomUUID()

async function request<T>(path: string, init: RequestInit = {}, idempotencyKey?: string): Promise<T> {
  const headers = new Headers(init.headers)
  if (init.body && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json')
  const token = auth.get(); if (token) headers.set('Authorization', `Bearer ${token}`)
  headers.set('X-Request-Id', newIdempotencyKey()); if (idempotencyKey) headers.set('Idempotency-Key', idempotencyKey)
  let response: Response
  try { response = await fetch(`${base}${path}`, { ...init, headers }) } catch { throw new ApiError('GATEWAY_SERVICE_UNAVAILABLE', '网关暂不可用', 503) }
  let body: ApiEnvelope<T>
  try {
    // Decode the bytes explicitly so Chinese responses remain intact even when
    // a gateway supplies a charset parameter instead of relying on Response.json().
    const contentType = response.headers.get('Content-Type') || ''
    const charset = contentType.match(/charset\s*=\s*['"]?([^;\s'"]+)/i)?.[1] || 'utf-8'
    const bytes = await response.arrayBuffer()
    const text = new TextDecoder(charset).decode(bytes)
    if (!text.trim()) {
      if (response.status === 401) { auth.clear(); window.dispatchEvent(new Event('auth-expired')); throw new ApiError('COMMON_UNAUTHORIZED', '请先登录', 401) }
      throw new ApiError('COMMON_INTERNAL_ERROR', '服务返回了空响应', response.status)
    }
    try { body = JSON.parse(text) as ApiEnvelope<T> } catch { throw new ApiError('COMMON_INTERNAL_ERROR', '服务返回了无法解析的响应', response.status) }
  } catch (error) {
    if (error instanceof ApiError) throw error
    throw new ApiError('COMMON_INTERNAL_ERROR', '服务返回了无法解析的响应', response.status)
  }
  if (response.status === 401 || body.code === 'COMMON_UNAUTHORIZED' || body.code === 'USER_LOGIN_FAILED') { auth.clear(); window.dispatchEvent(new Event('auth-expired')) }
  if (!response.ok || body.code !== '0') throw new ApiError(body.code, body.message || '请求失败', response.status, body.requestId)
  return body.data
}
const json = (method: string, body?: unknown): RequestInit => ({ method, body: body === undefined ? undefined : JSON.stringify(body) })
const writeKey = () => newIdempotencyKey()
const toQuery = (values: Record<string, unknown>) => { const q = new URLSearchParams(); Object.entries(values).forEach(([k,v]) => v !== undefined && v !== '' && q.set(k, String(v))); const s=q.toString(); return s ? `?${s}` : '' }

export const api = {
  register: (body: RegisterRequest) => request<RegisterResult>('/auth/register', json('POST', body)), login: (body: RegisterRequest) => request<LoginResult>('/auth/login', json('POST', body)), logout: () => request<null>('/auth/logout', json('POST'), writeKey()),
  me: () => request<User>('/users/me'), updateMe: (body: UpdateUserRequest) => request<User>('/users/me', json('PUT', body)), addresses: () => request<Address[]>('/users/me/addresses'), addAddress: (body: AddressRequest) => request<Address>('/users/me/addresses', json('POST', body), writeKey()), updateAddress: (id: number, body: Partial<AddressRequest>) => request<Address>(`/users/me/addresses/${id}`, json('PUT', body)), deleteAddress: (id: number) => request<null>(`/users/me/addresses/${id}`, { method: 'DELETE' }),
  products: (params: ProductQuery = {}) => request<Page<Product>>(`/products${toQuery(params)}`), product: (id: number|string) => request<ProductDetail>(`/products/${id}`), categories: (params: CategoryQuery = {}) => request<Category[]>(`/categories${toQuery(params)}`), createCategory: (body: CategoryRequest) => request<Category>('/categories', json('POST', body), writeKey()), updateCategory: (id: number, body: Partial<CategoryRequest>) => request<Category>(`/categories/${id}`, json('PUT', body)), deleteCategory: (id: number) => request<null>(`/categories/${id}`, { method: 'DELETE' }),
  createProduct: (body: ProductRequest) => request<ProductDetail>('/products', json('POST', body), writeKey()), updateProduct: (id: number|string, body: Partial<ProductRequest>) => request<ProductDetail>(`/products/${id}`, json('PUT', body)), publishProduct: (id: number|string) => request<Product>(`/products/${id}/publish`, json('POST'), writeKey()), unpublishProduct: (id: number|string) => request<Product>(`/products/${id}/unpublish`, json('POST'), writeKey()),
  cart: () => request<CartItem[]>('/cart'), addCart: (body: AddCartRequest) => request<CartItem>('/cart/items', json('POST', body), writeKey()), updateCart: (skuId: number, body: CartUpdateRequest) => request<CartItem>(`/cart/items/${skuId}`, json('PUT', body)), removeCart: (skuId: number) => request<null>(`/cart/items/${skuId}`, { method: 'DELETE' }), preview: (body: SettlementRequest) => request<Settlement>('/cart/settlement/preview', json('POST', body), writeKey()),
  orders: (params: OrderQuery = {}) => request<Page<Order>>(`/orders${toQuery(params)}`), order: (no: string) => request<OrderDetail>(`/orders/${no}`), createOrder: (body: CreateOrderRequest) => request<Order>('/orders', json('POST', body), writeKey()), cancelOrder: (no: string, reason?: string) => request<null>(`/orders/${no}/cancel`, json('POST', reason ? { reason } : undefined), writeKey()), confirmOrder: (no: string) => request<null>(`/orders/${no}/confirm`, json('POST'), writeKey()),
  createPayment: (body: PaymentRequest) => request<Payment>('/payments', json('POST', body), writeKey()), payment: (no: string) => request<Payment>(`/payments/orders/${no}`), mockSuccess: (payNo: string) => request<null>(`/payments/${payNo}/mock-success`, json('POST'), writeKey()), mockFail: (payNo: string, reason?: string) => request<null>(`/payments/${payNo}/mock-fail`, json('POST', reason ? { reason } : undefined), writeKey()), seckill: (id: number|string) => request<SeckillActivity>(`/seckill/activities/${id}`), seckillOrder: (body: SeckillOrderRequest) => request<SeckillOrderResult>('/seckill/orders', json('POST', body), writeKey()),
}

export const registerAndLogin = async (body: RegisterRequest): Promise<LoginResult> => {
  await api.register(body)
  return api.login(body)
}

export type Money = string
export type RegisterRequest = { username: string; password: string }; export type RegisterResult = { userId: number; username: string }; export type LoginResult = { token: string; user: User }; export type UpdateUserRequest = { nickname?: string; phone?: string; avatarUrl?: string }
export type User = { id: number; username: string; nickname?: string; phone?: string; avatarUrl?: string; roles: string[]; balance?: Money }; export type AddressRequest = { receiver: string; phone: string; detailAddress: string; isDefault: boolean }; export type Address = AddressRequest & { id: number }
export type CategoryQuery = { parentId?: number; status?: number }; export type CategoryRequest = { parentId: number; name: string; sortNo: number }; export type Category = CategoryRequest & { id: number; status: number }
export type ProductQuery = { keyword?: string; categoryId?: number; status?: number; page?: number; pageSize?: number; sort?: string }; export type ProductRequest = { name: string; mainImage?: string; description?: string; price: string; categoryId: number; skus?: Sku[]; parameters?: ProductParameter[] }; export type Product = { id: number|string; name: string; mainImage?: string; description?: string; price: Money; status: number; categoryId: number }; export type ProductDetail = Product & { skus: Sku[]; parameters?: ProductParameter[] }; export type ProductParameter = { name: string; value: string }; export type Sku = { id?: number|string; productId?: number|string; skuCode: string; specJson: Record<string,string>; price: Money; status: boolean }
export type Page<T> = { items: T[]; page: number; pageSize: number; total: number }; export type CartItem = { skuId: number; productId: number; productName: string; skuSnapshot?: Record<string,string>; unitPrice: Money; quantity: number; checked: boolean; mainImage?: string }; export type AddCartRequest = { skuId: number; quantity: number }; export type CartUpdateRequest = { quantity: number; checked?: boolean }; export type SettlementRequest = { skuIds: number[] }; export type Settlement = { items: CartItem[]; invalidItems?: CartItem[]; totalAmount: Money; payAmount: Money }
export type CreateOrderRequest = { items: { skuId: number; quantity: number }[]; addressId: number }; export type OrderQuery = { status?: string; page?: number; pageSize?: number; startTime?: string; endTime?: string }; export type Order = { orderNo: string; status: string; payAmount: Money; createdAt: string; expireAt?: string }; export type AddressSnapshot = Address | string | null; export type OrderDetail = Order & { items: { productNameSnapshot: string; skuSnapshot?: Record<string,string>; unitPrice: Money; quantity: number; lineAmount: Money }[]; addressSnapshot: AddressSnapshot }; export type PaymentRequest = { orderNo: string; payAmount: string }; export type Payment = { payNo: string; orderNo: string; amount: Money; status: string }
export type SeckillOrderRequest = { activityId: number; skuId: number }; export type SeckillOrderResult = { accepted: boolean; orderNo: string; status: string }; export type SeckillActivity = { activityId: number; skuId: number; status: string; startAt: string; endAt: string; remainingStock: number; perUserLimit: number; product?: Product }

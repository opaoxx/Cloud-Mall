import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api, ApiError, auth, newIdempotencyKey } from './api'
import { formatAddressSnapshot, productDetailToForm, productFormToRequest } from './App'

describe('CloudMall request conventions', () => {
  beforeEach(() => { localStorage.clear(); vi.restoreAllMocks() })

  it('creates unique idempotency keys', () => {
    const a = newIdempotencyKey(); const b = newIdempotencyKey()
    expect(a).not.toBe(b); expect(a).toMatch(/^[0-9a-f-]{36}$/)
  })

  it('adds Bearer and idempotency headers to order creation', async () => {
    auth.set('opaque-token')
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({ code: '0', message: 'OK', data: { orderNo: 'O-1', status: 'PENDING_PAYMENT', payAmount: '12.30', createdAt: '2026-08-29T12:00:00+08:00' } }), { status: 200 }))
    await api.createOrder({ items: [{ skuId: 7, quantity: 2 }], addressId: 3 })
    const [, init] = fetchMock.mock.calls[0]; const headers = new Headers(init?.headers)
    expect(headers.get('Authorization')).toBe('Bearer opaque-token')
    expect(headers.get('Idempotency-Key')).toMatch(/^[0-9a-f-]{36}$/)
    expect(JSON.parse(String(init?.body))).toEqual({ items: [{ skuId: 7, quantity: 2 }], addressId: 3 })
    expect(fetchMock.mock.calls[0][0]).toBe('/api/orders')
  })

  it('sends payment amounts as JSON strings with an idempotency key', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({ code: '0', message: 'OK', data: { payNo: 'P-1', orderNo: 'O-1', amount: '12.30', status: 'PENDING' } }), { status: 200 }))
    await api.createPayment({ orderNo: 'O-1', payAmount: '12.30' })
    const [, init] = fetchMock.mock.calls[0]; const headers = new Headers(init?.headers)
    expect(fetchMock.mock.calls[0][0]).toBe('/api/payments')
    expect(headers.get('Idempotency-Key')).toMatch(/^[0-9a-f-]{36}$/)
    expect(JSON.parse(String(init?.body)).payAmount).toBe('12.30')
  })

  it('exposes business error code and clears an expired token', async () => {
    auth.set('expired')
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({ code: 'COMMON_UNAUTHORIZED', message: '登录已过期', data: null }), { status: 401 }))
    await expect(api.me()).rejects.toMatchObject({ code: 'COMMON_UNAUTHORIZED', status: 401 })
    expect(auth.get()).toBeNull()
  })

  it('turns a network failure into a gateway error', async () => {
    vi.spyOn(globalThis, 'fetch').mockRejectedValue(new TypeError('offline'))
    await expect(api.products()).rejects.toEqual(expect.any(ApiError))
    await expect(api.products()).rejects.toMatchObject({ code: 'GATEWAY_SERVICE_UNAVAILABLE' })
  })

  it('keeps the seckill order number from the accepted async response', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({ code: '0', message: 'OK', data: { accepted: true, orderNo: '202608ABC', status: 'PENDING_PAYMENT' } }), { status: 200 }))
    await expect(api.seckillOrder({ activityId: 9, skuId: 7 })).resolves.toMatchObject({ accepted: true, orderNo: '202608ABC' })
    expect(fetchMock.mock.calls[0][0]).toContain('/seckill/orders')
  })

  it('sends product parameters and SKU specJson using the frozen shapes', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({ code: '0', message: 'OK', data: { id: 1 } }), { status: 200 }))
    const body = productFormToRequest({ name: '茶具', price: '39.90', categoryId: 2 }, '[{"name":"材质","value":"陶瓷"}]', '[{"skuCode":"BLACK","specJson":{"颜色":"黑色"},"price":"39.90","status":true}]')
    await api.createProduct(body)
    expect(JSON.parse(String(fetchMock.mock.calls[0][1]?.body))).toEqual({
      name: '茶具', price: '39.90', categoryId: 2, parameters: [{ name: '材质', value: '陶瓷' }],
      skus: [{ skuCode: 'BLACK', specJson: { 颜色: '黑色' }, price: '39.90', status: true }],
    })
  })

  it('maps product detail response fields back into the editable form', () => {
    expect(productDetailToForm({ id: 1, name: '茶具', price: '39.90', categoryId: 2, status: 1, skus: [{ id: 8, skuCode: 'BLACK', specJson: { 颜色: '黑色' }, price: '39.90', status: true }], parameters: [{ name: '材质', value: '陶瓷' }] })).toEqual({
      name: '茶具', price: '39.90', categoryId: 2, mainImage: '', description: '',
      parameters: [{ name: '材质', value: '陶瓷' }], skus: [{ id: 8, skuCode: 'BLACK', specJson: { 颜色: '黑色' }, price: '39.90', status: true }],
    })
  })

  it('rejects stringified SKU specJson before sending it', () => {
    expect(() => productFormToRequest({ name: '茶具', price: '39.90', categoryId: 2 }, '[]', '[{"skuCode":"BLACK","specJson":"{\\"颜色\\":\\"黑色\\"}","price":"39.90","status":true}]')).toThrow('SKU specJson必须是 JSON 对象')
  })

  it('renders object and JSON-string address snapshots', () => {
    expect(formatAddressSnapshot({ id: 1, receiver: '小明', phone: '13800000000', detailAddress: '云购路 1 号', isDefault: true })).toBe('小明 · 13800000000 · 云购路 1 号')
    expect(formatAddressSnapshot('{"receiver":"小红","phone":"13900000000","detailAddress":"商城路 2 号"}')).toBe('小红 · 13900000000 · 商城路 2 号')
    expect(formatAddressSnapshot('历史地址文本')).toBe('历史地址文本')
  })
})

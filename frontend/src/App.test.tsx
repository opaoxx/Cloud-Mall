import { act } from 'react'
import { createRoot } from 'react-dom/client'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api, ApiError, auth, newIdempotencyKey, registerAndLogin } from './api'
import { formatAddressSnapshot, getSeckillOrderRetryDelay, Pay, PayResult, productDetailToForm, productFormToRequest, resolveImageUrl, Seckill } from './App'
import { isValidPhone, normalizePhone } from './validation'

;(globalThis as Record<string, unknown>).IS_REACT_ACT_ENVIRONMENT = true

describe('CloudMall request conventions', () => {
  beforeEach(() => { localStorage.clear(); vi.restoreAllMocks() })

  it('creates unique idempotency keys', () => {
    const a = newIdempotencyKey(); const b = newIdempotencyKey()
    expect(a).not.toBe(b); expect(a).toMatch(/^[0-9a-f-]{36}$/)
  })

  it('validates and normalizes mainland mobile numbers', () => {
    expect(isValidPhone('13800138000')).toBe(true)
    expect(isValidPhone('1380013800a')).toBe(false)
    expect(isValidPhone('1380013800')).toBe(false)
    expect(normalizePhone('138a00138000x')).toBe('13800138000')
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

  it('turns an empty unauthorized response into a login error', async () => {
    auth.set('expired')
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(null, { status: 401 }))
    await expect(api.addCart({ skuId: 7, quantity: 1 })).rejects.toMatchObject({ code: 'COMMON_UNAUTHORIZED', status: 401 })
    expect(auth.get()).toBeNull()
  })

  it('decodes Chinese category data from a UTF-8 JSON response', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({ code: '0', message: '成功', data: [{ id: 1, parentId: 0, name: '家居百货', sortNo: 1, status: 1 }] }), { status: 200, headers: { 'Content-Type': 'application/json; charset=utf-8' } }))
    await expect(api.categories({ status: 1 })).resolves.toEqual([{ id: 1, parentId: 0, name: '家居百货', sortNo: 1, status: 1 }])
    expect(fetchMock.mock.calls[0][0]).toBe('/api/categories?status=1')
  })

  it('normalizes image paths and keeps a failed image eligible for a local fallback', () => {
    expect(resolveImageUrl('https://cdn.example.test/item.jpg')).toBe('https://cdn.example.test/item.jpg')
    expect(resolveImageUrl('/images/item.jpg')).toBe(`${window.location.origin}/images/item.jpg`)
    expect(resolveImageUrl('')).toBe('')
  })

  it('creates an address with the frozen request fields', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({ code: '0', message: '成功', data: { id: 9, receiver: '小明', phone: '13800000000', detailAddress: '云购路 1 号', isDefault: true } }), { status: 200 }))
    await expect(api.addAddress({ receiver: '小明', phone: '13800000000', detailAddress: '云购路 1 号', isDefault: true })).resolves.toMatchObject({ id: 9 })
    expect(JSON.parse(String(fetchMock.mock.calls[0][1]?.body))).toEqual({ receiver: '小明', phone: '13800000000', detailAddress: '云购路 1 号', isDefault: true })
    expect(new Headers(fetchMock.mock.calls[0][1]?.headers).get('Idempotency-Key')).toMatch(/^[0-9a-f-]{36}$/)
  })

  it('registers a user and immediately creates the login session', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(new Response(JSON.stringify({ code: '0', message: 'OK', data: { userId: 8, username: 'new-user' } }), { status: 200 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ code: '0', message: 'OK', data: { token: 'new-token', user: { id: 8, username: 'new-user', roles: ['USER'] } } }), { status: 200 }))
    await expect(registerAndLogin({ username: 'new-user', password: 'secret123' })).resolves.toMatchObject({ token: 'new-token' })
    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(fetchMock.mock.calls.map(call => call[0])).toEqual(['/api/auth/register', '/api/auth/login'])
  })

  it('keeps the seckill order number from the accepted async response', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({ code: '0', message: 'OK', data: { accepted: true, orderNo: '202608ABC', status: 'PENDING_PAYMENT' } }), { status: 200 }))
    await expect(api.seckillOrder({ activityId: 9, skuId: 7, addressId: 3 })).resolves.toMatchObject({ accepted: true, orderNo: '202608ABC' })
    expect(fetchMock.mock.calls[0][0]).toContain('/seckill/orders')
  })

  it('uses bounded backoff for async seckill order lookup', () => {
    expect(getSeckillOrderRetryDelay(0)).toBe(1000)
    expect(getSeckillOrderRetryDelay(3)).toBe(8000)
    expect(getSeckillOrderRetryDelay(99)).toBe(10000)
  })

  it('keeps accepted async seckill visible until the order is queryable', async () => {
    vi.useFakeTimers()
    const activity = { activityId: 9, skuId: 7, status: 'RUNNING', startAt: '2020-01-01T00:00:00Z', endAt: '2099-01-01T00:00:00Z', remainingStock: 1, perUserLimit: 1 }
    const detail = { orderNo: 'O-ASYNC', status: 'PENDING_PAYMENT', payAmount: '12.30', createdAt: '2026-08-29T12:00:00+08:00', items: [], addressSnapshot: null }
    vi.spyOn(api, 'seckill').mockResolvedValue(activity)
    vi.spyOn(api, 'addresses').mockResolvedValue([{ id: 3, receiver: '小明', phone: '13800138000', detailAddress: '云购路 1 号', isDefault: true }])
    vi.spyOn(api, 'seckillOrder').mockResolvedValue({ accepted: true, orderNo: 'O-ASYNC', status: 'PENDING_PAYMENT' })
    const orderLookup = vi.spyOn(api, 'order').mockRejectedValueOnce(new ApiError('COMMON_INTERNAL_ERROR', '订单尚未生成', 404)).mockResolvedValueOnce(detail)
    const host = document.createElement('div'); document.body.appendChild(host); const root = createRoot(host)
    try {
      await act(async () => { root.render(<MemoryRouter initialEntries={['/seckill/9']}><Routes><Route path="/seckill/:id" element={<Seckill />} /><Route path="/orders/:no" element={<div data-testid="order-detail">订单详情</div>} /></Routes></MemoryRouter>); await Promise.resolve() })
      await act(async () => { await Promise.resolve() })
      await act(async () => { (Array.from(host.querySelectorAll('button')).find(button => button.textContent?.includes('立即秒杀')) as HTMLButtonElement).click(); await Promise.resolve() })
      expect(host.textContent).toContain('订单生成中')
      expect(host.querySelector('[data-testid="order-detail"]')).toBeNull()
      expect(orderLookup).toHaveBeenCalledTimes(1)
      await act(async () => { await vi.advanceTimersByTimeAsync(1000) })
      expect(host.querySelector('[data-testid="order-detail"]')).not.toBeNull()
      expect(orderLookup).toHaveBeenCalledTimes(2)
    } finally { root.unmount(); host.remove(); vi.useRealTimers() }
  })

  it('does not query or present an accepted order when seckill is rejected', async () => {
    const activity = { activityId: 9, skuId: 7, status: 'RUNNING', startAt: '2020-01-01T00:00:00Z', endAt: '2099-01-01T00:00:00Z', remainingStock: 1, perUserLimit: 1 }
    vi.spyOn(api, 'seckill').mockResolvedValue(activity)
    vi.spyOn(api, 'addresses').mockResolvedValue([{ id: 3, receiver: '小明', phone: '13800138000', detailAddress: '云购路 1 号', isDefault: true }])
    vi.spyOn(api, 'seckillOrder').mockResolvedValue({ accepted: false, orderNo: 'O-REJECTED', status: 'UNKNOWN' })
    const orderLookup = vi.spyOn(api, 'order').mockResolvedValue({} as never)
    const host = document.createElement('div'); document.body.appendChild(host); const root = createRoot(host)
    try {
      await act(async () => { root.render(<MemoryRouter initialEntries={['/seckill/9']}><Routes><Route path="/seckill/:id" element={<Seckill />} /></Routes></MemoryRouter>); await Promise.resolve() })
      await act(async () => { await Promise.resolve() })
      await act(async () => { (Array.from(host.querySelectorAll('button')).find(button => button.textContent?.includes('立即秒杀')) as HTMLButtonElement).click(); await Promise.resolve() })
      expect(host.textContent).toContain('订单状态待核实')
      expect(host.textContent).not.toContain('订单生成中')
      expect(host.textContent).not.toContain('支付成功')
      expect(orderLookup).not.toHaveBeenCalled()
    } finally { root.unmount(); host.remove() }
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

describe('payment result recovery', () => {
  beforeEach(() => { localStorage.clear(); vi.restoreAllMocks() })

  const payment = (status: string) => ({ payNo: 'P-1', orderNo: 'O-1', amount: '79.80', status })
  const mount = async (path: string) => {
    const host = document.createElement('div'); document.body.appendChild(host)
    const root = createRoot(host)
    await act(async () => root.render(<MemoryRouter initialEntries={[path]}><Routes><Route path="/pay/:no" element={<Pay />} /><Route path="/pay-result/:no" element={<PayResult />} /></Routes></MemoryRouter>))
    return { host, root }
  }
  const click = async (host: HTMLElement, label: string) => {
    const button = Array.from(host.querySelectorAll('button')).find(item => item.textContent === label)
    if (!button) throw new Error(`Missing button: ${label}`)
    await act(async () => button.click())
  }

  it('follows 202 with read-only status polling and never repeats the debit write', async () => {
    vi.useFakeTimers()
    vi.spyOn(api, 'order').mockResolvedValue({ orderNo: 'O-1', status: 'PENDING_PAYMENT', payAmount: '79.80', createdAt: '2026-11-14T21:00:00+08:00', items: [], addressSnapshot: null })
    const create = vi.spyOn(api, 'createPayment').mockResolvedValue(payment('PENDING'))
    const debit = vi.spyOn(api, 'mockSuccess').mockRejectedValue(new ApiError('PAY_ORDER_SYNC_UNKNOWN', '结果待确认', 202))
    const cancel = vi.spyOn(api, 'mockFail')
    const query = vi.spyOn(api, 'payment').mockResolvedValueOnce(payment('ORDER_SYNC_UNKNOWN')).mockResolvedValue(payment('SUCCESS'))
    const { host, root } = await mount('/pay/O-1')
    try {
      await click(host, '确认支付'); await click(host, '使用余额支付')
      expect(query).toHaveBeenCalledWith('O-1')
      expect(host.querySelector('h2')?.textContent).not.toBe('支付成功')
      await act(async () => { await vi.advanceTimersByTimeAsync(2000) })
      expect(host.querySelector('h2')?.textContent).toBe('支付成功')
      await act(async () => { await vi.advanceTimersByTimeAsync(60000) })
      expect(query).toHaveBeenCalledTimes(2); expect(create).toHaveBeenCalledTimes(1)
      expect(debit).toHaveBeenCalledTimes(1); expect(cancel).not.toHaveBeenCalled()
    } finally { await act(async () => root.unmount()); host.remove(); vi.useRealTimers() }
  })

  it('recovers a temporary query failure and stops on a failed terminal payment', async () => {
    vi.useFakeTimers()
    const query = vi.spyOn(api, 'payment').mockRejectedValueOnce(new ApiError('UNAVAILABLE', '暂不可用', 503)).mockResolvedValue(payment('FAILED'))
    const { host, root } = await mount('/pay-result/O-1')
    try {
      expect(host.textContent).not.toContain('支付成功')
      await act(async () => { await vi.advanceTimersByTimeAsync(2000) })
      expect(host.querySelector('h2')?.textContent).toBe('支付失败')
      await act(async () => { await vi.advanceTimersByTimeAsync(60000) })
      expect(query).toHaveBeenCalledTimes(2)
    } finally { await act(async () => root.unmount()); host.remove(); vi.useRealTimers() }
  })

  it('bounds pending queries and keeps the outcome unconfirmed', async () => {
    vi.useFakeTimers()
    const query = vi.spyOn(api, 'payment').mockResolvedValue(payment('PENDING'))
    const { host, root } = await mount('/pay-result/O-1')
    try {
      await act(async () => { await vi.advanceTimersByTimeAsync(120000) })
      expect(query).toHaveBeenCalledTimes(30)
      expect(host.textContent).toContain('仍待确认')
      expect(host.querySelector('a')?.getAttribute('href')).toBe('/orders/O-1')
      expect(host.textContent).not.toContain('支付成功')
    } finally { await act(async () => root.unmount()); host.remove(); vi.useRealTimers() }
  })

  it('does not schedule another query when an in-flight request finishes after unmount', async () => {
    vi.useFakeTimers()
    let finish!: (value: ReturnType<typeof payment>) => void
    const response = new Promise<ReturnType<typeof payment>>(resolve => { finish = resolve })
    const query = vi.spyOn(api, 'payment').mockReturnValue(response)
    const { host, root } = await mount('/pay-result/O-1')
    try {
      await act(async () => { await vi.advanceTimersByTimeAsync(10000) })
      expect(query).toHaveBeenCalledTimes(1)
      await act(async () => root.unmount())
      await act(async () => { finish(payment('PENDING')); await vi.advanceTimersByTimeAsync(10000) })
      expect(query).toHaveBeenCalledTimes(1)
    } finally { host.remove(); vi.useRealTimers() }
  })

  it('blocks rapid duplicate payment and cancellation writes while the debit is in flight', async () => {
    vi.spyOn(api, 'order').mockResolvedValue({ orderNo: 'O-1', status: 'PENDING_PAYMENT', payAmount: '79.80', createdAt: '2026-11-14T21:00:00+08:00', items: [], addressSnapshot: null })
    vi.spyOn(api, 'createPayment').mockResolvedValue(payment('PENDING'))
    vi.spyOn(api, 'payment').mockResolvedValue(payment('SUCCESS'))
    let finish!: () => void
    const response = new Promise<null>(resolve => { finish = () => resolve(null) })
    const debit = vi.spyOn(api, 'mockSuccess').mockReturnValue(response)
    const cancel = vi.spyOn(api, 'mockFail')
    const { host, root } = await mount('/pay/O-1')
    try {
      await click(host, '确认支付')
      const buttons = Array.from(host.querySelectorAll('button'))
      const submit = buttons.find(button => button.textContent === '使用余额支付')
      const abort = buttons.find(button => button.textContent === '取消支付')
      if (!submit || !abort) throw new Error('Missing payment controls')
      await act(async () => { submit.click(); submit.click(); abort.click() })
      expect(debit).toHaveBeenCalledTimes(1); expect(cancel).not.toHaveBeenCalled()
      expect(submit.disabled).toBe(true); expect(abort.disabled).toBe(true)
      await act(async () => finish())
      expect(host.querySelector('h2')?.textContent).toBe('支付成功')
    } finally { finish(); await act(async () => root.unmount()); host.remove() }
  })
})

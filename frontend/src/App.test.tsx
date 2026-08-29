import { describe, expect, it } from 'vitest'
import { newIdempotencyKey } from './api'
describe('CloudMall request conventions', () => {
  it('creates unique idempotency keys', () => { const a=newIdempotencyKey(), b=newIdempotencyKey(); expect(a).not.toBe(b); expect(a).toMatch(/^[0-9a-f-]{36}$/) })
})

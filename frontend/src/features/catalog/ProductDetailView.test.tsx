import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import type { ProductDetail } from '../../api'
import { ProductDetailView } from './ProductDetailView'

const product: ProductDetail = {
  id: '42', name: '测试商品', categoryId: 3, price: '99.00', status: 1, skus: [],
}

describe('ProductDetailView metric provenance', () => {
  it('explains that imported heat is historical rather than live sales', () => {
    const html = renderToStaticMarkup(
      <ProductDetailView product={product} metricSourceType="LEGACY_IMPORT" />,
    )

    expect(html).toContain('热度数据来源：历史目录导入')
    expect(html).toContain('非实时成交或浏览统计')
  })

  it('does not guess a missing metric source', () => {
    const html = renderToStaticMarkup(
      <ProductDetailView product={product} metricSourceType="UNKNOWN" />,
    )

    expect(html).toContain('热度数据来源尚未确认')
  })
})

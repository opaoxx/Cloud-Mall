import { useEffect, useMemo, useState } from 'react'
import type { ProductDetail, Sku } from '../../api'

export type ProductDetailViewProps = {
  product: ProductDetail
  onAddToCart?: (sku: Sku, quantity: number) => void | Promise<void>
  addingToCart?: boolean
  message?: string
}

export function ProductDetailView({ product, onAddToCart, addingToCart = false, message = '' }: ProductDetailViewProps) {
  const activeSkus = product.skus?.filter(sku => sku.status) || []
  const [selectedSkuId, setSelectedSkuId] = useState<string>(() => String(activeSkus[0]?.id ?? ''))
  const [quantity, setQuantity] = useState(1)
  const [imageFailed, setImageFailed] = useState(false)
  useEffect(() => { if (!selectedSkuId && activeSkus.length > 0) setSelectedSkuId(String(activeSkus[0].id)) }, [activeSkus, selectedSkuId])
  const selectedSku = useMemo(() => activeSkus.find(sku => String(sku.id) === selectedSkuId), [activeSkus, selectedSkuId])
  const displayPrice = selectedSku?.price || product.price
  const productName = product.name || '未命名商品'

  return (
    <article className="detail">
      <div className="detail-image">
        {product.mainImage && !imageFailed ? (
          <img src={product.mainImage} alt={productName} onError={() => setImageFailed(true)} />
        ) : (
          <span className="image-fallback" style={{ display: 'block' }} aria-hidden="true">CloudMall</span>
        )}
      </div>
      <div>
        <p className="eyebrow">PRODUCT DETAIL</p>
        <h2>{productName}</h2>
        <div className="price">¥{Number(displayPrice || 0).toFixed(2)}</div>
        {product.description ? <p>{product.description}</p> : null}
        {activeSkus.length > 0 ? (
          <>
            <h4>选择规格</h4>
            <div className="chips" aria-label="商品规格">
              {activeSkus.map(sku => (
                <button
                  type="button"
                  key={String(sku.id ?? sku.skuCode)}
                  className={selectedSkuId === String(sku.id) ? 'selected' : ''}
                  onClick={() => setSelectedSkuId(String(sku.id))}
                >
                  {Object.values(sku.specJson || {}).join(' / ') || sku.skuCode}
                </button>
              ))}
            </div>
          </>
        ) : null}
        {product.parameters?.length ? (
          <dl>
            {product.parameters.map(parameter => <div key={parameter.name}><dt>{parameter.name}</dt><dd>{parameter.value}</dd></div>)}
          </dl>
        ) : null}
        <label>
          数量
          <input className="qty" type="number" min="1" value={quantity} onChange={event => setQuantity(Math.max(1, Number(event.target.value) || 1))} />
        </label>
        {message ? <div className="alert" role="alert">{message}</div> : null}
        <button className="button" type="button" disabled={!onAddToCart || (activeSkus.length > 0 && !selectedSku) || addingToCart} onClick={() => selectedSku && void onAddToCart?.(selectedSku, quantity)}>
          {addingToCart ? '加入中…' : '加入购物车'}
        </button>
      </div>
    </article>
  )
}

import { useState } from 'react'
import type { Product } from '../../api'

export type ProductCardProps = {
  product: Product
  href?: string
  onClick?: (product: Product) => void
  className?: string
}

export function ProductCard({ product, href, onClick, className = '' }: ProductCardProps) {
  const [imageFailed, setImageFailed] = useState(false)
  const label = product.name || '未命名商品'
  const content = (
    <>
      <div className="product-image" aria-label={`${label}图片`}>
        {product.mainImage && !imageFailed ? (
          <img src={product.mainImage} alt={label} onError={() => setImageFailed(true)} />
        ) : (
          <span className="image-fallback" style={{ display: 'block' }} aria-hidden="true">CM</span>
        )}
      </div>
      <div className="product-info">
        <h3>{label}</h3>
        <p>¥{Number(product.price || 0).toFixed(2)}</p>
      </div>
    </>
  )

  if (href) {
    return <a className={`product-card ${className}`.trim()} href={href}>{content}</a>
  }

  return (
    <button
      type="button"
      className={`product-card ${className}`.trim()}
      onClick={() => onClick?.(product)}
      style={{ display: 'block', width: '100%', padding: 0, textAlign: 'left' }}
    >
      {content}
    </button>
  )
}

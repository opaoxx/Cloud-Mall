import type { Product } from '../../api'
import { ProductCard, type ProductCardProps } from './ProductCard'

export type ProductGridProps = {
  products: Product[]
  getHref?: (product: Product) => string
  onProductClick?: (product: Product) => void
  cardClassName?: ProductCardProps['className']
}

export function ProductGrid({ products, getHref, onProductClick, cardClassName }: ProductGridProps) {
  return (
    <div className="grid" aria-label="商品列表">
      {products.map(product => (
        <ProductCard
          key={String(product.id)}
          product={product}
          href={getHref?.(product)}
          onClick={onProductClick}
          className={cardClassName}
        />
      ))}
    </div>
  )
}

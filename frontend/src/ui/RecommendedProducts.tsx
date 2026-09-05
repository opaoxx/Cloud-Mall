import { Product } from '../api'
import { EmptyState, LoadingSkeleton } from './Feedback'

export type RecommendedProductsProps = {
  products: Product[]
  title?: string
  subtitle?: string
  loading?: boolean
  emptyTitle?: string
  onProductClick?: (product: Product) => void
  productHref?: (product: Product) => string
}

export type ProductGridProps = {
  products: Product[]
  onAction?: (product: Product) => void
  actionLabel?: string
}

const formatMoney = (value: string) => `¥${Number(value || 0).toFixed(2)}`

export function RecommendedProducts({
  products,
  title = '为你推荐',
  subtitle = '根据人气与上新，为你挑选值得逛的好物',
  loading = false,
  emptyTitle,
  onProductClick,
  productHref = (product) => `/products/${product.id}`,
}: RecommendedProductsProps) {
  return (
    <section className="cm-recommended" aria-labelledby="cm-recommended-title">
      <div className="cm-section-heading">
        <div>
          <p className="cm-eyebrow">JUST FOR YOU</p>
          <h2 id="cm-recommended-title">{title}</h2>
          <p>{subtitle}</p>
        </div>
        <a href="/products" className="cm-text-link">查看全部 →</a>
      </div>
      {loading ? <LoadingSkeleton /> : products.length === 0 ? <EmptyState title={emptyTitle} /> : (
        <div className="cm-product-grid">
          {products.map((product) => <ProductCard key={product.id} product={product} href={productHref(product)} onClick={onProductClick} />)}
        </div>
      )}
    </section>
  )
}

export function ProductGrid({ products, onAction, actionLabel = '查看详情' }: ProductGridProps) {
  return (
    <div className="cm-product-grid">
      {products.map((product) => (
        <ProductCard key={product.id} product={product} href={`/products/${product.id}`} onClick={onAction} actionLabel={actionLabel} />
      ))}
    </div>
  )
}

type ProductCardProps = {
  product: Product
  href: string
  onClick?: (product: Product) => void
  actionLabel?: string
}

function ProductCard({ product, href, onClick, actionLabel }: ProductCardProps) {
  return (
    <a className="cm-product-card" href={href} onClick={() => onClick?.(product)}>
      <div className="cm-product-image">
        {product.mainImage ? <img src={product.mainImage} alt="" loading="lazy" /> : <span>CM</span>}
      </div>
      <div className="cm-product-info">
        <h3>{product.name}</h3>
        <p>{product.description || 'CloudMall 精选好物'}</p>
        <strong>{formatMoney(product.price)}</strong>
        {actionLabel ? <span className="cm-product-action">{actionLabel}</span> : null}
      </div>
    </a>
  )
}

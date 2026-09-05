import { Link } from 'react-router-dom'
import { useEffect, useState } from 'react'
import { api, Category, Product } from '../api'

const imageUrl = (value?: string | null) => value && value.trim() ? value : ''
const money = (value?: string | null) => `¥${Number(value || 0).toFixed(2)}`

function ProductTile({ product }: { product: Product }) {
  const [failed, setFailed] = useState(false)
  return <Link className="modern-product" to={`/products/${product.id}`}>
    <div className="modern-product-image">{imageUrl(product.mainImage) && !failed ? <img src={imageUrl(product.mainImage)} alt={product.name} onError={() => setFailed(true)} /> : <span>CM</span>}</div>
    <div className="modern-product-body"><h3>{product.name}</h3><strong>{money(product.price)}</strong></div>
  </Link>
}

export default function ModernHomePage() {
  const [categories, setCategories] = useState<Category[]>([])
  const [products, setProducts] = useState<Product[]>([])
  useEffect(() => {
    Promise.all([api.categories({ status: 1 }), api.products({ status: 1, page: 1, pageSize: 8 })])
      .then(([nextCategories, nextProducts]) => { setCategories(nextCategories); setProducts(nextProducts.items) })
      .catch(() => {})
  }, [])
  return <div className="modern-home">
    <section className="modern-hero">
      <div className="modern-hero-copy"><span className="modern-kicker">CLOUDMALL · 今日好物</span><h1>把喜欢的商品，<em>放心带回家。</em></h1><p>精选商品、透明价格、余额支付，逛完就能完成一笔完整订单。</p><Link className="modern-primary" to="/products">开始逛逛</Link></div>
      <div className="modern-hero-panel"><span>本周精选</span><strong>轻松发现<br />下一件心头好</strong><small>限时秒杀 · 每周上新</small></div>
    </section>
    <section className="modern-section modern-discovery"><div className="modern-section-heading"><div><span className="modern-kicker">DISCOVER</span><h2>按分类逛逛</h2></div><Link to="/products">查看全部 →</Link></div><div className="modern-category-grid"><Link className="modern-category all" to="/products"><b>全部商品</b><span>探索完整商品目录</span></Link>{categories.slice(0, 6).map(category => <Link className="modern-category" key={category.id} to={`/products?categoryId=${category.id}`}><b>{category.name}</b><span>精选好物</span></Link>)}</div></section>
    <section className="modern-section"><div className="modern-section-heading"><div><span className="modern-kicker">FOR YOU</span><h2>今日推荐</h2></div><Link to="/products">更多商品 →</Link></div><div className="modern-product-grid">{products.map(product => <ProductTile key={product.id} product={product} />)}</div></section>
  </div>
}

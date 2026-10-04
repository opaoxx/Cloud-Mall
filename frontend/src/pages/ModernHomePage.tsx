import { Link } from 'react-router-dom'
import { useEffect, useState } from 'react'
import { api, Category, Product, SeckillActivity, SkuSummary } from '../api'

const FEATURED_ACTIVITY_ID = 910000000001
const imageUrl = (value?: string | null) => value && value.trim() ? value : ''
const money = (value?: string | null) => `¥${Number(value || 0).toFixed(2)}`

type FeaturedFlash = { activity: SeckillActivity; sku: SkuSummary; product: Product }

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
  const [flash, setFlash] = useState<FeaturedFlash | null>(null)

  useEffect(() => {
    let active = true
    Promise.all([
      api.categories({ status: 1 }),
      api.products({ status: 1, page: 1, pageSize: 12 }),
      api.seckill(FEATURED_ACTIVITY_ID)
        .then(async activity => {
          const sku = await api.sku(activity.skuId)
          const product = await api.product(sku.productId)
          return { activity, sku, product }
        })
        .catch(() => null),
    ]).then(([nextCategories, nextProducts, nextFlash]) => {
      if (!active) return
      setCategories(nextCategories)
      setProducts(nextProducts.items)
      setFlash(nextFlash)
    }).catch(() => {})
    return () => { active = false }
  }, [])

  const categoryHref = (name: string) => {
    const category = categories.find(item => item.name === name)
    return category ? `/products?categoryId=${category.id}` : '/products'
  }

  return <div className="modern-home">
    <section className="modern-hero">
      <div className="modern-hero-copy"><span className="modern-kicker">CLOUDMALL · 今日好物</span><h1>把喜欢的商品，<em>放心带回家。</em></h1><p>精选商品、透明价格、余额支付，逛完就能完成一笔完整订单。</p><Link className="modern-primary" to="/products">开始逛逛</Link></div>
      <div className="modern-hero-panel"><span>本周精选</span><strong>轻松发现<br />下一件心头好</strong><small>限时秒杀 · 每周上新</small></div>
    </section>
    <section className="modern-benefits" aria-label="购物权益">
      <div><b>🚚 全场包邮</b><span>精选商品直接送到家</span></div>
      <div><b>🛡️ 正品保障</b><span>价格透明，安心下单</span></div>
      <div><b>⚡ 极速发货</b><span>订单状态实时可查</span></div>
      <div><b>💳 余额支付</b><span>结算流程简单快捷</span></div>
    </section>
    <section className="modern-campaigns" aria-label="精选活动">
      <Link className="modern-campaign campaign-green" to="/products?sort=latest"><span>NEW ARRIVALS</span><b>今日上新</b><small>按最新上架顺序发现好物</small><strong>去看看 →</strong></Link>
      <Link className="modern-campaign campaign-orange" to={categoryHref('手机')}><span>SMART PICKS</span><b>数码精选</b><small>通勤、学习和娱乐装备</small><strong>探索数码 →</strong></Link>
      <Link className="modern-campaign campaign-cream" to={categoryHref('牛奶')}><span>EVERYDAY VALUE</span><b>生活补给</b><small>把日常需要一次买齐</small><strong>逛逛食品 →</strong></Link>
    </section>
    <section className="modern-flash-layout" aria-label="限时活动">
      <div className="modern-flash-card">
        <div><span className="modern-kicker">FLASH SALE · 限时秒杀</span><h2>限量好价，手慢就没</h2>{flash ? <><p>{flash.product.name}</p><strong>{money(flash.sku.unitPrice)}</strong><small>剩余 {flash.activity.remainingStock} 件 · 每人限购 {flash.activity.perUserLimit} 件</small><Link className="modern-primary" to={`/seckill/${flash.activity.activityId}`}>立即抢购</Link></> : <><p>秒杀活动正在准备中，稍后刷新即可参与。</p><strong>查看活动 →</strong></>}</div>{flash && <div className="modern-flash-image"><img src={imageUrl(flash.product.mainImage)} alt={flash.product.name} /></div>}
      </div>
      <Link className="modern-coupon-demo" to="/products" aria-label="领券中心演示入口">
        <span>COUPON CENTER · 演示</span><b>领券中心</b><p>满 199 减 20 · 新人专享</p><strong>查看可用商品 →</strong>
      </Link>
    </section>
    <section className="modern-section modern-discovery"><div className="modern-section-heading"><div><span className="modern-kicker">DISCOVER</span><h2>按分类逛逛</h2></div><Link to="/products">查看全部 →</Link></div><div className="modern-category-grid"><Link className="modern-category all" to="/products"><b>全部商品</b><span>探索完整商品目录</span></Link>{categories.slice(0, 10).map(category => <Link className="modern-category" key={category.id} to={`/products?categoryId=${category.id}`}><b>{category.name}</b><span>历史热度参考</span></Link>)}</div></section>
    <section className="modern-section"><div className="modern-section-heading"><div><span className="modern-kicker">FOR YOU</span><h2>今日推荐</h2></div><Link to="/products">更多商品 →</Link></div><div className="modern-product-grid">{products.map(product => <ProductTile key={product.id} product={product} />)}</div></section>
  </div>
}

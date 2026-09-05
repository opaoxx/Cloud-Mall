import { FormEvent, useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { api, Category, Product } from '../api'

const money = (value?: string | null) => `¥${Number(value || 0).toFixed(2)}`
function Tile({ product }: { product: Product }) { const [failed, setFailed] = useState(false); return <Link className="modern-product" to={`/products/${product.id}`}><div className="modern-product-image">{product.mainImage && !failed ? <img src={product.mainImage} alt={product.name} onError={() => setFailed(true)} /> : <span>CM</span>}</div><div className="modern-product-body"><h3>{product.name}</h3><strong>{money(product.price)}</strong></div></Link> }

export default function ModernProductsPage() {
  const [params, setParams] = useSearchParams(); const [keyword, setKeyword] = useState(params.get('keyword') || '')
  const [categories, setCategories] = useState<Category[]>([]); const [products, setProducts] = useState<Product[]>([]); const [total, setTotal] = useState(0); const [loading, setLoading] = useState(true); const [error, setError] = useState('')
  const categoryId = params.get('categoryId') ? Number(params.get('categoryId')) : undefined
  useEffect(() => { api.categories({ status: 1 }).then(setCategories).catch(() => {}); setLoading(true); setError(''); api.products({ keyword: params.get('keyword') || undefined, categoryId, status: 1, page: 1, pageSize: 20 }).then(page => { setProducts(page.items); setTotal(page.total) }).catch(() => setError('商品暂时加载失败，请稍后重试')).finally(() => setLoading(false)) }, [params.toString()])
  const search = (event: FormEvent) => { event.preventDefault(); const next = new URLSearchParams(params); keyword ? next.set('keyword', keyword) : next.delete('keyword'); setParams(next) }
  return <section className="modern-catalog"><div className="modern-catalog-head"><div><span className="modern-kicker">CLOUDMALL MARKET</span><h1>发现好商品</h1><p>{total ? `共找到 ${total} 件商品` : '精选商品，随时准备装进购物车'}</p></div><form className="modern-search" onSubmit={search}><input value={keyword} onChange={event => setKeyword(event.target.value)} placeholder="搜索商品名称" /><button>搜索</button></form></div><div className="modern-filter-bar"><Link className={!categoryId ? 'active' : ''} to="/products">全部</Link>{categories.map(category => <Link className={categoryId === category.id ? 'active' : ''} key={category.id} to={`/products?categoryId=${category.id}`}>{category.name}</Link>)}</div>{error && <div className="modern-alert">{error}</div>}{loading ? <div className="modern-loading">正在加载商品…</div> : products.length ? <div className="modern-product-grid">{products.map(product => <Tile key={product.id} product={product} />)}</div> : <div className="modern-empty">没有找到相关商品</div>}</section>
}

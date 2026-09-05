import { useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { api, auth, type ProductDetail, type Sku } from '../api'
import { ProductDetailView } from '../features/catalog'

export function ProductDetailPage() {
  const { id = '' } = useParams()
  const navigate = useNavigate()
  const [product, setProduct] = useState<ProductDetail | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [message, setMessage] = useState('')
  const [addingToCart, setAddingToCart] = useState(false)

  useEffect(() => {
    let active = true
    setLoading(true)
    setError('')
    api.product(id).then(value => { if (active) setProduct(value) }).catch(() => { if (active) setError('商品详情暂时无法加载') }).finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [id])

  const addToCart = async (sku: Sku, quantity: number) => {
    const skuId = Number(sku.id)
    if (!Number.isSafeInteger(skuId)) { setMessage('商品规格编号无效，请刷新后重试'); return }
    setAddingToCart(true); setMessage('')
    if (!auth.get()) { navigate('/login'); return }
    try { await api.addCart({ skuId, quantity }); navigate('/cart') } catch (e) { setMessage(e instanceof Error ? e.message : '加入购物车失败，请稍后重试') } finally { setAddingToCart(false) }
  }

  if (loading) return <section className="page"><p>正在加载商品详情…</p></section>
  if (error || !product) return <section className="page"><p className="alert" role="alert">{error || '商品不存在'}</p></section>
  return <section className="page"><ProductDetailView product={product} onAddToCart={addToCart} addingToCart={addingToCart} message={message} /></section>
}

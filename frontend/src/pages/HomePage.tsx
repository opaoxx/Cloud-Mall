import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api, type Category, type Product } from '../api'
import { CategoryNav, EmptyState, HeroPromotion, RecommendedProducts, SearchBar } from '../ui'

export type HomePageProps = {
  pageSize?: number
  onProductAction?: (product: Product) => void
}

export default function HomePage({ pageSize = 12, onProductAction }: HomePageProps) {
  const [categories, setCategories] = useState<Category[]>([])
  const [products, setProducts] = useState<Product[]>([])
  const [keyword, setKeyword] = useState('')
  const [activeKeyword, setActiveKeyword] = useState('')
  const [categoryId, setCategoryId] = useState<number | undefined>()
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

  useEffect(() => {
    api.categories().then(setCategories).catch(() => setCategories([]))
  }, [])

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setError('')
    api.products({ keyword: activeKeyword, categoryId, status: 1, page: 1, pageSize, sort: 'hot' })
      .then((result) => { if (!cancelled) setProducts(result.items) })
      .catch(() => { if (!cancelled) setError('商品加载失败，请稍后再试。') })
      .finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [activeKeyword, categoryId, pageSize])

  const submitSearch = (value: string) => setActiveKeyword(value)
  const selectCategory = (nextCategoryId?: number) => setCategoryId(nextCategoryId)

  return (
    <main className="cm-home-page">
      <HeroPromotion
        title={<>好物到手 <em>刚刚好</em></>}
        description="从日常所需到灵感小物，CloudMall 为你整理一份轻松、可靠的购物清单。"
        primaryAction={<a className="cm-primary-button" href="#products">立即探索</a>}
        secondaryAction={<span className="cm-hero-note">本周精选</span>}
      />
      <div className="cm-home-toolbar">
        <SearchBar value={keyword} onChange={setKeyword} onSubmit={(_, value) => submitSearch(value)} />
      </div>
      <CategoryNav categories={categories} selectedCategoryId={categoryId} onSelect={selectCategory} />
      <section id="products" className="cm-home-products" aria-labelledby="recommendations-title">
        {error ? <EmptyState title="暂时无法展示商品" description={error} action={<Link className="cm-secondary-button" to="/">重新加载</Link>} /> : (
          <RecommendedProducts
            products={products}
            loading={loading}
            title={activeKeyword ? `“${activeKeyword}”的搜索结果` : '今日推荐'}
            subtitle="根据热度精选，挑选今天值得带回家的好物"
            onProductClick={onProductAction}
            emptyTitle="还没有匹配的商品"
          />
        )}
      </section>
    </main>
  )
}

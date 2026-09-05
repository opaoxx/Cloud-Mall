import { useEffect, useState } from 'react'
import { api, type Category, type Product, type ProductQuery } from '../../api'
import { CatalogFilters, type CatalogFilterValues } from './CatalogFilters'
import { ProductGrid } from './ProductGrid'

export type SearchResultsProps = {
  filters?: CatalogFilterValues
  onFiltersChange?: (filters: CatalogFilterValues) => void
  getProductHref?: (product: Product) => string
  onProductClick?: (product: Product) => void
  pageSize?: number
  status?: number
}

export function SearchResults({
  filters = {},
  onFiltersChange,
  getProductHref,
  onProductClick,
  pageSize = 20,
  status = 1,
}: SearchResultsProps) {
  const [products, setProducts] = useState<Product[]>([])
  const [categories, setCategories] = useState<Category[]>([])
  const [loading, setLoading] = useState(true)
  const [categoriesLoading, setCategoriesLoading] = useState(true)
  const [error, setError] = useState('')
  const [categoryError, setCategoryError] = useState('')
  const [page, setPage] = useState(1)
  const [total, setTotal] = useState(0)
  const [sort, setSort] = useState('hot')

  useEffect(() => {
    let active = true
    setCategoriesLoading(true)
    api.categories({ status: 1 })
      .then(items => { if (active) setCategories(items) })
      .catch(() => { if (active) setCategoryError('分类暂时无法加载') })
      .finally(() => { if (active) setCategoriesLoading(false) })
    return () => { active = false }
  }, [])

  useEffect(() => {
    let active = true
    setLoading(true)
    setError('')
    const query: ProductQuery = {
      keyword: filters.keyword,
      categoryId: filters.categoryId,
      status,
      page,
      pageSize,
      sort,
    }
    api.products(query)
      .then(result => { if (active) { setProducts(result.items); setTotal(result.total) } })
      .catch(() => { if (active) setError('商品暂时无法加载，请稍后重试') })
      .finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [filters.categoryId, filters.keyword, page, pageSize, sort, status])

  const showFilterError = categoryError && !error
  return (
    <section aria-busy={loading}>
      <CatalogFilters categories={categories} value={filters} onChange={onFiltersChange} loading={categoriesLoading} />
      {showFilterError ? <p className="alert" role="alert">{categoryError}</p> : null}
      {loading ? <p aria-live="polite">正在加载商品…</p> : null}
      {error ? <p className="alert" role="alert">{error}</p> : null}
      {!loading && !error && products.length === 0 ? <p className="empty">暂无符合条件的商品</p> : null}
      {!loading && !error && products.length > 0 ? <><div className="catalog-toolbar"><span>共 {total} 件商品</span><select value={sort} onChange={event => { setSort(event.target.value); setPage(1) }} aria-label="商品排序"><option value="hot">热门排序</option><option value="latest">最新上架</option><option value="price_asc">价格从低到高</option><option value="price_desc">价格从高到低</option></select></div><ProductGrid products={products} getHref={getProductHref} onProductClick={onProductClick} /><div className="catalog-pagination"><button disabled={page <= 1} onClick={() => setPage(value => value - 1)}>上一页</button><span>第 {page} 页</span><button disabled={page * pageSize >= total} onClick={() => setPage(value => value + 1)}>下一页</button></div></> : null}
    </section>
  )
}

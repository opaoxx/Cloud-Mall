import { useEffect, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { SearchResults, type CatalogFilterValues } from '../features/catalog'

export function CatalogPage() {
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const [filters, setFilters] = useState<CatalogFilterValues>(() => readFilters(searchParams))
  useEffect(() => { setFilters(readFilters(searchParams)) }, [searchParams])
  return (
    <section className="page">
      <div className="page-heading"><div><p className="eyebrow">DISCOVER</p><h2>全部商品</h2></div></div>
      <SearchResults filters={filters} onFiltersChange={setFilters} getProductHref={product => `/products/${product.id}`} onProductClick={product => navigate(`/products/${product.id}`)} />
    </section>
  )
}

function readFilters(searchParams: URLSearchParams): CatalogFilterValues {
  const categoryId = searchParams.get('categoryId')
  return {
    keyword: searchParams.get('keyword') || undefined,
    categoryId: categoryId ? Number(categoryId) : undefined,
  }
}

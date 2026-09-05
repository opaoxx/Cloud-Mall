import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { SearchResults, type CatalogFilterValues } from '../features/catalog'

export function CatalogPage() {
  const navigate = useNavigate()
  const [filters, setFilters] = useState<CatalogFilterValues>({})
  return (
    <section className="page">
      <div className="page-heading"><div><p className="eyebrow">DISCOVER</p><h2>全部商品</h2></div></div>
      <SearchResults filters={filters} onFiltersChange={setFilters} getProductHref={product => `/products/${product.id}`} onProductClick={product => navigate(`/products/${product.id}`)} />
    </section>
  )
}

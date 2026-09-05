import { type FormEvent, useState } from 'react'
import type { Category } from '../../api'

export type CatalogFilterValues = {
  keyword?: string
  categoryId?: number
}

export type CatalogFiltersProps = {
  categories?: Category[]
  value?: CatalogFilterValues
  onChange?: (value: CatalogFilterValues) => void
  loading?: boolean
}

export function CatalogFilters({ categories = [], value, onChange, loading = false }: CatalogFiltersProps) {
  const [keyword, setKeyword] = useState(value?.keyword || '')
  const selectedCategory = value?.categoryId

  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    onChange?.({ keyword: keyword.trim() || undefined, categoryId: selectedCategory })
  }

  return (
    <div>
      <form onSubmit={submit} aria-label="商品搜索">
        <input
          value={keyword}
          onChange={event => setKeyword(event.target.value)}
          placeholder="搜索商品名称"
          aria-label="商品名称"
        />
        <button className="button small" type="submit">搜索</button>
      </form>
      <div className="chips" aria-label="商品分类">
        <button type="button" className={selectedCategory === undefined ? 'selected' : ''} onClick={() => onChange?.({ keyword: keyword.trim() || undefined })}>
          全部
        </button>
        {loading ? <span aria-live="polite">分类加载中…</span> : categories.map(category => (
          <button
            type="button"
            key={category.id}
            className={selectedCategory === category.id ? 'selected' : ''}
            onClick={() => onChange?.({ keyword: keyword.trim() || undefined, categoryId: category.id })}
          >
            {category.name}
          </button>
        ))}
      </div>
    </div>
  )
}

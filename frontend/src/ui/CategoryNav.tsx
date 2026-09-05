import { Category } from '../api'

export type CategoryNavProps = {
  categories: Category[]
  selectedCategoryId?: number
  activeCategoryId?: number
  allLabel?: string
  onSelect: (categoryId?: number) => void
}

export function CategoryNav({
  categories,
  selectedCategoryId,
  allLabel = '全部商品',
  onSelect,
  activeCategoryId,
}: CategoryNavProps) {
  const currentCategoryId = selectedCategoryId ?? activeCategoryId
  return (
    <nav className="cm-category-nav" aria-label="商品分类">
      <button
        type="button"
        className={currentCategoryId === undefined ? 'is-selected' : undefined}
        onClick={() => onSelect(undefined)}
      >
        {allLabel}
      </button>
      {categories.map((category) => (
        <button
          type="button"
          className={currentCategoryId === category.id ? 'is-selected' : undefined}
          key={category.id}
          onClick={() => onSelect(category.id)}
        >
          {category.name}
        </button>
      ))}
    </nav>
  )
}

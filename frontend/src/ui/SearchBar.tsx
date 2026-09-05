import { FormEvent, useState } from 'react'

export type SearchBarProps = {
  value?: string
  defaultValue?: string
  placeholder?: string
  onChange?: (value: string) => void
  onSubmit: ((event: FormEvent<HTMLFormElement>, value: string) => void) | ((value: string) => void)
  className?: string
}

export function SearchBar({
  value,
  defaultValue = '',
  placeholder = '搜索商品、品牌或关键词',
  onChange,
  onSubmit,
  className,
}: SearchBarProps) {
  const [internalValue, setInternalValue] = useState(defaultValue)
  const inputValue = value ?? internalValue
  const rootClassName = ['cm-search-bar', className].filter(Boolean).join(' ')

  return (
    <form className={rootClassName} role="search" onSubmit={(event) => {
      if (onSubmit.length > 1) {
        (onSubmit as (event: FormEvent<HTMLFormElement>, value: string) => void)(event, inputValue.trim())
      } else {
        (onSubmit as (value: string) => void)(inputValue.trim())
      }
    }}>
      <span className="cm-search-icon" aria-hidden="true">⌕</span>
      <input
        aria-label="搜索商品"
        value={inputValue}
        placeholder={placeholder}
        onChange={(event) => {
          const nextValue = event.target.value
          if (value === undefined) setInternalValue(nextValue)
          onChange?.(nextValue)
        }}
      />
      <button type="submit">搜索</button>
    </form>
  )
}

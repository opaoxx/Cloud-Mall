import { FormEvent, ReactNode } from 'react'
import { SearchBar, SearchBarProps } from './SearchBar'

export type HeaderNavItem = {
  label: string
  href: string
  active?: boolean
}

export type HeaderProps = {
  navItems?: HeaderNavItem[]
  brandHref?: string
  account?: ReactNode
  cartCount?: number
  search?: Omit<SearchBarProps, 'onSubmit'> & {
    onSubmit?: (value: string) => void
  }
}

export function Header({
  navItems = [],
  brandHref = '/',
  account,
  cartCount = 0,
  search,
}: HeaderProps) {
  const handleSearch = (event: FormEvent<HTMLFormElement>, value: string) => {
    event.preventDefault()
    search?.onSubmit?.(value)
  }

  return (
    <header className="cm-header">
      <a className="cm-brand" href={brandHref} aria-label="CloudMall 首页">
        Cloud<span>Mall</span>
      </a>
      {search ? (
        <SearchBar
          {...search}
          onSubmit={(event, value) => handleSearch(event, value)}
        />
      ) : null}
      <nav className="cm-header-nav" aria-label="主导航">
        {navItems.map((item) => (
          <a className={item.active ? 'is-active' : undefined} href={item.href} key={item.href}>
            {item.label}
          </a>
        ))}
      </nav>
      <div className="cm-header-account">
        <a href="/cart" className="cm-cart-link">
          购物车{cartCount > 0 ? <span className="cm-cart-count">{cartCount}</span> : null}
        </a>
        {account ?? <a href="/login">登录</a>}
      </div>
    </header>
  )
}

import { ReactNode } from 'react'
import { Header, HeaderProps } from './Header'
import './cloudmall-ui.css'

export type AppShellProps = HeaderProps & {
  children: ReactNode
  footer?: ReactNode
}

export function AppShell({ children, footer, ...headerProps }: AppShellProps) {
  return (
    <div className="cm-shell">
      <Header {...headerProps} />
      <main className="cm-main">{children}</main>
      {footer === undefined ? (
        <footer className="cm-footer">CloudMall 云购微商城 · 精选好物，安心购</footer>
      ) : (
        <footer className="cm-footer">{footer}</footer>
      )}
    </div>
  )
}

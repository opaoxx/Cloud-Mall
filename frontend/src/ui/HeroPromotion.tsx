import { ReactNode } from 'react'

export type HeroPromotionProps = {
  eyebrow?: string
  title: ReactNode
  highlight?: ReactNode
  description?: ReactNode
  primaryAction?: ReactNode
  secondaryAction?: ReactNode
  badge?: string
  className?: string
}

export function HeroPromotion({
  eyebrow = 'CLOUDMALL / 云购微商城',
  title,
  highlight,
  description,
  primaryAction,
  secondaryAction,
  badge = '今日精选',
  className,
}: HeroPromotionProps) {
  return (
    <section className={['cm-hero', className].filter(Boolean).join(' ')}>
      <div className="cm-hero-copy">
        <p className="cm-eyebrow">{eyebrow}</p>
        <h1>{title}{highlight ? <> <em>{highlight}</em></> : null}</h1>
        {description ? <p className="cm-hero-description">{description}</p> : null}
        <div className="cm-hero-actions">
          {primaryAction}
          {secondaryAction}
        </div>
      </div>
      <div className="cm-promotion-card">
        <span className="cm-promotion-badge">{badge}</span>
        <strong>把喜欢的好物<br />带回家</strong>
        <span className="cm-promotion-note">精选商品 · 价格透明 · 放心下单</span>
      </div>
    </section>
  )
}

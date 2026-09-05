import { ReactNode } from 'react'

export type LoadingSkeletonProps = {
  count?: number
  variant?: 'card' | 'row'
}

export function LoadingSkeleton({ count = 4, variant = 'card' }: LoadingSkeletonProps) {
  return (
    <div className={`cm-skeleton-list cm-skeleton-${variant}`} aria-label="正在加载" aria-busy="true">
      {Array.from({ length: count }, (_, index) => (
        <div className="cm-skeleton-item" key={index}>
          <span className="cm-skeleton-block" />
          <span className="cm-skeleton-line cm-skeleton-line-wide" />
          <span className="cm-skeleton-line" />
        </div>
      ))}
    </div>
  )
}

export type EmptyStateProps = {
  title?: string
  description?: string
  action?: ReactNode
  actionLabel?: string
  actionHref?: string
}

export function EmptyState({
  title = '这里还没有商品',
  description = '换个关键词试试，或先去逛逛其他分类。',
  action,
  actionLabel,
  actionHref = '/',
}: EmptyStateProps) {
  return (
    <div className="cm-empty-state" role="status">
      <div className="cm-empty-icon" aria-hidden="true">◇</div>
      <h3>{title}</h3>
      <p>{description}</p>
      {action || actionLabel ? <div className="cm-empty-action">{action ?? <a className="cm-button cm-button-quiet" href={actionHref}>{actionLabel}</a>}</div> : null}
    </div>
  )
}

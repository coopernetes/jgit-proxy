import type { PushStatus } from '../types'

const STATUS_CLASSES: Record<string, string> = {
  PENDING:
    'bg-amber-100 text-amber-800 border border-amber-300 dark:bg-amber-900/30 dark:text-amber-300 dark:border-amber-700',
  APPROVED:
    'bg-green-100 text-green-800 border border-green-300 dark:bg-green-900/30 dark:text-green-300 dark:border-green-700',
  FORWARDED:
    'bg-blue-100 text-blue-800 border border-blue-300 dark:bg-blue-900/30 dark:text-blue-300 dark:border-blue-700',
  REJECTED:
    'bg-red-100 text-red-800 border border-red-300 dark:bg-red-900/30 dark:text-red-300 dark:border-red-700',
  DENIED:
    'bg-orange-100 text-orange-800 border border-orange-300 dark:bg-orange-900/30 dark:text-orange-300 dark:border-orange-700',
  CANCELED:
    'bg-gray-100 text-gray-600 border border-gray-300 dark:bg-slate-700 dark:text-gray-300 dark:border-slate-600',
  RECEIVED:
    'bg-slate-100 text-slate-600 border border-slate-300 dark:bg-slate-700 dark:text-slate-300 dark:border-slate-600',
  ERROR:
    'bg-red-200 text-red-900 border border-red-400 dark:bg-red-900/50 dark:text-red-200 dark:border-red-700',
}

interface StatusBadgeProps {
  status: PushStatus | string
  className?: string
}

export function StatusBadge({ status, className = '' }: StatusBadgeProps) {
  const cls =
    STATUS_CLASSES[status] ?? 'bg-gray-100 text-gray-600 dark:bg-slate-700 dark:text-gray-300'
  return (
    <span
      className={`text-xs font-semibold px-2 py-0.5 rounded-full whitespace-nowrap shrink-0 ${cls} ${className}`}
    >
      {status}
    </span>
  )
}

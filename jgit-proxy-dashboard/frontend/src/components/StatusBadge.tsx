import type { PushStatus } from '../types'

const STATUS_CLASSES: Record<string, string> = {
  BLOCKED: 'bg-amber-100 text-amber-800 border border-amber-300',
  APPROVED: 'bg-green-100 text-green-800 border border-green-300',
  FORWARDED: 'bg-blue-100 text-blue-800 border border-blue-300',
  REJECTED: 'bg-red-100 text-red-800 border border-red-300',
  CANCELED: 'bg-gray-100 text-gray-600 border border-gray-300',
  RECEIVED: 'bg-slate-100 text-slate-600 border border-slate-300',
  ERROR: 'bg-red-200 text-red-900 border border-red-400',
}

interface StatusBadgeProps {
  status: PushStatus | string
  className?: string
}

export function StatusBadge({ status, className = '' }: StatusBadgeProps) {
  const cls = STATUS_CLASSES[status] ?? 'bg-gray-100 text-gray-600'
  return (
    <span
      className={`text-xs font-semibold px-2 py-0.5 rounded-full whitespace-nowrap shrink-0 ${cls} ${className}`}
    >
      {status}
    </span>
  )
}

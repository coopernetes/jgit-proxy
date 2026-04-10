import type { RepoPermission } from '../types'

export function PathTypeBadge({ pathType }: { pathType: RepoPermission['pathType'] }) {
  const styles = {
    LITERAL: 'bg-gray-100 text-gray-600',
    GLOB: 'bg-purple-50 text-purple-700',
    REGEX: 'bg-orange-50 text-orange-700',
  }
  return (
    <span
      className={`inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium ${styles[pathType]}`}
    >
      {pathType.toLowerCase()}
    </span>
  )
}

export function OperationsBadge({ operations }: { operations: RepoPermission['operations'] }) {
  const styles: Record<RepoPermission['operations'], string> = {
    PUSH: 'bg-blue-50 text-blue-700',
    REVIEW: 'bg-green-50 text-green-700',
    PUSH_AND_REVIEW: 'bg-teal-50 text-teal-700',
    SELF_CERTIFY: 'bg-yellow-50 text-yellow-700',
  }
  return (
    <span
      className={`inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium ${styles[operations]}`}
    >
      {operations.toLowerCase()}
    </span>
  )
}

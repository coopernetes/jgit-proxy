export type PushStatus =
  | 'BLOCKED'
  | 'APPROVED'
  | 'FORWARDED'
  | 'REJECTED'
  | 'CANCELED'
  | 'RECEIVED'
  | 'ERROR'

export interface Step {
  id: string
  stepName: string
  stepOrder: number
  status: 'PASS' | 'FAIL' | 'BLOCKED' | string
  errorMessage?: string
  blockedMessage?: string
  content?: string
  timestamp?: string
}

export interface Commit {
  sha: string
  message: string
  authorName: string
  authorEmail: string
  committerName?: string
  committerEmail?: string
  signedOffBy?: string[]
}

export interface Attestation {
  type?: 'APPROVAL' | 'REJECTION' | 'CANCELLATION'
  reviewerUsername: string
  reviewerEmail?: string
  reason?: string
  timestamp?: string
}

export interface PushRecord {
  id: string
  status: PushStatus
  project?: string
  repoName?: string
  url?: string
  upstreamUrl?: string
  branch?: string
  commitTo?: string
  commitFrom?: string
  message?: string
  author?: string
  user?: string
  resolvedUser?: string
  scmUsername?: string
  committer?: string
  timestamp?: string | number
  blockedMessage?: string
  errorMessage?: string
  autoApproved?: boolean
  autoRejected?: boolean
  attestation?: Attestation
  commits?: Commit[]
  steps?: Step[]
}

export interface Provider {
  name: string
  uri: string
  host: string
  pushPath: string
  proxyPath: string
}

export interface EmailEntry {
  email: string
  verified: boolean
}

export interface ScmIdentity {
  provider: string
  username: string
  verified: boolean
}

export interface CurrentUser {
  username: string
  emails: EmailEntry[]
  scmIdentities: ScmIdentity[]
}

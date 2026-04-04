export type PushStatus =
  | 'BLOCKED'
  | 'APPROVED'
  | 'FORWARDED'
  | 'REJECTED'
  | 'CANCELED'
  | 'RECEIVED'
  | 'ERROR';

export interface Step {
  id: string;
  stepName: string;
  stepOrder: number;
  status: 'PASS' | 'FAIL' | 'BLOCKED' | string;
  errorMessage?: string;
  blockedMessage?: string;
  content?: string;
}

export interface Commit {
  sha: string;
  message: string;
  authorName: string;
  authorEmail: string;
  committerName?: string;
  committerEmail?: string;
  signedOffBy?: string[];
}

export interface Attestation {
  reviewerUsername: string;
  reviewerEmail?: string;
  reason?: string;
}

export interface PushRecord {
  id: string;
  status: PushStatus;
  project?: string;
  repoName?: string;
  url?: string;
  branch?: string;
  commitTo?: string;
  commitFrom?: string;
  message?: string;
  author?: string;
  user?: string;
  committer?: string;
  timestamp?: string | number;
  blockedMessage?: string;
  attestation?: Attestation;
  commits?: Commit[];
  steps?: Step[];
}

export interface Provider {
  name: string;
  uri: string;
  host: string;
  pushPath: string;
  proxyPath: string;
}

export interface CurrentUser {
  username: string;
  email?: string;
}

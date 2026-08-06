import type { getOctokit } from '@actions/github'

export type Github = ReturnType<typeof getOctokit>
export type { Context } from '@actions/github/lib/context'

import { findLatestReleaseBranchesUpTo } from './releases'
import { Github } from './types'
import { getOctokit } from '@actions/github'

if (require.main === module) {
  const token = process.env.GH_TOKEN
  if (token === undefined) {
    throw new Error('GH_TOKEN environment variable is not set.')
  }
  const github: Github = getOctokit(token)

  const [oldestReleaseBranch] = process.argv.slice(2)

  findLatestReleaseBranchesUpTo(github, oldestReleaseBranch).then(console.log)
}

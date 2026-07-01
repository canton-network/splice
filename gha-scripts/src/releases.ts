import type { Github } from './types'
import { getFileFromGit } from './git'

const spliceRepo = {
  owner: 'canton-network',
  repo: 'splice',
}

export async function findLatestReleaseBranchesUpTo(
  github: Github,
  oldestReleaseBranch: string,
): Promise<Array<string>> {
  const branches: Array<string> = []
  for await (const branch of iterateReleaseLineBranches(github)) {
    branches.push(branch)
    if (branch === oldestReleaseBranch) break
  }
  return branches
}

export async function findNLatestReleaseBranches(
  github: Github,
  numberOfReleases: number,
): Promise<Array<string>> {
  const branches: Array<string> = []
  for await (const branch of iterateReleaseLineBranches(github)) {
    branches.push(branch)
    if (branches.length >= numberOfReleases) break
  }
  return branches
}

async function* iterateReleaseLineBranches(
  github: Github,
  startingBranch: string = 'main',
): AsyncGenerator<string> {
  const latestReleaseContent = await getFileFromGit(
    github,
    { repo: spliceRepo },
    startingBranch,
    'LATEST_RELEASE',
  )
  const latestReleaseBranch = `release-line-${latestReleaseContent.trim()}`
  yield latestReleaseBranch
  yield* iterateReleaseLineBranches(github, latestReleaseBranch)
}

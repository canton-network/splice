import type { Context, Github } from './types'

export async function getFileFromGit(
  github: Github,
  context: Pick<Context, 'repo'>,
  ref: string,
  path: string,
): Promise<string> {
  const { data } = await github.rest.repos.getContent({
    owner: context.repo.owner,
    repo: context.repo.repo,
    path: path,
    ref: ref
  });
  if (Array.isArray(data)) {
    throw new Error(`Expected a single file at path ${path} and ref ${ref}, but got many.`)
  }
  if (data.type === 'file') {
    return Buffer.from(data.content, 'base64').toString('utf-8')
  } else {
    throw new Error(`Expected a file at path ${path} and ref ${ref}, but got type ${data.type}.`)
  }
}

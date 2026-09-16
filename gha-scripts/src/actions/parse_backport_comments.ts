import { parseBackportComments } from '../backports'
import * as core from '@actions/core'
import * as github from '@actions/github'

(async () => {
  const prNumber = Number(core.getInput('pr_number', { required: true }))
  const token = core.getInput('token')

  const backportBranches = await parseBackportComments(
    github.getOctokit(token),
    github.context,
    prNumber,
  )

  core.setOutput('backport_branches', backportBranches)
})()

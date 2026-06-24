import { addBackportReminderComment } from '../backports'
import * as core from '@actions/core'
import * as github from '@actions/github'

const prNumber = Number(core.getInput('pr_number', { required: true }))
const lookupProdClusterConfigs = core.getInput('lookup_prod_cluster_configs', { required: true }).toLowerCase() === 'true'
const token = core.getInput('token')

addBackportReminderComment(
  github.getOctokit(token),
  github.context,
  prNumber,
  lookupProdClusterConfigs,
)

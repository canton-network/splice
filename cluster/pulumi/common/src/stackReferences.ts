// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as pulumi from '@pulumi/pulumi';

import { CLUSTER_BASENAME } from './utils';

export class StackReferences {
  private static refCache: Partial<Record<string, pulumi.StackReference>> = {};

  // Reference to upstream infrastructure stack.
  public static get infra(): pulumi.StackReference {
    const projectName = 'infra';
    const stackName = `${projectName}.${CLUSTER_BASENAME}`;
    return (StackReferences.refCache[stackName] ??= new pulumi.StackReference(
      `organization/${projectName}/${stackName}`
    ));
  }

  public static get cantonNetwork(): pulumi.StackReference {
    const projectName = 'canton-network';
    const stackName = `${projectName}.${CLUSTER_BASENAME}`;
    return (StackReferences.refCache[stackName] ??= new pulumi.StackReference(
      `organization/${projectName}/${stackName}`
    ));
  }

  public static svCanton(sv: string, migrationId: number): pulumi.StackReference {
    const projectName = 'sv-canton';
    const stackName = `${projectName}.${sv}-migration-${migrationId}.${CLUSTER_BASENAME}`;
    return (StackReferences.refCache[stackName] ??= new pulumi.StackReference(
      `organization/${projectName}/${stackName}`
    ));
  }

  private constructor() {}
}

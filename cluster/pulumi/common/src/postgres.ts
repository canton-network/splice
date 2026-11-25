// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as gcp from '@pulumi/gcp';
import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import * as random from '@pulumi/random';
import * as _ from 'lodash';
import { Resource } from '@pulumi/pulumi';

import { CnChartVersion } from './artifacts';
import { clusterSmallDisk, CloudSqlConfig, config, SplicePostgresConfig } from './config';
import { spliceConfig } from './config/config';
import { GcpProject } from './config/gcpConfig';
import {
  appsKubernetesScheduling,
  infraKubernetesScheduling,
  SpliceCustomResourceOptions,
} from './helm';
import { installPostgresPasswordSecret } from './secrets';
import { standardStorageClassName } from './storage/storageClass';
import { CLUSTER_BASENAME, ExactNamespace, GCP_ZONE } from './utils';

const project = gcp.organizations.getProjectOutput({});

// use existing default network (needs to have a private vpc connection)
export const privateNetworkId = pulumi.interpolate`projects/${project.name}/global/networks/default`;

export function generatePassword(
  name: string,
  opts?: pulumi.ResourceOptions
): random.RandomPassword {
  return new random.RandomPassword(
    name,
    {
      length: 16,
      overrideSpecial: '_%@',
      special: true,
    },
    opts
  );
}

export interface PostgresUser {
  readonly userName: string;
  readonly secretName: pulumi.Output<string>;
}

export interface Postgres extends pulumi.Resource {
  readonly instanceName: string;
  readonly namespace: ExactNamespace;

  readonly address: pulumi.Output<string>;
  readonly secretName: pulumi.Output<string>;
  readonly databaseId?: pulumi.Output<string>;

  readonly userName: string;
  readonly database: Resource;

  addUser(userName: string): PostgresUser;
}

export class CloudPostgres
  extends pulumi.ComponentResource<CloudPostgresOutput>
  implements Postgres
{
  address!: pulumi.Output<string>;
  databaseId?: pulumi.Output<string>;
  databaseInstance!: gcp.sql.DatabaseInstance;
  instanceName!: string;
  namespace!: ExactNamespace;
  secretName!: pulumi.Output<string>;
  user!: gcp.sql.User;
  userName!: string;
  zone!: string;
  database!: Resource;

  private name!: string;
  private args!: CloudPostgresResolvedArgs;
  private deletionProtection!: boolean;

  protected async initialize(
    args: CloudPostgresResolvedArgs,
    opts: pulumi.ComponentResourceOptions | undefined,
    name: string
  ): Promise<CloudPostgresOutput> {
    this.name = name;
    this.args = args;
    const {
      active,
      alias,
      cloudSqlConfig,
      deletionProtection,
      existingInstanceName,
      existingSecretName,
      instanceName,
      logicalDecoding,
      migrationId,
      namespace,
      secretName,
      defaultUserName,
      retainDbResourcesOnDelete = false,
    } = args;
    const zone = getCloudSdkZone();

    const databaseInstanceImportOpts =
      existingInstanceName !== undefined
        ? {
            import: existingInstanceName,
            ignoreChanges: ['userLabels'],
          }
        : {};

    const databaseInstance = new gcp.sql.DatabaseInstance(
      name,
      {
        databaseVersion: cloudSqlConfig.databaseVersion,
        // keep always false as this is the terraform provider and cannot be manually removed
        // https://github.com/pulumi/pulumi-gcp/issues/1209
        deletionProtection: false,
        region: config.requireEnv('CLOUDSDK_COMPUTE_REGION'),
        settings: {
          deletionProtectionEnabled: deletionProtection,
          activationPolicy: active ? 'ALWAYS' : 'NEVER',
          databaseFlags: [
            ...Object.keys(cloudSqlConfig.flags).map(name => {
              return { name, value: cloudSqlConfig.flags[name] };
            }),
            ...(logicalDecoding ? [{ name: 'cloudsql.logical_decoding', value: 'on' }] : []),
          ],
          backupConfiguration: {
            enabled: spliceConfig.pulumiProjectConfig.cloudSql.enableAutoBackups,
            pointInTimeRecoveryEnabled: spliceConfig.pulumiProjectConfig.cloudSql.enableAutoBackups,
            startTime: cloudSqlConfig.backupStartTime,
          },
          insightsConfig: {
            queryInsightsEnabled: true,
            enhancedQueryInsightsEnabled: cloudSqlConfig.enterprisePlus,
          },
          tier: cloudSqlConfig.tier,
          edition: cloudSqlConfig.enterprisePlus ? 'ENTERPRISE_PLUS' : 'ENTERPRISE',
          ...(cloudSqlConfig.enterprisePlus
            ? {
                dataCacheConfig: {
                  dataCacheEnabled: true,
                },
              }
            : undefined),
          ipConfiguration: {
            ipv4Enabled: false,
            privateNetwork: privateNetworkId,
            enablePrivatePathForGoogleCloudServices: true,
          },
          userLabels:
            migrationId !== undefined
              ? {
                  cluster: CLUSTER_BASENAME,
                  migration_id: migrationId.toString(),
                }
              : {
                  cluster: CLUSTER_BASENAME,
                },
          locationPreference: {
            // it's fairly critical for performance that the sql instance is in the same zone as the GKE nodes
            zone: zone,
          },
          maintenanceWindow: spliceConfig.pulumiProjectConfig.cloudSql.maintenanceWindow,
        },
      },
      {
        aliases: opts?.aliases,
        parent: this,
        protect: !retainDbResourcesOnDelete && deletionProtection,
        retainOnDelete: retainDbResourcesOnDelete,
        ...databaseInstanceImportOpts,
      }
    );

    const existingDatabase =
      existingInstanceName !== undefined
        ? await gcp.sql.getDatabase({ instance: existingInstanceName, name: 'cantonnet' })
        : undefined;

    const database = new gcp.sql.Database(
      `${namespace.logicalName}-db-${instanceName}-cantonnet`,
      {
        instance: databaseInstance.name,
        name: 'cantonnet',
      },
      {
        parent: this,
        deletedWith: databaseInstance,
        protect: !retainDbResourcesOnDelete && deletionProtection,
        retainOnDelete: retainDbResourcesOnDelete,
        aliases: [{ name: `${namespace.logicalName}-db-${alias}-cantonnet` }],
        import: existingDatabase?.id,
      }
    );

    const defaultUser = this.addInternalUser(defaultUserName, databaseInstance);

    this.address = databaseInstance.privateIpAddress;
    this.databaseId = databaseInstance.name;
    this.databaseInstance = databaseInstance;
    this.instanceName = instanceName;
    this.namespace = namespace;
    this.secretName = defaultUser.secretName;
    this.user = defaultUser.sqlUser;
    this.userName = defaultUser.userName;
    this.zone = zone;
    this.database = database;

    return {
      address: this.address,
      databaseId: this.databaseId,
      secretName: this.secretName,
    };
  }

  addUser(userName: string): PostgresUser {
    return this.addInternalUser(userName, this.databaseInstance);
  }

  private addInternalUser(
    userName: string,
    databaseInstance: gcp.sql.DatabaseInstance
  ): PostgresUser & { sqlUser: gcp.sql.User } {
    const {
      alias,
      defaultUserName,
      deletionProtection,
      existingInstanceName,
      existingSecretName,
      instanceName,
      namespace,
      secretName,
      retainDbResourcesOnDelete,
    } = this.args;
    const isDefaultUser = userName === defaultUserName;
    const resourceName = `${namespace.logicalName}-${instanceName}-${userName}`;
    const password = generatePassword(`${resourceName}-passwd`, {
      parent: this,
      protect: !retainDbResourcesOnDelete && this.deletionProtection,
      //   aliases: [{ name: `${namespace.logicalName}-${alias}-passwd` }],
      aliases: isDefaultUser
        ? [{ name: `${this.name}-passwd` }, { name: `${namespace.logicalName}-${alias}-passwd` }]
        : undefined,
    }).result;
    const k8sSecretName = isDefaultUser ? secretName : `pg-${instanceName}-${userName}-secrets`;
    const passwordSecret = installPostgresPasswordSecret(
      namespace,
      password,
      k8sSecretName,
      existingSecretName,
      retainDbResourcesOnDelete
    );

    const defaultUserOpts = isDefaultUser
      ? {
          aliases: [
            { name: `user-${this.name}` },
            { name: `user-${namespace.logicalName}-${alias}` },
          ],
        }
      : {};
    const userImportOpts =
      existingInstanceName !== undefined && isDefaultUser
        ? {
            import: `${GcpProject}/${existingInstanceName}/${userName}`,
            ignoreChanges: ['password'],
          }
        : {};

    const sqlUser = new gcp.sql.User(
      `user-${resourceName}`,
      {
        instance: databaseInstance.name,
        name: userName,
        password: password,
      },
      {
        parent: this,
        deletedWith: databaseInstance,
        dependsOn: [passwordSecret],
        protect: !retainDbResourcesOnDelete && deletionProtection,
        retainOnDelete: retainDbResourcesOnDelete,
        ...defaultUserOpts,
        ...userImportOpts,
      }
    );

    return {
      userName,
      secretName: passwordSecret.metadata.name,
      sqlUser,
    };
  }

  private constructor(
    name: string,
    args: CloudPostgresArgs,
    opts?: pulumi.ComponentResourceOptions
  ) {
    const resolvedArgs: CloudPostgresResolvedArgs = {
      ...args,
      active: args.active ?? true,
      deletionProtection: (args.disableProtection ?? false) ? false : args.cloudSqlConfig.protected,
      logicalDecoding: args.logicalDecoding ?? false,
      defaultUserName: args.userName ?? defaultUserName,
      retainDbResourcesOnDelete: args.retainDbResourcesOnDelete ?? false,
    };
    super('canton:cloud:postgres', name, resolvedArgs, opts);
  }

  static async install(
    name: string,
    args: CloudPostgresArgs,
    opts?: pulumi.ComponentResourceOptions
  ): Promise<CloudPostgres> {
    const instance = new CloudPostgres(name, args, opts);
    await instance.getData();
    return instance;
  }
}

export type CloudPostgresArgs = {
  active?: boolean;
  alias: string;
  cloudSqlConfig: CloudSqlConfig;
  disableProtection?: boolean;
  existingInstanceName?: string;
  existingSecretName?: string;
  instanceName: string;
  logicalDecoding?: boolean;
  migrationId?: number;
  namespace: ExactNamespace;
  secretName: string;
  userName?: string;
  retainDbResourcesOnDelete?: boolean;
};

type CloudPostgresResolvedArgs = {
  active: boolean;
  alias: string;
  cloudSqlConfig: CloudSqlConfig;
  deletionProtection: boolean;
  existingInstanceName?: string;
  existingSecretName?: string;
  instanceName: string;
  logicalDecoding?: boolean;
  migrationId?: number;
  namespace: ExactNamespace;
  secretName: string;
  defaultUserName: string;
  retainDbResourcesOnDelete: boolean;
};

type CloudPostgresOutput = {
  address: pulumi.Output<string>;
  databaseId?: pulumi.Output<string>;
  secretName: pulumi.Output<string>;
};

type PostgresChartValues = Partial<{
  resources: k8s.types.input.core.v1.ResourceRequirements;
  db: Partial<{
    volumeSize: string;
    maxConnections: number;
    volumeStorageClass: string;
    pvcTemplateName: string;
    maxWalSize: string;
    dataSource: pulumi.Input<k8s.types.input.core.v1.TypedLocalObjectReference>;
  }>;
  appsAffinityAndTolerations: unknown;
}>;

export class SplicePostgres extends pulumi.ComponentResource implements Postgres {
  instanceName: string;
  namespace: ExactNamespace;
  address: pulumi.Output<string>;
  pg: Resource;
  secretName: pulumi.Output<string>;
  userName: string;
  database: Resource;

  constructor(
    xns: ExactNamespace,
    instanceName: string,
    installPassword: (parent: Resource) => k8s.core.v1.Secret,
    splicePostgresConfig: SplicePostgresConfig,
    values?: PostgresChartValues,
    overrideDbSizeFromValues?: boolean,
    disableProtection?: boolean,
    version?: CnChartVersion,
    useinfraKubernetesScheduling: boolean = false,
    resourceOpts?: SpliceCustomResourceOptions
  ) {
    // The suffix is kept so that existing deployments are not renamed.
    const deployedInstanceName = `${instanceName}-helmless`;
    const logicalName = xns.logicalName + '-' + deployedInstanceName;
    super('canton:network:postgres', logicalName, [], {
      ...resourceOpts,
      protect: disableProtection ? false : spliceConfig.pulumiProjectConfig.cloudSql.protected,
      aliases: [],
    });

    const passwordSecret = installPassword(this);
    this.secretName = passwordSecret.metadata.name;

    this.instanceName = deployedInstanceName;
    this.namespace = xns;
    const postgresUser: string = 'cnadmin';
    const postgresDb: string = 'cantonnet';
    this.userName = postgresUser;
    this.address = pulumi.output(
      `${this.instanceName}.${this.namespace.logicalName}.svc.cluster.local`
    );

    const smallDiskSize = clusterSmallDisk ? '240Gi' : undefined;

    const volumeSize = overrideDbSizeFromValues
      ? values?.db?.volumeSize || smallDiskSize || '2800Gi'
      : smallDiskSize || '2800Gi';
    const pvcTemplateName = 'pg-data-hd';
    const volumeStorageClass = standardStorageClassName;
    const maxConnections: number = values?.db?.maxConnections ?? 300;
    const maxWalSize: string = values?.db?.maxWalSize ?? '2GB';
    const imageName: string = splicePostgresConfig.postgresImage;
    const resources = _.merge(
      { limits: { memory: '12Gi' }, requests: { cpu: '0.5', memory: '1Gi' } },
      values?.resources || {}
    );
    const kubernetesScheduling = useinfraKubernetesScheduling
      ? infraKubernetesScheduling
      : appsKubernetesScheduling;

    // ConfigMap for non-secret environment variables
    const configMap = new k8s.core.v1.ConfigMap(
      `${logicalName}-configuration`,
      {
        metadata: {
          name: `${deployedInstanceName}-configuration`,
          namespace: xns.logicalName,
        },
        data: {
          PGDATA: '/var/lib/postgresql/data/pgdata',
          POSTGRES_DB: postgresDb,
          POSTGRES_USER: postgresUser,
          POSTGRES_INITDB_ARGS: '--data-checksums',
        },
      },
      { parent: this, dependsOn: [xns.ns] }
    );

    // StatefulSet using the official postgres image
    const statefulSet = new k8s.apps.v1.StatefulSet(
      logicalName,
      {
        metadata: {
          name: deployedInstanceName,
          namespace: xns.logicalName,
        },
        spec: {
          serviceName: deployedInstanceName,
          replicas: 1,
          selector: { matchLabels: { app: deployedInstanceName } },
          template: {
            metadata: {
              labels: { app: deployedInstanceName, namespace: xns.logicalName },
            },
            spec: {
              securityContext: {
                seccompProfile: { type: 'RuntimeDefault' },
                fsGroup: 999,
                fsGroupChangePolicy: 'OnRootMismatch',
              },
              containers: [
                {
                  name: deployedInstanceName,
                  image: imageName,
                  imagePullPolicy: 'IfNotPresent',
                  securityContext: {
                    runAsNonRoot: true,
                    runAsUser: 999,
                    runAsGroup: 999,
                    allowPrivilegeEscalation: false,
                    privileged: false,
                    capabilities: { drop: ['ALL'] },
                  },
                  args: [
                    '-c',
                    `max_connections=${maxConnections}`,
                    '-c',
                    `max_wal_size=${maxWalSize}`,
                  ],
                  env: [
                    {
                      name: 'POSTGRES_PASSWORD',
                      valueFrom: {
                        secretKeyRef: {
                          name: this.secretName,
                          key: 'postgresPassword',
                        },
                      },
                    },
                  ],
                  envFrom: [{ configMapRef: { name: `${deployedInstanceName}-configuration` } }],
                  livenessProbe: {
                    exec: {
                      command: ['psql', '-U', postgresUser, '-d', 'template1', '-c', 'SELECT 1'],
                    },
                    failureThreshold: 3,
                    periodSeconds: 10,
                    successThreshold: 1,
                    timeoutSeconds: 1,
                  },
                  ports: [{ containerPort: 5432, name: 'postgresdb', protocol: 'TCP' }],
                  resources,
                  volumeMounts: [{ mountPath: '/var/lib/postgresql/data', name: pvcTemplateName }],
                },
              ],
              restartPolicy: 'Always',
              ...kubernetesScheduling,
            },
          },
          volumeClaimTemplates: [
            {
              metadata: { name: pvcTemplateName },
              spec: {
                accessModes: ['ReadWriteOnce'],
                resources: { requests: { storage: volumeSize } },
                storageClassName: volumeStorageClass,
                volumeMode: 'Filesystem',
                ...(values?.db?.dataSource ? { dataSource: values.db.dataSource } : {}),
              },
            },
          ],
        },
      },
      {
        parent: this,
        dependsOn: [passwordSecret, configMap],
        ...(spliceConfig.pulumiProjectConfig.replacePostgresStatefulSetOnChanges
          ? { replaceOnChanges: ['*'], deleteBeforeReplace: true }
          : {}),
      }
    );

    // Headless service for the StatefulSet
    new k8s.core.v1.Service(
      `${logicalName}-svc`,
      {
        metadata: {
          name: deployedInstanceName,
          namespace: xns.logicalName,
        },
        spec: {
          ports: [{ name: 'postgresdb', port: 5432, protocol: 'TCP' }],
          selector: { app: deployedInstanceName },
        },
      },
      { parent: this, dependsOn: [xns.ns] }
    );

    this.pg = statefulSet;
    this.database = statefulSet;

    this.registerOutputs({
      address: statefulSet.id.apply(
        () => `${deployedInstanceName}.${xns.logicalName}.svc.cluster.local`
      ),
      secretName: this.secretName,
    });
  }

  addUser(_userName: string): PostgresUser {
    return {
      userName: 'cnadmin',
      secretName: this.secretName,
    };
  }
}

// toplevel

type SplicePostgresInstallOptions = {
  isActive?: boolean;
  migrationId?: number;
  disableProtection?: boolean;
  logicalDecoding?: boolean;
  userName?: string;
  existingInstanceName?: string;
  existingSecretName?: string;
  retainDbResourcesOnDelete?: boolean;
};

export async function installPostgres(
  xns: ExactNamespace,
  instanceName: string,
  alias: string,
  version: CnChartVersion,
  cloudSqlConfig: CloudSqlConfig,
  splicePostgresConfig: SplicePostgresConfig,
  uniqueSecretName = false,
  opts: SplicePostgresInstallOptions = {}
): Promise<Postgres> {
  const o = { isActive: true, ...opts };
  const secretName = uniqueSecretName ? instanceName + '-secrets' : 'postgres-secrets';
  if (cloudSqlConfig.enabled) {
    return await CloudPostgres.install(
      `${xns.logicalName}-${instanceName}`,
      {
        active: o.isActive,
        alias,
        cloudSqlConfig,
        disableProtection: o.disableProtection,
        existingInstanceName: o.existingInstanceName,
        existingSecretName: o.existingSecretName,
        instanceName,
        logicalDecoding: o.logicalDecoding,
        migrationId: o.migrationId,
        namespace: xns,
        secretName,
        userName: o.userName,
        retainDbResourcesOnDelete: o.retainDbResourcesOnDelete,
      },
      {
        aliases: [{ name: `${xns.logicalName}-${alias}` }],
      }
    );
  } else {
    return installSplicePostgres(
      xns,
      instanceName,
      secretName,
      splicePostgresConfig,
      version,
      opts
    );
  }
}

export function installSplicePostgres(
  xns: ExactNamespace,
  instanceName: string,
  secretName: string,
  splicePostgresConfig: SplicePostgresConfig,
  version?: CnChartVersion,
  opts: SplicePostgresInstallOptions = {},
  chartValues?: PostgresChartValues,
  overrideDbSizeFromValues?: boolean,
  useinfraKubernetesScheduling: boolean = false,
  resourceOpts?: SpliceCustomResourceOptions
): Postgres {
  return new SplicePostgres(
    xns,
    instanceName,
    parent => installPasswordWithParent(parent, xns, instanceName, secretName),
    splicePostgresConfig,
    chartValues,
    overrideDbSizeFromValues,
    opts.disableProtection,
    version,
    useinfraKubernetesScheduling,
    resourceOpts
  );
}

export function installPasswordWithParent(
  parent: Resource,
  xns: ExactNamespace,
  instanceName: string,
  secretName: string
): k8s.core.v1.Secret {
  // Password keeps the same historical name to avoid re-creating it unnecessarily
  const password = generatePassword(`${xns.logicalName}-${instanceName}-passwd`, {
    parent,
    // same name, no parent, because multi-validators were creating their own without a parent
    aliases: [{ parent: undefined, name: `${xns.logicalName}-${instanceName}-passwd` }],
  }).result;
  return installPostgresPasswordSecret(xns, password, secretName);
}

export function getCloudSdkZone(): string {
  const zoneFromEnv = config.optionalEnv('DB_CLOUDSDK_COMPUTE_ZONE') || GCP_ZONE;
  if (!zoneFromEnv) {
    throw new Error(
      'CLOUDSDK_COMPUTE_ZONE is not set in the environment, and DB_CLOUDSDK_COMPUTE_ZONE is also not set. One of these must be set to specify the zone for the Cloud SQL instance.'
    );
  }
  return zoneFromEnv;
}

export const defaultUserName = 'cnadmin';

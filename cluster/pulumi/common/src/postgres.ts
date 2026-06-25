// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as gcp from '@pulumi/gcp';
import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import * as random from '@pulumi/random';
import * as _ from 'lodash';
import { Resource } from '@pulumi/pulumi';

import { CnChartVersion } from './artifacts';
import { clusterSmallDisk, CloudSqlConfig, config } from './config';
import { spliceConfig } from './config/config';
import { GcpProject } from './config/gcpConfig';
import { hyperdiskSupportConfig } from './config/hyperdiskSupportConfig';
import {
  appsAffinityAndTolerations,
  infraAffinityAndTolerations,
  installSpliceHelmChart,
} from './helm';
import { installPostgresPasswordSecret } from './secrets';
import { standardStorageClassName } from './storage/storageClass';
import { createVolumeSnapshot } from './storage/volumeSnapshot';
import {
  ChartValues,
  CLUSTER_BASENAME,
  ExactNamespace,
  GCP_ZONE,
  HELM_CHART_TIMEOUT_SEC,
  HELM_MAX_HISTORY_SIZE,
} from './utils';

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
    const zoneFromEnv = config.optionalEnv('DB_CLOUDSDK_COMPUTE_ZONE') || GCP_ZONE;
    if (!zoneFromEnv) {
      throw new Error(
        'GCP_ZONE is not set in the environment, and DB_CLOUDSDK_COMPUTE_ZONE is also not set. One of these must be set to specify the zone for the Cloud SQL instance.'
      );
    }
    const zone = zoneFromEnv;

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
        databaseVersion: 'POSTGRES_17',
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
            enabled: true,
            pointInTimeRecoveryEnabled: true,
            ...(spliceConfig.pulumiProjectConfig.cloudSql.backupsToRetain
              ? {
                  backupRetentionSettings: {
                    retainedBackups: spliceConfig.pulumiProjectConfig.cloudSql.backupsToRetain,
                  },
                }
              : {}),
          },
          insightsConfig: {
            queryInsightsEnabled: true,
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

    new gcp.sql.Database(
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
      defaultUserName: args.userName ?? 'cnadmin',
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

// @deprecated Use bitnami/postgresql instead. This class installs the splice-postgres chart which
// is frozen at PostgreSQL 14 and will be unsupported after 2026-11-12.
// See https://github.com/canton-network/splice/issues/1129
export class SplicePostgres extends pulumi.ComponentResource implements Postgres {
  instanceName: string;
  namespace: ExactNamespace;
  address: pulumi.Output<string>;
  pg: Resource;
  secretName: pulumi.Output<string>;
  userName: string;

  constructor(
    xns: ExactNamespace,
    instanceName: string,
    alias: string,
    secretName: string,
    values?: ChartValues,
    overrideDbSizeFromValues?: boolean,
    disableProtection?: boolean,
    version?: CnChartVersion,
    useInfraAffinityAndTolerations: boolean = false
  ) {
    const logicalName = xns.logicalName + '-' + instanceName;
    const logicalNameAlias = xns.logicalName + '-' + alias; // pulumi name before #12391
    super('canton:network:postgres', logicalName, [], {
      protect: disableProtection ? false : spliceConfig.pulumiProjectConfig.cloudSql.protected,
      aliases: [{ name: logicalNameAlias, type: 'canton:network:postgres' }],
    });

    this.instanceName = instanceName;
    this.namespace = xns;
    this.userName = 'cnadmin';
    this.address = pulumi.output(
      `${this.instanceName}.${this.namespace.logicalName}.svc.cluster.local`
    );
    const password = generatePassword(`${logicalName}-passwd`, {
      parent: this,
      aliases: [{ name: `${logicalNameAlias}-passwd` }],
    }).result;
    const passwordSecret = installPostgresPasswordSecret(xns, password, secretName);
    this.secretName = passwordSecret.metadata.name;

    // an initial database named cantonnet is created automatically (configured in the Helm chart).
    const smallDiskSize = clusterSmallDisk ? '240Gi' : undefined;
    const supportsHyperdisk = useInfraAffinityAndTolerations
      ? hyperdiskSupportConfig.hyperdiskSupport.enabledForInfra
      : hyperdiskSupportConfig.hyperdiskSupport.enabled;
    const migratingToHyperdisk = useInfraAffinityAndTolerations
      ? hyperdiskSupportConfig.hyperdiskSupport.migratingInfra
      : hyperdiskSupportConfig.hyperdiskSupport.migrating;

    let hyperdiskMigrationValues = {};
    if (supportsHyperdisk && migratingToHyperdisk) {
      const { dataSource } = createVolumeSnapshot({
        resourceName: `pg-data-${xns.logicalName}-${instanceName}-snapshot`,
        snapshotName: `pg-data-${instanceName}-snapshot`,
        namespace: xns.logicalName,
        pvcName: `pg-data-${instanceName}-0`,
      });
      hyperdiskMigrationValues = { dataSource };
    }
    const pg = installSpliceHelmChart(
      xns,
      instanceName,
      'splice-postgres',
      _.merge(values || {}, {
        db: {
          volumeSize: overrideDbSizeFromValues
            ? values?.db?.volumeSize || smallDiskSize
            : smallDiskSize,
          ...(supportsHyperdisk
            ? {
                volumeStorageClass: standardStorageClassName,
                pvcTemplateName: 'pg-data-hd',
                ...hyperdiskMigrationValues,
              }
            : {}),
        },
        persistence: {
          secretName: this.secretName,
        },
      }),
      version,
      {
        aliases: [{ name: logicalNameAlias, type: 'kubernetes:helm.sh/v3:Release' }],
        dependsOn: [passwordSecret],
        ...((supportsHyperdisk &&
          // during the migration we first delete the stateful set, which keeps the old pvcs (stateful sets always keep the pvcs), and then recreate with the new pvcs
          // the stateful sets are immutable so they need to be recreated to force the change of the pvcs
          migratingToHyperdisk) ||
        spliceConfig.pulumiProjectConfig.replacePostgresStatefulSetOnChanges
          ? {
              replaceOnChanges: ['*'],
              deleteBeforeReplace: true,
            }
          : {}),
      },
      true,
      useInfraAffinityAndTolerations ? infraAffinityAndTolerations : appsAffinityAndTolerations
    );
    this.pg = pg;

    this.registerOutputs({
      address: pg.id.apply(() => `${instanceName}.${xns.logicalName}.svc.cluster.local`),
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

// Pulled directly from the upstream bitnami OCI registry, consistent with how every other external
// chart in this repo is consumed (prometheus, istio, pulumi-operator, gha runners all pull from
// their respective upstreams). Note: this is the only external chart sourced from Docker Hub, which
// is rate-limited and whose free bitnami catalog is being pruned — if that becomes a problem, route
// it through the Docker Hub read-through mirror (cluster/pulumi/gha/src/dockerMirror.ts) rather than
// CACHE_GHCR, which only proxies ghcr.io.
const BITNAMI_POSTGRESQL_CHART = 'oci://registry-1.docker.io/bitnamicharts/postgresql';
const BITNAMI_POSTGRESQL_CHART_VERSION = '16.7.27'; // PostgreSQL 17.6.0

export type BitnamiPostgresArgs = {
  secretName: string;
  volumeSize?: string;
  storageClass?: string;
  maxConnections?: number;
  maxWalSize?: string;
  resources?: ChartValues;
  disableProtection?: boolean;
  affinityAndTolerations?: { affinity?: object; tolerations?: object[] };
  dependsOn?: pulumi.Input<pulumi.Resource>[];
};

export class BitnamiPostgres extends pulumi.ComponentResource implements Postgres {
  instanceName: string;
  namespace: ExactNamespace;
  address: pulumi.Output<string>;
  pg: k8s.helm.v3.Release;
  secretName: pulumi.Output<string>;
  userName: string;

  constructor(xns: ExactNamespace, instanceName: string, args: BitnamiPostgresArgs) {
    const logicalName = `${xns.logicalName}-${instanceName}`;
    super('canton:network:bitnami-postgres', logicalName, [], {
      protect: args.disableProtection ? false : spliceConfig.pulumiProjectConfig.cloudSql.protected,
    });

    this.instanceName = instanceName;
    this.namespace = xns;
    this.userName = 'cnadmin';
    this.address = pulumi.output(`${instanceName}.${xns.logicalName}.svc.cluster.local`);

    // Single-key secret (postgresPassword) for the cnadmin user. We set enablePostgresUser=false
    // below, so no postgres-superuser password is needed and the secret shape matches what the old
    // splice-postgres chart used — letting operators reuse their existing secret on migration.
    const userPassword = generatePassword(`${logicalName}-passwd`, { parent: this }).result;
    const passwordSecret = installPostgresPasswordSecret(xns, userPassword, args.secretName);
    this.secretName = passwordSecret.metadata.name;

    const maxConnections = args.maxConnections ?? 300;
    const maxWalSize = args.maxWalSize ?? '2GB';
    const storageClass = args.storageClass ?? standardStorageClassName;
    // Default to the apps node affinity/tolerations so the pod schedules onto cn_apps nodes,
    // matching the behavior of the splice-postgres chart this replaces.
    const { affinity, tolerations } = args.affinityAndTolerations ?? appsAffinityAndTolerations;

    const helmValues: ChartValues = {
      fullnameOverride: instanceName,
      // Chart + images are pulled directly from upstream bitnami (docker.io), consistent with how
      // every other external chart in this repo is consumed. The repo's Docker Hub rate-limit
      // mitigation is an anonymous shared read-through cache wired as a containerd registry-mirror
      // (see cluster/pulumi/gha/src/dockerMirror.ts) — there are no Docker Hub credentials. For
      // app-cluster image pulls to benefit, the node containerd needs that same registry-mirror;
      // that is cluster/node config, not something this chart controls.
      auth: {
        enablePostgresUser: false, // Canton connects as cnadmin only; no postgres superuser password
        username: 'cnadmin',
        database: 'cantonnet',
        existingSecret: args.secretName,
        secretKeys: {
          userPasswordKey: 'postgresPassword',
        },
      },
      primary: {
        initdb: {
          args: '--data-checksums',
          scripts: {
            'grant-createdb.sql': 'ALTER USER cnadmin CREATEDB;',
          },
        },
        extendedConfiguration: `max_connections = ${maxConnections}\nmax_wal_size = ${maxWalSize}\n`,
        persistence: {
          size: args.volumeSize ?? '2800Gi',
          storageClass,
        },
        ...(args.resources ? { resources: args.resources } : {}),
        ...(affinity ? { affinity } : {}),
        ...(tolerations ? { tolerations } : {}),
      },
    };

    this.pg = new k8s.helm.v3.Release(
      logicalName,
      {
        name: instanceName,
        namespace: xns.ns.metadata.name,
        chart: BITNAMI_POSTGRESQL_CHART,
        version: BITNAMI_POSTGRESQL_CHART_VERSION,
        values: helmValues,
        timeout: HELM_CHART_TIMEOUT_SEC,
        maxHistory: HELM_MAX_HISTORY_SIZE,
      },
      {
        parent: this,
        dependsOn: [passwordSecret, xns.ns, ...(args.dependsOn ?? [])],
      }
    );

    this.registerOutputs({
      address: this.pg.id.apply(() => `${instanceName}.${xns.logicalName}.svc.cluster.local`),
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

export async function installPostgres(
  xns: ExactNamespace,
  instanceName: string,
  alias: string,
  version: CnChartVersion,
  cloudSqlConfig: CloudSqlConfig,
  uniqueSecretName = false,
  opts: {
    isActive?: boolean;
    migrationId?: number;
    disableProtection?: boolean;
    logicalDecoding?: boolean;
    userName?: string;
    existingInstanceName?: string;
    existingSecretName?: string;
    retainDbResourcesOnDelete?: boolean;
  } = {}
): Promise<Postgres> {
  const o = { isActive: true, ...opts };
  const secretName = uniqueSecretName ? instanceName + '-secrets' : 'postgres-secrets';
  return cloudSqlConfig.enabled
    ? await CloudPostgres.install(
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
      )
    : new SplicePostgres(
        xns,
        instanceName,
        alias,
        secretName,
        undefined,
        undefined,
        undefined,
        version
      );
}

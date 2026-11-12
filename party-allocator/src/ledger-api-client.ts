// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  Command,
  createConfiguration,
  CreatedEvent,
  DeduplicationPeriod2,
  DefaultApi,
  DisclosedContract,
  Filters,
  GenerateExternalPartyTopologyResponse,
  GetActiveContractsRequest,
  GrantUserRightsResponse,
  HttpAuthAuthentication,
  IdentifierFilter,
  Kind,
  RequestContext,
  ResponseContext,
  ServerConfiguration,
  Signature,
  SignedTransaction,
  SigningPublicKey,
} from "@canton-network/canton-json-api-v2-openapi";
import { AsyncLocalStorage } from "node:async_hooks";
import * as crypto from "node:crypto";
import { logger } from "./logger.js";
import { TokenSource } from "./auth.js";
import {
  cantonErrorCode,
  isTransientError,
  retry,
  RetryOptions,
} from "./retry.js";
import { traceparentHeader } from "./tracing.js";

export type SubmissionInputs = {
  command: Command;
  disclosedContracts: DisclosedContract[];
};

export class LedgerApiClient {
  private api: DefaultApi;
  private als: AsyncLocalStorage<{ url: string | undefined }>;
  constructor(url: string, tokenSource: TokenSource) {
    this.als = new AsyncLocalStorage<{ url: string }>();
    this.api = new DefaultApi(
      createConfiguration({
        baseServer: new ServerConfiguration(url, {}),
        authMethods: {
          default: new HttpAuthAuthentication({
            getToken(): Promise<string> | string {
              return tokenSource.getToken();
            },
          }),
        },
        promiseMiddleware: [
          {
            post: async (context: ResponseContext) => {
              const url = this.als.getStore()?.url || "<unknown>";
              logger.debug(`[Response] ${url} ${context.httpStatusCode}`);
              return context;
            },
            pre: async (context: RequestContext) => {
              const traceparent = traceparentHeader();
              context.setHeaderParam("traceparent", traceparent);
              logger.debug(
                `[Request] ${context.getUrl()} traceparent=${traceparent}`,
              );
              const store = this.als.getStore();
              if (store) {
                store.url = context.getUrl();
              }
              return context;
            },
          },
        ],
      }),
    );
  }

  async generateExternalPartyTopology(
    synchronizer: string,
    partyHint: string,
    publicKey: SigningPublicKey,
  ): Promise<GenerateExternalPartyTopologyResponse> {
    return this.retry("generate external party topology", () =>
      this.als.run({ url: undefined }, () =>
        this.api.postV2PartiesExternalGenerateTopology({
          synchronizer,
          partyHint,
          localParticipantObservationOnly: false,
          confirmationThreshold: 1,
          publicKey,
        }),
      ),
    );
  }

  async allocateExternalParty(
    partyId: string,
    synchronizer: string,
    onboardingTransactions: SignedTransaction[],
    multiHashSignatures: Signature[],
  ): Promise<void> {
    return this.retry(
      `allocate external party ${partyId}`,
      () =>
        this.als.run({ url: undefined }, async () => {
          const response = await this.api.getV2PartiesParty(partyId);
          if (response?.partyDetails?.length === 0) {
            await this.api.postV2PartiesExternalAllocate({
              synchronizer,
              identityProviderId: "",
              onboardingTransactions,
              multiHashSignatures,
            });
          } else {
            logger.info(`Party id ${partyId} is already allocated`);
          }
        }),
      // party allocations take forever so we also retry (almost) forever, including on errors
      // that look permanent as they can be caused by the allocation still being in progress.
      {
        maxRetries: 120,
        shouldRetry: () => true,
        task: "allocate external party",
      },
    );
  }

  // Prepares, signs and executes a transaction. On failure, the whole submission is retried
  // with fresh inputs, e.g. to replace disclosed contracts that have been archived in the meantime.
  // All attempts share one command id so command deduplication prevents executing it twice.
  async submitTransaction(
    description: string,
    synchronizerId: string,
    actAs: string,
    privateKey: string,
    inputs: () => Promise<SubmissionInputs>,
    options: RetryOptions = {},
  ): Promise<void> {
    const commandId = crypto.randomUUID();
    return this.retry(
      `submit ${description}`,
      async () => {
        const { command, disclosedContracts } = await inputs();
        const preparedTransaction = await this.als.run({ url: undefined }, () =>
          this.api.postV2InteractiveSubmissionPrepare({
            userId: "",
            actAs: [actAs],
            readAs: [],
            disclosedContracts,
            commandId,
            synchronizerId,
            verboseHashing: false,
            packageIdSelectionPreference: [],
            commands: [command],
          }),
        );
        const deduplicationPeriod = new DeduplicationPeriod2();
        deduplicationPeriod.Empty = {};
        const signature = crypto.sign(
          null,
          Buffer.from(preparedTransaction.preparedTransactionHash!, "base64"),
          privateKey,
        );
        try {
          await this.als.run({ url: undefined }, () =>
            this.api.postV2InteractiveSubmissionExecute({
              deduplicationPeriod,
              submissionId: crypto.randomUUID(),
              userId: "",
              hashingSchemeVersion: preparedTransaction.hashingSchemeVersion!,
              preparedTransaction: preparedTransaction.preparedTransaction!,
              partySignatures: {
                signatures: [
                  {
                    party: actAs,
                    signatures: [
                      {
                        format: "SIGNATURE_FORMAT_RAW",
                        signingAlgorithmSpec: "SIGNING_ALGORITHM_SPEC_ED25519",
                        signature: signature.toString("base64"),
                        signedBy: actAs.split("::")[1],
                      },
                    ],
                  },
                ],
              },
            }),
          );
        } catch (e: unknown) {
          if (cantonErrorCode(e) === "DUPLICATE_COMMAND") {
            logger.info(
              `Command ${commandId} for ${description} was already accepted`,
            );
            return;
          }
          throw e;
        }
      },
      options,
    );
  }

  async queryContracts(
    parties: string[],
    templateIds: string[],
    retryOptions: RetryOptions = {
      maxRetries: 5,
      shouldRetry: isTransientError,
    },
  ): Promise<CreatedEvent[]> {
    const ledgerEnd = (
      await this.retry(
        "get ledger end",
        () =>
          this.als.run({ url: undefined }, () =>
            this.api.getV2StateLedgerEnd(),
          ),
        retryOptions,
      )
    ).offset;
    const toTemplateFilter = (t: string) => {
      const idFilter = new IdentifierFilter();
      idFilter.TemplateFilter = {
        value: { includeCreatedEventBlob: false, templateId: t },
      };
      return idFilter;
    };
    const filters: Filters = {
      cumulative: templateIds.map((t) => ({
        identifierFilter: toTemplateFilter(t),
      })),
    };
    const request: GetActiveContractsRequest = {
      activeAtOffset: ledgerEnd!,
      eventFormat: {
        verbose: false,
        filtersByParty: Object.fromEntries(parties.map((p) => [p, filters])),
      },
    };
    const responses = await this.retry(
      "query active contracts",
      () =>
        this.als.run({ url: undefined }, () =>
          this.api.postV2StateActiveContracts(request),
        ),
      retryOptions,
    );
    return responses.flatMap((r) =>
      r.contractEntry!.JsActiveContract.createdEvent
        ? [r.contractEntry!.JsActiveContract.createdEvent]
        : [],
    );
  }

  async grantExecuteAndReadAsAnyPartyRights(
    userId: string,
  ): Promise<GrantUserRightsResponse> {
    const executeRight = new Kind();
    executeRight.CanExecuteAsAnyParty = { value: {} };
    // execute does not imply read as so we also need to grant that.
    const readRight = new Kind();
    readRight.CanReadAsAnyParty = { value: {} };
    return this.retry(
      `Grant ExecuteAsAnyParty and ReadAsAnyParty rights to ${userId}`,
      () =>
        this.als.run({ url: undefined }, () =>
          this.api.postV2UsersUserIdRights(userId, {
            userId: userId,
            identityProviderId: "",
            rights: [{ kind: executeRight }, { kind: readRight }],
          }),
        ),
      { task: "grant user rights" },
    );
  }

  async retry<T>(
    description: string,
    task: () => Promise<T>,
    options: RetryOptions = {},
  ): Promise<T> {
    return retry(description, task, options);
  }
}

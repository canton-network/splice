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
import { isTransientError, retry, RetryOptions } from "./retry.js";
import { traceparentHeader } from "./tracing.js";

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
              return Promise.resolve(tokenSource.getToken());
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
      // party allocations take forever so we also retry (almost) forever
      { maxRetries: 120 },
    );
  }

  async submitTransaction(
    description: string,
    synchronizerId: string,
    actAs: string,
    privateKey: string,
    disclosedContracts: DisclosedContract[],
    command: Command,
  ): Promise<void> {
    const preparedTransaction = await this.retry(`prepare ${description}`, () =>
      this.als.run({ url: undefined }, () =>
        this.api.postV2InteractiveSubmissionPrepare({
          userId: "",
          actAs: [actAs],
          readAs: [],
          disclosedContracts,
          commandId: crypto.randomUUID(),
          synchronizerId,
          verboseHashing: false,
          packageIdSelectionPreference: [],
          commands: [command],
        }),
      ),
    );
    const deduplicationPeriod = new DeduplicationPeriod2();
    deduplicationPeriod.Empty = {};
    const signature = crypto.sign(
      null,
      Buffer.from(preparedTransaction.preparedTransactionHash!, "base64"),
      privateKey,
    );

    return this.retry(`execute ${description}`, () =>
      this.als.run({ url: undefined }, () =>
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
      ),
    );
  }

  async queryContracts(
    parties: string[],
    templateIds: string[],
  ): Promise<CreatedEvent[]> {
    const ledgerEnd = (
      await this.retry(
        "get ledger end",
        () =>
          this.als.run({ url: undefined }, () =>
            this.api.getV2StateLedgerEnd(),
          ),
        { maxRetries: 5, shouldRetry: isTransientError },
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
      { maxRetries: 5, shouldRetry: isTransientError },
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

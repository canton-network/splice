// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  DefaultApi as LedgerJsonApi,
  JsTransaction,
} from "@canton-network/canton-json-api-v2-openapi";
import {
  TokenStandardEvent,
  Transaction,
  Holding as HoldingResult,
  Label,
} from "./types";
import { Event as LedgerApiEvent } from "@canton-network/canton-json-api-v2-openapi/dist/models/Event";
import {
  EventLogInterface,
  HoldingInterfaceV2,
  ReasonMetaKey,
} from "../constants";
import { EventLog_HoldingsChange } from "@daml.js/splice-api-token-transfer-events-v2-1.0.0/lib/Splice/Api/Token/TransferEventsV2/module";
import { Holding } from "@daml.js/splice-api-token-holding-v2-1.0.0/lib/Splice/Api/Token/HoldingV2";
import {
  getEventsOfContract,
  getMetaKeyValue,
  getNodeIdAndEvent,
} from "../apis/ledger-api-utils";
import { computeSummary, holdingChangesNonEmpty } from "./summary";

export class V2TransactionParser {
  private readonly ledgerClient: LedgerJsonApi;
  private readonly partyId: string;
  private readonly transaction: JsTransaction;

  constructor(
    transaction: JsTransaction,
    ledgerClient: LedgerJsonApi,
    partyId: string,
  ) {
    this.ledgerClient = ledgerClient;
    this.partyId = partyId;
    this.transaction = transaction;
  }

  async parseTransaction(): Promise<Transaction> {
    const tx = this.transaction;
    const events = await this.parseEvents(tx.events);
    return {
      updateId: tx.updateId,
      offset: tx.offset,
      recordTime: tx.recordTime,
      synchronizerId: tx.synchronizerId,
      events,
    };
  }

  async parseEvents(events: LedgerApiEvent[]): Promise<TokenStandardEvent[]> {
    let callStack: Array<{ parentChoiceName: string; untilNodeId: number }> =
      [];
    const cachedHoldings: Map<string, HoldingResult> = new Map();
    const holdingChanges: TokenStandardEventWithNodeId[] = [];
    const allCreates: ExtractedHolding[] = [];
    const allArchives: ExtractedHolding[] = [];

    // Loosely based on the v1 implementation.
    // We only keep the call stack to be able to determine the parentChoice,
    // which is only necessary when we have a root create that is not explained by an EventLog_HoldingsChange.
    for (const event of events) {
      const { nodeId, createdEvent, archivedEvent, exercisedEvent } =
        getNodeIdAndEvent(event);
      callStack = callStack.filter((s) => s.untilNodeId <= nodeId);
      const parentChoice =
        (callStack[callStack.length - 1] &&
          callStack[callStack.length - 1].parentChoiceName) ||
        "none (root node)";

      if (createdEvent) {
        const parsedCreate = this.extractHoldingCreate(
          createdEvent,
          parentChoice,
        );
        if (parsedCreate) {
          cachedHoldings.set(
            parsedCreate.holding.contractId,
            parsedCreate.holding,
          );
          allCreates.push(parsedCreate);
        }
      } else if (archivedEvent) {
        const parsedArchive = await this.extractHoldingArchive(
          archivedEvent,
          parentChoice,
          cachedHoldings,
        );
        if (parsedArchive) {
          allArchives.push(parsedArchive);
        }
      } else if (exercisedEvent) {
        const holdingsChange =
          this.extractChoiceArgumentEventLog_HoldingsChange(exercisedEvent);
        if (holdingsChange) {
          const parsed = await this.parseHoldingsChange(
            holdingsChange,
            cachedHoldings,
          );
          holdingChanges.push({ event: parsed, nodeId: nodeId });
        }
      } else {
        throw new Error(`Impossible event: ${JSON.stringify(event)}`);
      }
    }

    const accountedForHoldings = new Set(
      holdingChanges
        .map((withNodeId) => withNodeId.event)
        .flatMap((holdingsChange) =>
          holdingsChange.lockedHoldingsChange.archives
            .concat(holdingsChange.lockedHoldingsChange.creates)
            .concat(holdingsChange.unlockedHoldingsChange.archives)
            .concat(holdingsChange.unlockedHoldingsChange.creates),
        )
        .map((holding) => holding.contractId),
    );

    const unaccountedCreates: TokenStandardEventWithNodeId[] = allCreates
      .filter(
        (rawCreate) =>
          rawCreate.holding.owner === this.partyId &&
          !accountedForHoldings.has(rawCreate.holding.contractId),
      )
      .map((rawCreate) => ({
        nodeId: rawCreate.nodeId,
        event: this.buildRawCreate(rawCreate),
      }));
    const unaccountedArchives: TokenStandardEventWithNodeId[] = allArchives
      .filter(
        (rawArchive) =>
          rawArchive.holding.owner === this.partyId &&
          !accountedForHoldings.has(rawArchive.holding.contractId),
      )
      .map((rawArchive) => ({
        nodeId: rawArchive.nodeId,
        event: this.buildRawArchive(rawArchive),
      }));

    const result = holdingChanges
      .filter((change) => holdingChangesNonEmpty(change.event))
      .concat(unaccountedArchives)
      .concat(unaccountedCreates)
      .sort((a, b) => a.nodeId - b.nodeId)
      .map((withNodeId) => withNodeId.event);

    return result;
  }

  extractHoldingCreate(
    createdEvent: LedgerApiEvent["CreatedEvent"],
    parentChoice: string,
  ): ExtractedHolding | null {
    if (!createdEvent || !this.createdEventInvolvesUser(createdEvent)) {
      return null;
    }
    const { interfaceViews } = createdEvent;
    const holdingView = interfaceViews?.find((view) =>
      HoldingInterfaceV2.matches(view.interfaceId),
    );
    if (!holdingView) {
      return null;
    }

    let decodedPayload: Holding;
    try {
      decodedPayload = Holding.decoder.runWithException(holdingView.viewValue);
    } catch (err) {
      console.error(
        `Failed to decode Holding. View: ${holdingView}. Error: ${err}`,
      );
      throw err;
    }
    const result = holdingViewToResult(createdEvent.contractId, decodedPayload);

    return {
      holding: result,
      nodeId: createdEvent.nodeId,
      offset: createdEvent.offset,
      packageName: createdEvent.packageName,
      parentChoice,
      templateId: createdEvent.templateId,
      actingParties: [],
    };
  }

  async extractHoldingArchive(
    archiveEvent:
      | LedgerApiEvent["ExercisedEvent"]
      | LedgerApiEvent["ArchivedEvent"]
      | undefined,
    parentChoice: string,
    cachedHoldings: Map<string, HoldingResult>,
  ): Promise<ExtractedHolding | null> {
    if (
      !archiveEvent ||
      !archiveEvent.implementedInterfaces?.some((interfaceId) =>
        HoldingInterfaceV2.matches(interfaceId),
      )
    ) {
      return null;
    }
    const result = await this.resolveHolding(
      archiveEvent.contractId,
      parentChoice,
      cachedHoldings,
    );
    return (
      result && {
        holding: result,
        nodeId: archiveEvent.nodeId,
        offset: archiveEvent.offset,
        packageName: archiveEvent.packageName,
        parentChoice,
        templateId: archiveEvent.templateId,
        actingParties:
          (archiveEvent as LedgerApiEvent["ExercisedEvent"]).actingParties ||
          [],
      }
    );
  }

  extractChoiceArgumentEventLog_HoldingsChange(
    exercisedEvent: LedgerApiEvent["ExercisedEvent"],
  ): EventLog_HoldingsChange | null {
    const { interfaceId, choice, choiceArgument } = exercisedEvent;

    if (
      interfaceId &&
      EventLogInterface.matches(interfaceId) &&
      choice === "EventLog_HoldingsChange"
    ) {
      return choiceArgument;
    } else {
      return null;
    }
  }

  async parseHoldingsChange(
    holdingsChange: EventLog_HoldingsChange,
    cachedHoldings: Map<string, HoldingResult>,
  ): Promise<TokenStandardEvent> {
    // exclude holdings that are both in input and output
    const inputHoldingCids = holdingsChange.inputHoldingCids.filter(
      (cid) => holdingsChange.outputHoldingCids.indexOf(cid) === -1,
    );
    const outputHoldingCids = holdingsChange.outputHoldingCids.filter(
      (cid) => holdingsChange.inputHoldingCids.indexOf(cid) === -1,
    );

    const resolvedInputHoldings = (
      await Promise.all(
        inputHoldingCids.map((cid) =>
          this.resolveHolding(cid, "EventLog_HoldingsChange", cachedHoldings),
        ),
      )
    ).filter((h) => h !== null);
    const resolvedOutputHoldings = (
      await Promise.all(
        outputHoldingCids.map((cid) =>
          this.resolveHolding(cid, "EventLog_HoldingsChange", cachedHoldings),
        ),
      )
    ).filter((h) => h !== null);

    const unlockedInputHoldings = resolvedInputHoldings.filter((h) => !h.lock);
    const lockedInputHoldings = resolvedInputHoldings.filter((h) => !!h.lock);
    const unlockedOutputHoldings = resolvedOutputHoldings.filter(
      (h) => !h.lock,
    );
    const lockedOutputHoldings = resolvedOutputHoldings.filter((h) => !!h.lock);

    // only this.partyId's holdings should be included in the response
    const unlockedHoldingsChange = {
      creates: unlockedOutputHoldings.filter((h) => h.owner === this.partyId),
      archives: unlockedInputHoldings.filter((h) => h.owner === this.partyId),
    };
    const lockedHoldingsChange = {
      creates: lockedOutputHoldings.filter((h) => h.owner === this.partyId),
      archives: lockedInputHoldings.filter((h) => h.owner === this.partyId),
    };

    return {
      label: this.getLabel(holdingsChange),
      unlockedHoldingsChange,
      unlockedHoldingsChangeSummary: computeSummary(
        unlockedHoldingsChange,
        this.partyId,
      ),
      lockedHoldingsChange,
      lockedHoldingsChangeSummary: computeSummary(
        lockedHoldingsChange,
        this.partyId,
      ),
      transferInstruction: null,
    };
  }

  getLabel(holdingsChange: EventLog_HoldingsChange): Label {
    const reason = getMetaKeyValue(
      ReasonMetaKey,
      holdingsChange.extraArgs.meta,
    );
    return {
      type: "V2",
      admin: holdingsChange.admin,
      account: holdingsChange.account,
      transferLegSides: holdingsChange.transferLegSides,
      reason,
      meta: holdingsChange.extraArgs.meta,
    };
  }

  async resolveHolding(
    cid: string,
    parentChoiceName: string,
    cachedHoldings: Map<string, HoldingResult>,
  ): Promise<HoldingResult | null> {
    const cached = cachedHoldings.get(cid);
    if (cached) {
      return cached;
    }

    const fromEvent = await getEventsOfContract(
      this.ledgerClient,
      cid,
      this.partyId,
      [HoldingInterfaceV2],
    );
    if (
      !fromEvent ||
      !fromEvent.created ||
      !this.createdEventInvolvesUser(fromEvent.created.createdEvent)
    ) {
      return null;
    }

    const holding = this.extractHoldingCreate(
      fromEvent.created.createdEvent,
      parentChoiceName,
    );
    if (!holding) {
      throw new Error(
        `Contract ${cid} should be a Holding but it's not: ${JSON.stringify(fromEvent)}`,
      );
    }
    cachedHoldings.set(cid, holding.holding);
    return holding.holding;
  }

  createdEventInvolvesUser(
    createdEvent: LedgerApiEvent["CreatedEvent"],
  ): boolean {
    return createdEvent.witnessParties
      .concat(createdEvent.signatories)
      .concat(createdEvent.observers || [])
      .some((party) => this.partyId === party);
  }

  buildRawCreate(holding: ExtractedHolding): TokenStandardEvent {
    const lockedHoldingsChange = {
      archives: [],
      creates: holding.holding.lock ? [holding.holding] : [],
    };
    const unlockedHoldingsChange = {
      archives: [],
      creates: !holding.holding.lock ? [holding.holding] : [],
    };
    return {
      label: {
        type: "Create",
        contractId: holding.holding.contractId,
        meta: holding.holding.meta,
        payload: holding,
        templateId: holding.templateId,
        packageName: holding.packageName,
        offset: holding.offset,
        parentChoice: holding.parentChoice,
      },
      lockedHoldingsChange,
      unlockedHoldingsChange,
      lockedHoldingsChangeSummary: computeSummary(
        lockedHoldingsChange,
        this.partyId,
      ),
      unlockedHoldingsChangeSummary: computeSummary(
        unlockedHoldingsChange,
        this.partyId,
      ),
      transferInstruction: null,
    };
  }

  buildRawArchive(holding: ExtractedHolding): TokenStandardEvent {
    const lockedHoldingsChange = {
      creates: [],
      archives: holding.holding.lock ? [holding.holding] : [],
    };
    const unlockedHoldingsChange = {
      creates: [],
      archives: !holding.holding.lock ? [holding.holding] : [],
    };
    return {
      label: {
        type: "Archive",
        contractId: holding.holding.contractId,
        meta: holding.holding.meta,
        payload: holding,
        actingParties: holding.actingParties,
        templateId: holding.templateId,
        packageName: holding.packageName,
        offset: holding.offset,
        parentChoice: holding.parentChoice,
      },
      lockedHoldingsChange,
      unlockedHoldingsChange,
      lockedHoldingsChangeSummary: computeSummary(
        lockedHoldingsChange,
        this.partyId,
      ),
      unlockedHoldingsChangeSummary: computeSummary(
        unlockedHoldingsChange,
        this.partyId,
      ),
      transferInstruction: null,
    };
  }
}

interface ExtractedHolding {
  holding: HoldingResult;
  offset: number;
  parentChoice: string;
  actingParties: string[];
  templateId: string;
  packageName: string;
  nodeId: number;
}

function holdingViewToResult(cid: string, holding: Holding): HoldingResult {
  return {
    contractId: cid,
    amount: holding.amount,
    owner: holding.account.owner ?? "<missing>",
    instrumentId: holding.instrumentId,
    lock: holding.lock
      ? {
          holders: holding.lock.holders,
          context: holding.lock.context || null,
          expiresAfter: holding.lock.expiresAfter?.microseconds || null,
          expiresAt: holding.lock.expiresAt || null,
        }
      : null,
    meta: holding.meta,
  };
}

interface TokenStandardEventWithNodeId {
  nodeId: number;
  event: TokenStandardEvent;
}

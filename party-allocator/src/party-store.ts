// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as crypto from "node:crypto";
import {
  mkdir,
  readdir,
  readFile,
  rename,
  rm,
  writeFile,
} from "node:fs/promises";

export type KeyPair = crypto.KeyPairSyncResult<Buffer, string>;

// Persists the key of each party as <index>_pub.key/<index>_priv.key. Parties whose setup has
// started but not finished are marked by an <index>_pending file so they can be resumed after a restart.
export class PartyStore {
  constructor(private readonly dir: string) {}

  // Returns the next unused party index and the indices of parties with an unfinished setup.
  async load(): Promise<{ nextIndex: number; pending: number[] }> {
    await mkdir(this.dir, { recursive: true });
    let nextIndex = 0;
    const pending: number[] = [];
    for (const name of await readdir(this.dir)) {
      const match = name.match(/^(\d+)_(priv\.key|pending)$/);
      if (match) {
        const index = parseInt(match[1]);
        nextIndex = Math.max(nextIndex, index + 1);
        if (match[2] === "pending") {
          pending.push(index);
        }
      }
    }
    return { nextIndex, pending: pending.sort((a, b) => a - b) };
  }

  async markPending(index: number): Promise<void> {
    await writeFile(this.path(index, "pending"), "");
  }

  async markDone(index: number): Promise<void> {
    await rm(this.path(index, "pending"), { force: true });
  }

  // Loads the key of the party if a previous attempt already created it so the setup can be resumed.
  async loadOrCreateKeyPair(
    index: number,
  ): Promise<{ keyPair: KeyPair; existed: boolean }> {
    const existing = await this.loadKeyPair(index);
    if (existing) {
      return { keyPair: existing, existed: true };
    }
    const keyPair = crypto.generateKeyPairSync("ed25519", {
      publicKeyEncoding: { format: "der", type: "spki" },
      privateKeyEncoding: { format: "pem", type: "pkcs8" },
    });
    // The private key is written last so a key only counts as existing once both files are complete.
    await this.writeAtomically(this.path(index, "pub.key"), keyPair.publicKey);
    await this.writeAtomically(
      this.path(index, "priv.key"),
      keyPair.privateKey,
    );
    return { keyPair, existed: false };
  }

  private async loadKeyPair(index: number): Promise<KeyPair | undefined> {
    try {
      const publicKey = await readFile(this.path(index, "pub.key"));
      const privateKey = await readFile(this.path(index, "priv.key"), "utf8");
      return { publicKey, privateKey };
    } catch (e: unknown) {
      if ((e as NodeJS.ErrnoException).code === "ENOENT") {
        return undefined;
      }
      throw e;
    }
  }

  private async writeAtomically(path: string, data: Buffer | string) {
    const tmp = `${path}.tmp`;
    await writeFile(tmp, data);
    await rename(tmp, path);
  }

  private path(index: number, suffix: string) {
    return `${this.dir}/${index}_${suffix}`;
  }
}

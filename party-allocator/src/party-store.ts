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

export class PartyStoreError extends Error {
  constructor(operation: string, cause: unknown) {
    super(
      `Party store ${operation} failed: ${cause instanceof Error ? cause.message : String(cause)}`,
      { cause },
    );
    this.name = "PartyStoreError";
  }
}

const partiesPerShard = 10_000;
const shardsDir = "shards";
const fileName = /^(\d+)_(priv\.key|pending)$/;

// Persists the key of each party as <index>_pub.key/<index>_priv.key. Parties whose setup has
// started but not finished are marked by an <index>_pending file so they can be resumed after a restart.
// Files are stored in <dir>/shards/<index / 10000>/ since a single directory with millions of entries
// runs out of space in its index (ENOSPC) on ext4. Older versions stored all files directly in <dir>,
// these are still read so existing parties can be resumed.
export class PartyStore {
  private readonly createdShards = new Set<number>();

  constructor(private readonly dir: string) {}

  // Returns the next unused party index and the indices of parties with an unfinished setup.
  async load(): Promise<{ nextIndex: number; pending: number[] }> {
    return this.wrap("load", async () => {
      await mkdir(`${this.dir}/${shardsDir}`, { recursive: true });
      let nextIndex = 0;
      const pending = new Set<number>();
      const scan = (names: string[]) => {
        for (const name of names) {
          const match = name.match(fileName);
          if (match) {
            const index = parseInt(match[1]);
            nextIndex = Math.max(nextIndex, index + 1);
            if (match[2] === "pending") {
              pending.add(index);
            }
          }
        }
      };
      scan(await readdir(this.dir));
      for (const shard of await readdir(`${this.dir}/${shardsDir}`)) {
        scan(await readdir(`${this.dir}/${shardsDir}/${shard}`));
      }
      return { nextIndex, pending: [...pending].sort((a, b) => a - b) };
    });
  }

  async markPending(index: number): Promise<void> {
    return this.wrap("markPending", async () => {
      await this.ensureShard(index);
      await writeFile(this.path(index, "pending"), "");
    });
  }

  async markDone(index: number): Promise<void> {
    return this.wrap("markDone", async () => {
      await rm(this.path(index, "pending"), { force: true });
      await rm(this.legacyPath(index, "pending"), { force: true });
    });
  }

  // Loads the key of the party if a previous attempt already created it so the setup can be resumed.
  async loadOrCreateKeyPair(
    index: number,
  ): Promise<{ keyPair: KeyPair; existed: boolean }> {
    return this.wrap("loadOrCreateKeyPair", async () => {
      const existing =
        (await this.loadKeyPair((s) => this.path(index, s))) ??
        (await this.loadKeyPair((s) => this.legacyPath(index, s)));
      if (existing) {
        return { keyPair: existing, existed: true };
      }
      const keyPair = crypto.generateKeyPairSync("ed25519", {
        publicKeyEncoding: { format: "der", type: "spki" },
        privateKeyEncoding: { format: "pem", type: "pkcs8" },
      });
      await this.ensureShard(index);
      // The private key is written last so a key only counts as existing once both files are complete.
      await this.writeAtomically(
        this.path(index, "pub.key"),
        keyPair.publicKey,
      );
      await this.writeAtomically(
        this.path(index, "priv.key"),
        keyPair.privateKey,
      );
      return { keyPair, existed: false };
    });
  }

  private async loadKeyPair(
    path: (suffix: string) => string,
  ): Promise<KeyPair | undefined> {
    try {
      const publicKey = await readFile(path("pub.key"));
      const privateKey = await readFile(path("priv.key"), "utf8");
      return { publicKey, privateKey };
    } catch (e: unknown) {
      if ((e as NodeJS.ErrnoException).code === "ENOENT") {
        return undefined;
      }
      throw e;
    }
  }

  private async ensureShard(index: number) {
    const shard = Math.floor(index / partiesPerShard);
    if (!this.createdShards.has(shard)) {
      await mkdir(this.shardDir(shard), { recursive: true });
      this.createdShards.add(shard);
    }
  }

  private async writeAtomically(path: string, data: Buffer | string) {
    const tmp = `${path}.tmp`;
    await writeFile(tmp, data);
    await rename(tmp, path);
  }

  private async wrap<T>(operation: string, f: () => Promise<T>): Promise<T> {
    try {
      return await f();
    } catch (e: unknown) {
      throw new PartyStoreError(operation, e);
    }
  }

  private shardDir(shard: number) {
    return `${this.dir}/${shardsDir}/${shard}`;
  }

  private path(index: number, suffix: string) {
    return `${this.shardDir(Math.floor(index / partiesPerShard))}/${index}_${suffix}`;
  }

  private legacyPath(index: number, suffix: string) {
    return `${this.dir}/${index}_${suffix}`;
  }
}

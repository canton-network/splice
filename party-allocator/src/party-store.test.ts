// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import * as crypto from "node:crypto";
import { mkdtemp, readdir, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { PartyStore, PartyStoreError } from "./party-store.js";

describe("PartyStore", () => {
  let dir: string;
  beforeEach(async () => {
    dir = await mkdtemp(`${tmpdir()}/party-store-`);
  });
  afterEach(async () => {
    await rm(dir, { recursive: true, force: true });
  });

  it("starts at 0 in an empty or missing directory", async () => {
    expect(await new PartyStore(`${dir}/keys`).load()).toEqual({
      nextIndex: 0,
      pending: [],
    });
  });

  it("resumes after the highest key and returns unfinished parties", async () => {
    const store = new PartyStore(dir);
    for (const i of [0, 1, 2, 3]) {
      await store.markPending(i);
      await store.loadOrCreateKeyPair(i);
    }
    await store.markDone(0);
    await store.markDone(2);
    // Leftover from a write interrupted by a crash.
    await writeFile(`${dir}/shards/0/7_priv.key.tmp`, "");
    expect(await new PartyStore(dir).load()).toEqual({
      nextIndex: 4,
      pending: [1, 3],
    });
  });

  it("reuses an existing key", async () => {
    const store = new PartyStore(dir);
    const first = await store.loadOrCreateKeyPair(5);
    expect(first.existed).toBe(false);
    const second = await store.loadOrCreateKeyPair(5);
    expect(second.existed).toBe(true);
    expect(second.keyPair.publicKey.equals(first.keyPair.publicKey)).toBe(true);
    expect(second.keyPair.privateKey).toBe(first.keyPair.privateKey);
    const signature = crypto.sign(
      null,
      Buffer.from("hash"),
      second.keyPair.privateKey,
    );
    const publicKey = crypto.createPublicKey({
      key: second.keyPair.publicKey,
      format: "der",
      type: "spki",
    });
    expect(crypto.verify(null, Buffer.from("hash"), publicKey, signature)).toBe(
      true,
    );
    expect((await readdir(`${dir}/shards/0`)).sort()).toEqual([
      "5_priv.key",
      "5_pub.key",
    ]);
  });

  it("creates a new key if only the public key was written", async () => {
    const store = new PartyStore(dir);
    await store.markPending(6);
    await writeFile(`${dir}/shards/0/6_pub.key`, "partial");
    const { keyPair, existed } = await store.loadOrCreateKeyPair(6);
    expect(existed).toBe(false);
    expect(keyPair.publicKey.toString()).not.toBe("partial");
  });

  it("stores parties in shards of 10000", async () => {
    const store = new PartyStore(dir);
    await store.loadOrCreateKeyPair(9_999);
    await store.loadOrCreateKeyPair(10_000);
    await store.markPending(25_000);
    expect((await readdir(`${dir}/shards`)).sort()).toEqual(["0", "1", "2"]);
    expect(await readdir(`${dir}/shards/2`)).toEqual(["25000_pending"]);
    expect(await new PartyStore(dir).load()).toEqual({
      nextIndex: 25_001,
      pending: [25_000],
    });
  });

  it("resumes parties from the legacy flat layout", async () => {
    const legacy = crypto.generateKeyPairSync("ed25519", {
      publicKeyEncoding: { format: "der", type: "spki" },
      privateKeyEncoding: { format: "pem", type: "pkcs8" },
    });
    await writeFile(`${dir}/8_pub.key`, legacy.publicKey);
    await writeFile(`${dir}/8_priv.key`, legacy.privateKey);
    await writeFile(`${dir}/8_pending`, "");
    await writeFile(`${dir}/9_priv.key`, "");
    const store = new PartyStore(dir);
    expect(await store.load()).toEqual({ nextIndex: 10, pending: [8] });

    await store.markPending(8);
    const { keyPair, existed } = await store.loadOrCreateKeyPair(8);
    expect(existed).toBe(true);
    expect(keyPair.privateKey).toBe(legacy.privateKey);
    await store.markDone(8);
    expect(await new PartyStore(dir).load()).toEqual({
      nextIndex: 10,
      pending: [],
    });
  });

  it("wraps file system errors in a PartyStoreError", async () => {
    const store = new PartyStore(dir);
    await store.load();
    // A file where the shard directory should be.
    await writeFile(`${dir}/shards/1`, "");
    await expect(store.loadOrCreateKeyPair(10_000)).rejects.toBeInstanceOf(
      PartyStoreError,
    );
  });
});

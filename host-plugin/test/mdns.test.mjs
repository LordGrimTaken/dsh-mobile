import assert from 'node:assert/strict';
import { test } from 'node:test';

import { buildMdnsRecords, buildMdnsResponse, encodeName, parseQuestionNames, readDnsName } from '../lib/mdns.js';

test('encodeName writes labels terminated by zero', () => {
  assert.deepEqual([...encodeName('_dsh._tcp.local')], [
    4, 95, 100, 115, 104, 4, 95, 116, 99, 112, 5, 108, 111, 99, 97, 108, 0,
  ]);
});

test('record set carries PTR, SRV (with port), TXT and A records', () => {
  const { instance, target, records } = buildMdnsRecords({
    port: 3543,
    hostName: 'studio-pc',
    addresses: ['192.168.1.24'],
    version: '0.2.0',
  });
  assert.equal(instance, 'studio-pc._dsh._tcp.local');
  assert.equal(target, 'studio-pc.local');
  assert.deepEqual(records.map((r) => r.type), [12, 33, 16, 1]);
  const srv = records[1];
  assert.equal(srv.rdata.readUInt16BE(4), 3543);
  const packet = buildMdnsResponse(records);
  assert.equal(packet.readUInt16BE(6), 4); // answer count
  assert.ok(packet.length > 40);
});

test('question parsing extracts browse names', () => {
  const name = encodeName('_dsh._tcp.local');
  const header = Buffer.alloc(12);
  header.writeUInt16BE(1, 4);
  const question = Buffer.concat([name, Buffer.from([0, 12, 0, 1])]);
  const packet = Buffer.concat([header, question]);
  assert.deepEqual(parseQuestionNames(packet), ['_dsh._tcp.local']);
  const decoded = readDnsName(name, 0);
  assert.equal(decoded.name, '_dsh._tcp.local');
});

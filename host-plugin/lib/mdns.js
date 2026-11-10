/**
 * Zero-dependency mDNS responder advertising the Mobile API on the local network.
 *
 * The phone's "Find hosts on this network" uses Android NSD to browse `_dsh._tcp`; this responder
 * answers those queries with PTR/SRV/TXT/A records so no typing IPs is needed. It advertises over
 * multicast only (no cloud), ignores non-matching queries and never throws into the plugin.
 */

import dgram from 'node:dgram';
import os from 'node:os';

const MDNS_ADDRESS = '224.0.0.251';
const MDNS_PORT = 5353;
const SERVICE = '_dsh._tcp.local';
const TTL = 120;

/** DNS name wire encoding (no compression). */
export function encodeName(name) {
  const parts = String(name).split('.').filter(Boolean);
  const chunks = [];
  for (const part of parts) {
    const bytes = Buffer.from(part, 'utf8');
    chunks.push(Buffer.from([bytes.length]), bytes);
  }
  chunks.push(Buffer.from([0]));
  return Buffer.concat(chunks);
}

function resourceRecord(name, type, cls, rdata) {
  const head = Buffer.alloc(10);
  head.writeUInt16BE(type, 0);
  head.writeUInt16BE(cls, 2);
  head.writeUInt32BE(TTL, 4);
  head.writeUInt16BE(rdata.length, 8);
  return Buffer.concat([encodeName(name), head, rdata]);
}

function srvData(port, target) {
  const head = Buffer.alloc(6);
  head.writeUInt16BE(0, 0);
  head.writeUInt16BE(0, 2);
  head.writeUInt16BE(port, 4);
  return Buffer.concat([head, encodeName(target)]);
}

function txtData(entries) {
  return Buffer.concat(
    entries.map((entry) => {
      const bytes = Buffer.from(entry, 'utf8');
      return Buffer.concat([Buffer.from([bytes.length]), bytes]);
    }),
  );
}

function addressData(address) {
  return Buffer.from(address.split('.').map((part) => Number(part) & 0xff));
}

/** Builds the DNS record set for one host (exported for tests). */
export function buildMdnsRecords({ port, hostName, addresses, version }) {
  const instance = `${hostName}.${SERVICE}`;
  const target = `${hostName}.local`;
  const records = [
    { name: SERVICE, type: 12, cls: 1, rdata: encodeName(instance) },
    { name: instance, type: 33, cls: 0x8001, rdata: srvData(port, target) },
    { name: instance, type: 16, cls: 0x8001, rdata: txtData([`v=${version}`, 'api=1']) },
    ...addresses.map((address) => ({ name: target, type: 1, cls: 0x8001, rdata: addressData(address) })),
  ];
  return { instance, target, records };
}

/** Encodes an authoritative response packet for [records]. */
export function buildMdnsResponse(records) {
  const header = Buffer.alloc(12);
  header.writeUInt16BE(0, 0);
  header.writeUInt16BE(0x8400, 2);
  header.writeUInt16BE(0, 4);
  header.writeUInt16BE(records.length, 6);
  header.writeUInt16BE(0, 8);
  header.writeUInt16BE(0, 10);
  return Buffer.concat(
    [header, ...records.map((record) => resourceRecord(record.name, record.type, record.cls, record.rdata))],
  );
}

/** Reads a DNS name at [offset]; returns the lower-cased name and the next offset. */
export function readDnsName(buffer, offset) {
  const labels = [];
  let cursor = offset;
  while (cursor < buffer.length) {
    const length = buffer[cursor];
    if (length === 0) {
      cursor += 1;
      break;
    }
    if ((length & 0xc0) === 0xc0) {
      // Compression pointer: follow it once (queries normally do not use this).
      if (cursor + 1 >= buffer.length) return null;
      const pointer = ((length & 0x3f) << 8) | buffer[cursor + 1];
      const nested = readDnsName(buffer, pointer);
      if (!nested) return null;
      labels.push(nested.name);
      cursor += 2;
      break;
    }
    if (cursor + 1 + length > buffer.length) return null;
    labels.push(buffer.subarray(cursor + 1, cursor + 1 + length).toString('utf8'));
    cursor += 1 + length;
  }
  return { name: labels.join('.').toLowerCase(), offset: cursor };
}

/** Question names in a DNS query; [] when malformed. */
export function parseQuestionNames(message) {
  if (!message || message.length < 12) return [];
  const count = message.readUInt16BE(4);
  let offset = 12;
  const names = [];
  for (let index = 0; index < count; index += 1) {
    const decoded = readDnsName(message, offset);
    if (!decoded) break;
    names.push(decoded.name);
    offset = decoded.offset + 4;
  }
  return names;
}

/**
 * Starts advertising; returns an idempotent stop function. Safe to call when the network is
 * unavailable — it logs once and returns a no-op.
 */
export function startMdnsAdvertiser({ port, hostName, version, logger = console }) {
  let socket = null;
  let timer = null;
  let stopped = false;
  try {
    const addresses = Object.values(os.networkInterfaces())
      .flat()
      .filter((entry) => entry && entry.family === 'IPv4' && !entry.internal)
      .map((entry) => entry.address);
    const safeHost =
      String(hostName || os.hostname() || 'dsh')
        .replace(/[^a-zA-Z0-9-]/g, '-')
        .replace(/^-+|-+$/g, '')
        .slice(0, 40) || 'dsh';
    const { instance, target, records } = buildMdnsRecords({
      port,
      hostName: safeHost,
      addresses: addresses.length ? addresses : ['127.0.0.1'],
      version,
    });
    const packet = buildMdnsResponse(records);
    const names = new Set([SERVICE.toLowerCase(), instance.toLowerCase(), target.toLowerCase()]);

    socket = dgram.createSocket({ type: 'udp4', reuseAddr: true });
    socket.on('error', (error) => logger.warn?.(`[dsh-mobile-host] mDNS error: ${String(error?.message ?? error)}`));
    socket.on('message', (message) => {
      try {
        if (!message || message.length < 12) return;
        const flags = message.readUInt16BE(2);
        if ((flags & 0x8000) !== 0) return; // ignore responses
        const questions = parseQuestionNames(message);
        if (!questions.some((name) => names.has(name))) return;
        socket.send(packet, MDNS_PORT, MDNS_ADDRESS);
      } catch {
        /* malformed packets are ignored */
      }
    });
    socket.bind(MDNS_PORT, () => {
      if (stopped) return;
      try {
        socket.addMembership(MDNS_ADDRESS);
        socket.setMulticastTTL(255);
        socket.send(packet, MDNS_PORT, MDNS_ADDRESS);
        timer = setInterval(() => socket?.send(packet, MDNS_PORT, MDNS_ADDRESS), 60_000);
        timer.unref?.();
      } catch (error) {
        logger.warn?.(`[dsh-mobile-host] mDNS setup failed: ${String(error?.message ?? error)}`);
      }
    });
  } catch (error) {
    logger.warn?.(`[dsh-mobile-host] could not start mDNS: ${String(error?.message ?? error)}`);
    try {
      socket?.close();
    } catch {
      /* ignore */
    }
    return () => {};
  }
  return () => {
    if (stopped) return;
    stopped = true;
    if (timer) clearInterval(timer);
    try {
      socket?.close();
    } catch {
      /* already closed */
    }
  };
}

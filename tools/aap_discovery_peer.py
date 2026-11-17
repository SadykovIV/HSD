#!/usr/bin/env python3
"""Local-only independent AAP receiver. OpenSSL TLS, hand-written framing/protobuf, no HSD parser."""
from pathlib import Path
import argparse, json, socketserver, ssl, struct, threading, time, zlib

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
CERTIFICATE = ROOT / "android/app/src/androidTest/assets/aap-interop/receiver.pem"
PRIVATE_KEY = None
RESULTS = None
LOCK = threading.Lock()

def varint(value):
    out = bytearray()
    while value > 127:
        out.append((value & 127) | 128)
        value >>= 7
    return bytes(out + bytes([value]))

def blob(number, data):
    if isinstance(data, str): data = data.encode()
    return varint(number * 8 + 2) + varint(len(data)) + data

def fields(data):
    pos = 0
    def readvar():
        nonlocal pos
        value = 0
        for shift in range(0, 70, 7):
            b = data[pos]; pos += 1
            value |= (b & 127) << shift
            if b < 128: return value
        raise ValueError('Invalid protobuf varint')
    result = {}
    while pos < len(data):
        tag = readvar(); kind = tag & 7; number = tag >> 3
        assert number not in result, 'Duplicate field'
        if kind == 2:
            size = readvar(); result[number] = data[pos:pos+size]; pos += size
            assert len(result[number]) == size
        elif kind == 0: result[number] = readvar()
        else: raise ValueError(f'Unexpected wire type {kind}')
    assert pos == len(data)
    return result

def check_png(data, size):
    assert data[:8] == b'\x89PNG\r\n\x1a\n'
    assert struct.unpack('>II', data[16:24]) == (size, size)
    pos = 8; pixels = bytearray(); ended = False
    while pos < len(data):
        length, = struct.unpack('>I', data[pos:pos+4])
        chunk = data[pos+4:pos+8+length]
        crc, = struct.unpack('>I', data[pos+8+length:pos+12+length])
        assert zlib.crc32(chunk) == crc
        if chunk[:4] == b'IDAT': pixels.extend(chunk[4:])
        if chunk[:4] == b'IEND': ended = True
        pos += 12 + length
    assert pos == len(data) and ended and len(zlib.decompress(pixels)) > 0

class Peer(socketserver.BaseRequestHandler):
    def exact(self, size):
        out = bytearray()
        while len(out) < size:
            part = self.request.recv(size - len(out))
            if not part: raise EOFError('Phone closed before expected frame')
            out.extend(part)
        return bytes(out)

    def frame(self):
        channel, flags, length = struct.unpack('>BBH', self.exact(4))
        total = struct.unpack('>I', self.exact(4))[0] if flags & 3 == 1 else None
        return channel, flags, total, self.exact(length)

    @staticmethod
    def packet(flags, data, total=None):
        return struct.pack('>BBH', 0, flags, len(data)) + (struct.pack('>I', total) if total is not None else b'') + data

    def plain(self, opcode, body):
        self.request.sendall(self.packet(3, struct.pack('>H', opcode) + body))

    def handshake(self):
        while True:
            try:
                self.tls.do_handshake()
                if self.outgoing.pending: self.plain(3, self.outgoing.read())
                return
            except ssl.SSLWantReadError:
                if self.outgoing.pending: self.plain(3, self.outgoing.read())
                channel, flags, total, data = self.frame()
                assert channel == 0 and flags == 3 and total is None and data[:2] == b'\0\3'
                self.incoming.write(data[2:])

    def encrypted(self, opcode, body, fragment=None):
        plain = struct.pack('>H', opcode) + body
        size = fragment or len(plain)
        frames = bytearray()
        for offset in range(0, len(plain), size):
            first = offset == 0; last = offset + size >= len(plain)
            chunk = plain[offset:offset+size]
            assert self.tls.write(chunk) == len(chunk)
            record = self.outgoing.read()
            assert record and record[0] == 23
            frames.extend(self.packet(8 | int(first) | (2 if last else 0), record,
                                      len(plain) if first and not last else None))
        return bytes(frames)

    def message(self):
        plain = bytearray(); expected = None
        while True:
            channel, flags, total, data = self.frame()
            assert channel == 0 and flags & 8 and not flags & 4
            if not plain: assert flags & 1
            if total is not None: expected = total
            self.incoming.write(data)
            while True:
                try: plain.extend(self.tls.read(65536))
                except ssl.SSLWantReadError: break
            if flags & 2:
                if expected is not None: assert len(plain) == expected
                assert len(plain) >= 2
                return int.from_bytes(plain[:2], 'big'), bytes(plain[2:])

    def handle(self):
        began = time.monotonic(); report = {'fixture': 'openssl-2'}
        try:
            self.request.settimeout(20)
            mode = self.exact(1)[0]
            assert mode in (0, 1, 2)
            report['mode'] = mode
            context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
            context.minimum_version = context.maximum_version = ssl.TLSVersion.TLSv1_2
            context.check_hostname = False  # AAP identifies a TLS role, not an HTTPS DNS name.
            context.verify_flags = ssl.VERIFY_DEFAULT
            context.load_verify_locations(cadata=ssl.DER_cert_to_PEM_cert(
                (ROOT/'android/app/src/main/res/raw/aa_google_automotive_link.der').read_bytes()))
            context.load_cert_chain(str(CERTIFICATE), str(PRIVATE_KEY))
            cipher = 'ECDHE-RSA-AES128-SHA' if mode == 2 else 'ECDHE-RSA-AES128-GCM-SHA256'
            report['offered_cipher'] = cipher
            context.set_ciphers(cipher)
            self.incoming = ssl.MemoryBIO(); self.outgoing = ssl.MemoryBIO()
            self.tls = context.wrap_bio(self.incoming, self.outgoing, server_side=False)
            self.plain(1, struct.pack('>HH', 1, 6))
            channel, flags, total, version = self.frame()
            assert (channel, flags, total) == (0, 3, None)
            assert version[:8] == b'\0\2\0\1\0\7\0\0'
            # VersionResponseOptions are optional; parse them independently when present.
            if len(version) > 8: fields(version[8:])
            self.handshake()
            report['cipher'] = self.tls.cipher()[0]
            report['phone_chain_verified'] = bool(self.tls.getpeercert())
            if mode == 1: self.request.sendall(self.encrypted(4, b'\x08\x00'))
            else: self.plain(4, b'\x08\x00')
            opcode, request = self.message()
            assert opcode == 5
            presentation = fields(request)
            assert {1, 2, 3, 4, 5, 6} <= presentation.keys(), 'Incomplete phone presentation'
            for number, size in ((1,32), (2,64), (3,128)): check_png(presentation[number], size)
            assert presentation[4] == b'HSD' and presentation[5]
            assert len(fields(presentation[6])[1]) == 32
            report['request_bytes'] = len(request)
            report['icon_bytes'] = [len(presentation[n]) for n in (1,2,3)]
            response = blob(14, 'OpenSSL receiver')
            if mode == 2: response += blob(200, b'x' * 18000)  # Unknown protobuf field, real fragmented TLS/AAP.
            wire = self.encrypted(6, response, 7000 if mode == 2 else None)
            wire += self.encrypted(11, b'\x08\x7b')
            if mode == 2:
                # TCP can split headers or coalesce several AAP frames independently of their boundaries.
                for offset in range(0, len(wire), 97): self.request.sendall(wire[offset:offset+97])
            else: self.request.sendall(wire)
            opcode, body = self.message()
            assert opcode == 12 and fields(body)[1] == 123
            self.request.sendall(self.encrypted(15, b'\x08\x01'))
            opcode, _ = self.message()
            assert opcode == 16
            report['pass'] = True
        except Exception as error:
            report.update({'pass':False, 'error':f'{type(error).__name__}: {error}'})
        finally:
            report['seconds'] = round(time.monotonic() - began, 3)
            with LOCK:
                if RESULTS is not None:
                    with RESULTS.open('a') as out: out.write(json.dumps(report)+'\n')
                print(json.dumps(report), flush=True)

class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--key', type=Path, required=True, help='Private key matching the test APK receiver certificate; never a production key')
    parser.add_argument('--certificate', type=Path, default=CERTIFICATE)
    parser.add_argument('--port', type=int, default=5883)
    parser.add_argument('--results', type=Path, help='Append bounded JSON results to this file')
    args = parser.parse_args()
    CERTIFICATE, PRIVATE_KEY, RESULTS = args.certificate, args.key, args.results
    with Server(('127.0.0.1', args.port), Peer) as server:
        print(f'Independent OpenSSL receiver listening on loopback:{args.port}', flush=True)
        server.serve_forever()

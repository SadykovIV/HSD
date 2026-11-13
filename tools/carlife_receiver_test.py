"""Exercise HSD's Android instrumentation with the CarLife HeadUnit Emulator wire codec.

Install the HSD app and its debug androidTest APK on a test device, then run with
the emulator project on PYTHONPATH. Use --serial to select the ADB target.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
import re
import socket
import subprocess
import time

from hu_lab import protocol as wire


PACKAGE = "com.hsd.universal"
MUSIC_CONTROL = 0x18028
MODULE_STATUS = 0x10026
TURN_INFO = 0x10030
TRACK_INFO = 0x10035
TRACK_PROGRESS = 0x10036
MEDIA_DATA = 0x30006


class Receiver:
    def __init__(self, connection):
        self.connection = connection
        self.frames = wire.FrameParser()
        self.pending = []
        self.active_music = False
        self.audio_packets = 0
        self.track_messages = 0

    def send(self, message, **values):
        payload = wire.protobuf(**values)
        self.connection.sendall(wire.Packet(message >> 16, message, payload).encode())

    def next(self, predicate):
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            if not self.pending:
                self.connection.settimeout(max(.01, deadline - time.monotonic()))
                data = self.connection.recv(65536)
                if not data:
                    raise EOFError("HSD closed the test connection")
                self.pending.extend(self.frames.feed(data))
                continue
            packet = self.pending.pop(0)
            if packet.message == MODULE_STATUS:
                module = wire.fields(packet.payload).get(2)
                if module:
                    descriptor = wire.fields(module)
                    if descriptor.get(1) == 3 and descriptor.get(2) == 1:
                        self.active_music = True
            if packet.channel in (wire.MEDIA, wire.TTS, wire.VR):
                self.audio_packets += 1
            if packet.message in (TRACK_INFO, TRACK_PROGRESS):
                self.track_messages += 1
            if predicate(packet):
                return packet
        raise TimeoutError("expected CarLife message did not arrive")

    def message(self, message):
        return self.next(lambda p: p.message == message)

    def touch(self, action, x, second=None):
        values = dict(f1=action, f2=x, f3=300)
        if second is not None:
            values.update(f4=second, f5=360)
        self.send(wire.TOUCH_ACTION, **values)


def gesture_audio(receiver):
    receiver.send(wire.VIDEO_INIT, f1=1280, f2=720, f3=25, f4=2)
    receiver.send(wire.VIDEO_START)
    samples = []
    video_frames = 0

    def observe(packet):
        nonlocal video_frames
        if packet.message == MEDIA_DATA:
            samples.append(int.from_bytes(packet.payload[:2], "little"))
        if packet.message == wire.VIDEO_DATA:
            video_frames += 1
        return bool(samples)

    receiver.next(observe)
    def last_sample(packet):
        observe(packet)
        return samples[-1] == 399

    with ThreadPoolExecutor(max_workers=1) as worker:
        drained = worker.submit(receiver.next, last_sample)
        receiver.touch(0, 300)
        for i in range(120):
            receiver.touch(2, 300 + i % 45)
            time.sleep(.005)
        receiver.touch(1, 300)
        receiver.touch(0, 300)
        for stroke in range(10):
            receiver.touch(261, 300, 800)
            for i in range(22):
                offset = 3 * (i if stroke % 2 else 21 - i)
                receiver.touch(2, 300 + offset, 800 - offset)
                time.sleep(.006)
            receiver.touch(262, 300, 800)
        receiver.touch(1, 300)
        drained.result(timeout=8)
    assert samples == list(range(400)), "Missing, repeated or reordered 10 ms audio samples"
    assert video_frames >= 85, video_frames
    print(f"Received 400 consecutive PCM packets and {video_frames} video frames")


def camera_hud(receiver):
    def keyframe(packet):
        return (packet.message == wire.VIDEO_DATA and
                any(nal and nal[0] & 31 == 5 for nal in re.split(b"\x00\x00\x01", packet.payload)))

    def music_inactive(packet):
        if packet.message != MODULE_STATUS:
            return False
        entry = wire.fields(packet.payload).get(2)
        return bool(entry) and wire.fields(entry) == {1: 3, 2: 0}

    nav = wire.protobuf(f1=1, f2=wire.protobuf(f1=0))
    music = wire.protobuf(f1=1, f2=wire.protobuf(f1=2))
    def subscribe(message, module):
        receiver.connection.sendall(wire.Packet(wire.CMD, message, module).encode())

    receiver.send(wire.HU_VERSION, f1=4, f2=2)
    receiver.message(wire.MD_INFO)
    receiver.send(0x18027, f4="20772119")
    receiver.send(wire.VIDEO_INIT, f1=1024, f2=768, f3=30, f4=2)
    receiver.message(wire.VIDEO_INIT_DONE)
    receiver.send(wire.VIDEO_START)
    receiver.next(keyframe)
    subscribe(0x18043, nav)
    receiver.message(0x10044)
    subscribe(0x18045, nav)
    turn = wire.fields(receiver.message(TURN_INFO).payload)
    assert (turn[1], turn[2], turn[3].decode(), turn[5]) == (1, 3, "ул Мира", 180) and turn[6]
    for video_pause in (False, True):
        if video_pause:
            receiver.send(wire.VIDEO_PAUSE)
        for setting in (0, 1):
            receiver.send(MUSIC_CONTROL, f1=3, f2=setting)
            receiver.next(music_inactive)
        if video_pause:
            receiver.send(wire.VIDEO_START)
        receiver.send(wire.VIDEO_RESET)
        receiver.next(keyframe)
    receiver.send(MUSIC_CONTROL, f1=3, f2=1)
    receiver.next(music_inactive)
    assert receiver.track_messages == 0
    subscribe(0x18045, music)
    assert wire.fields(receiver.message(TRACK_INFO).payload)[2] == b"Fixture track"
    receiver.message(TRACK_PROGRESS)
    subscribe(0x18046, music)
    subscribe(0x18046, nav)
    assert not receiver.active_music and receiver.audio_packets == 0
    print("Camera return restored video; Bluetooth music stayed external; HUD received the turn, street and distance")


def run(adb, protocol, check):
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        listener.listen(1)
        listener.settimeout(20)
        port = str(listener.getsockname()[1])
        address = "tcp:" + port
        subprocess.run([*adb, "reverse", address, address], check=True, capture_output=True)
        try:
            command = [*adb, "shell", "am", "instrument", "-w", "-r", "-e", "protocol", protocol,
                       "-e", "fixturePort", port, PACKAGE + ".test/" + PACKAGE + ".DriveDeckAapProbe"]
            with subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True) as proc:
                try:
                    connection, _ = listener.accept()
                    with connection:
                        check(Receiver(connection))
                    output = proc.communicate(timeout=20)[0]
                    if proc.returncode or "INSTRUMENTATION_RESULT: result=PASS" not in output:
                        raise RuntimeError(output)
                finally:
                    if proc.poll() is None:
                        proc.terminate()
        finally:
            subprocess.run([*adb, "reverse", "--remove", address], check=True, capture_output=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    args = parser.parse_args()
    target = [args.adb, "-s", args.serial]
    run(target, "carlife-gesture-audio", gesture_audio)
    run(target, "carlife-hud", camera_hud)

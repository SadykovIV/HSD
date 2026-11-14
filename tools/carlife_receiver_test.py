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
from tempfile import TemporaryFile

from hu_lab import protocol as wire
from hu_lab.client import ReferenceStartGate


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
        receiver.touch(0, 300)  # A physical Honda reports one contact twice before its first MOVE.
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


def check_screen_off(adb, restart_decoder=False):
    def shell(*args):
        return subprocess.check_output([*adb, "shell", *args], text=True).strip()

    if shell("getprop", "ro.kernel.qemu") != "1":
        raise RuntimeError("Screen-off checks require an Android emulator")

    def panel_state():
        info = shell("dumpsys", "display")
        match = re.search(r"mBaseDisplayInfo=DisplayInfo\{[^\n]*?displayId 0\b[^\n]*?\bstate (\w+)", info)
        if not match:
            raise RuntimeError("Cannot read the default display state")
        return match[1]

    if panel_state() != "ON" or re.search(r"\bdeviceLocked=(true|1)\b", shell("dumpsys", "trust")):
        raise RuntimeError("Start with the test emulator awake and unlocked")

    def capture(receiver):
        receiver.send(wire.HU_VERSION, f1=2 if restart_decoder else 1, f2=0 if restart_decoder else 6)
        receiver.message(wire.VERSION_MATCH)
        receiver.send(wire.VIDEO_INIT, f1=1024, f2=768, f3=30)
        receiver.message(wire.VIDEO_INIT_DONE)
        receiver.send(wire.VIDEO_START)
        receiver.touch(0, 300)
        receiver.touch(1, 300)
        with TemporaryFile() as stream:
            def collect(seconds):
                end = time.monotonic() + seconds
                count = 0
                while time.monotonic() < end:
                    stream.write(receiver.message(wire.VIDEO_DATA).payload)
                    count += 1
                return count

            before = collect(5)
            if panel_state() != "ON":
                raise RuntimeError("The emulator fell asleep before the reference frame")
            try:
                shell("input", "keyevent", "KEYCODE_POWER")
                until = time.monotonic() + 8
                while panel_state() != "OFF":
                    if time.monotonic() >= until:
                        raise TimeoutError("The default display stayed on")
                    collect(.25)
                after = collect(5)
                assert panel_state() == "OFF" and after >= 3
                if restart_decoder:
                    # Drop all decoder state. Keep the USB session alive, send no commands,
                    # and start a new decoder with only the bytes arriving from this point.
                    fresh = recover_picture(receiver, "silent decoder restart")
                    receiver.send(wire.VIDEO_RESET)
                    requested = recover_picture(receiver, "receiver RESET")
                    assert panel_state() == "OFF", "The phone woke during decoder recovery"
            finally:
                shell("input", "keyevent", "KEYCODE_WAKEUP")
                shell("wm", "dismiss-keyguard")
            stream.seek(0)
            raw = subprocess.check_output(["ffmpeg", "-v", "error", "-i", "pipe:0", "-vf", "scale=96:72",
                                           "-f", "rawvideo", "-pix_fmt", "rgb24", "-"], stdin=stream)
        frame_bytes = 96 * 72 * 3
        assert before > 2 and len(raw) % frame_bytes == 0 and len(raw) >= (before + after) * frame_bytes
        reference = raw[(before - 1) * frame_bytes:before * frame_bytes]
        asleep = raw[-frame_bytes:]
        pictures = [reference, asleep]
        if restart_decoder:
            pictures.extend([fresh, requested])
        for frame in pictures:
            assert len(set(zip(frame[::3], frame[1::3], frame[2::3]))) > 64, "Missing launcher picture"
        for frame in pictures[1:]:
            delta = sum(abs(a - b) for a, b in zip(reference, frame)) / frame_bytes
            assert delta < 12, f"The head unit lost the launcher picture (mean RGB difference {delta:.1f})"
        print(f"{'DriveDeck' if restart_decoder else 'Android Desktop'} stayed visible with the default display OFF ({after} CarLife video packets)")

    run(adb, "carlife-usb" if restart_decoder else "carlife-desktop", capture)


def recover_picture(receiver, reason):
    gate = ReferenceStartGate()
    deadline = time.monotonic() + 8
    keys = 0
    while time.monotonic() < deadline:
        data = receiver.message(wire.VIDEO_DATA).payload
        keys += any(nal and nal[0] & 31 == 5 for nal in re.split(b"\x00\x00\x01", data))
        picture = gate.feed(data)
        if picture is None:
            continue
        # The decoder process has no previous pictures or out-of-band codec configuration.
        pixels = subprocess.check_output(["ffmpeg", "-v", "error", "-f", "h264", "-i", "pipe:0",
                                          "-frames:v", "1", "-vf", "scale=96:72", "-f", "rawvideo",
                                          "-pix_fmt", "rgb24", "-"], input=picture)
        assert len(pixels) == 96 * 72 * 3 and keys <= 2, (reason, keys, len(pixels))
        print(f"{reason}: decoded a fresh picture after {keys} IDR(s)", flush=True)
        return pixels
    raise AssertionError(f"{reason}: could not initialize a new decoder after receiving {keys} IDR(s)")


def run(adb, protocol, check):
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        listener.listen(1)
        listener.settimeout(20)
        port = str(listener.getsockname()[1])
        address = "tcp:" + port
        subprocess.run([*adb, "reverse", address, address], check=True, capture_output=True)
        try:
            command = [*adb, "shell", "am", "instrument", "--no-hidden-api-checks", "-w", "-r", "-e", "protocol", protocol,
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
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--screen-off", action="store_true", help="Check decoded desktop frames while an unlocked emulator sleeps (requires ffmpeg)")
    mode.add_argument("--decoder-restart", action="store_true", help="Reset the DriveDeck receiver decoder while the phone display is off (requires ffmpeg)")
    args = parser.parse_args()
    target = [args.adb, "-s", args.serial]
    if args.screen_off or args.decoder_restart:
        check_screen_off(target, args.decoder_restart)
    else:
        run(target, "carlife-gesture-audio", gesture_audio)
        run(target, "carlife-hud", camera_hud)

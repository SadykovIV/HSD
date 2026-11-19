"""Exercise HSD's Android instrumentation with the CarLife HeadUnit Emulator wire codec.

Install the HSD app and its debug androidTest APK on a test device, then run with
the emulator project on PYTHONPATH. Use --serial to select the ADB target.
"""
import argparse
import bisect
from concurrent.futures import ThreadPoolExecutor
import json
import math
from pathlib import Path
import re
import socket
import statistics
import subprocess
import time
import threading
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

    def next(self, predicate, timeout=10):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if not self.pending:
                self.connection.settimeout(max(.01, deadline - time.monotonic()))
                data = self.connection.recv(32768)
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
    # The Android fixture bounds physical write completion to 4 MB/s. Large pictures
    # periodically exceed that rate. Verify bounded arrival jitter, not just packet count.
    receiver.send(wire.VIDEO_INIT, f1=1280, f2=720, f3=25, f4=2)
    receiver.send(wire.VIDEO_START)
    samples = []
    arrivals = []
    sender_writes = []
    video_frames = 0

    def observe(packet):
        nonlocal video_frames
        if packet.message == MEDIA_DATA:
            samples.append(int.from_bytes(packet.payload[:2], "little"))
            arrivals.append(time.monotonic())
            if packet.payload[4:8] == b"HSDP":
                sender_writes.append(int.from_bytes(packet.payload[8:16], "big"))
        if packet.message == wire.VIDEO_DATA:
            video_frames += 1
        return bool(samples)

    receiver.next(observe)
    def last_sample(packet):
        observe(packet)
        return samples[-1] == 1999

    with ThreadPoolExecutor(max_workers=1) as worker:
        drained = worker.submit(receiver.next, last_sample, 30)
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
        receiver.send(wire.VIDEO_PAUSE)
        time.sleep(.4)
        receiver.send(wire.VIDEO_START)
        try:
            drained.result(timeout=25)
        except (EOFError, OSError, TimeoutError):
            if samples:
                missing = sorted(set(range(samples[-1] + 1)).difference(samples))
                print(f"Receiver stopped after sample {samples[-1]}; missing before it: {missing[:32]}", flush=True)
                if len(sender_writes) == len(samples):
                    late = max((ns - sender_writes[0]) / 1e6 - (sample - samples[0]) * 10
                               for sample, ns in zip(samples, sender_writes))
                    print(f"Max sender lateness among received PCM: {late:.1f} ms", flush=True)
            raise
    assert samples == list(range(2000)), (f"Missing, repeated or reordered 10 ms PCM: count={len(samples)}, "
        f"first mismatches={[(i,value) for i,value in enumerate(samples) if i!=value][:12]}, "
        f"missing={sorted(set(range(2000))-set(samples))[:20]}")
    assert video_frames >= 85, video_frames
    late_index, lateness = max(enumerate(at - arrivals[0] - i * .01 for i, at in enumerate(arrivals)),
                               key=lambda sample: sample[1])
    gap_index, gap = max(enumerate((b - a for a, b in zip(arrivals, arrivals[1:])), start=1),
                         key=lambda sample: sample[1])
    print(f"Receiver PCM: peak lateness {lateness*1000:.1f} ms at packet {late_index}, "
          f"longest gap {gap*1000:.1f} ms before packet {gap_index}", flush=True)
    if len(sender_writes) == len(arrivals):
        sender_late = [(ns - sender_writes[0]) / 1e6 - i * 10 for i, ns in enumerate(sender_writes)]
        print(f"At peak receiver lateness: {sender_late[late_index]:.1f} ms on the sender clock, "
              f"{lateness*1000 - sender_late[late_index]:.1f} ms additional transit since the first PCM; "
              f"max sender lateness {max(sender_late):.1f} ms", flush=True)
        sender_gap_ms = (sender_writes[gap_index] - sender_writes[gap_index - 1]) / 1e6
        print(f"At the longest receiver gap: {sender_gap_ms:.1f} ms between the same two writes on Android, "
              f"{gap*1000 - sender_gap_ms:.1f} ms change in transit and receiver scheduling", flush=True)
    assert lateness < .150, f"A 150 ms receiver buffer underflowed: {lateness:.3f}s lateness"
    assert gap < .150, f"PCM arrival gap {gap:.3f}s"
    print(f"Received 2000 consecutive PCM packets and {video_frames} video frames; "
          f"max PCM lateness={lateness*1000:.1f} ms, arrival gap={gap*1000:.1f} ms; "
          "4 MB/s bulk writes, 240 KB video bursts, gestures and camera transition")


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


def marker_centres(encoded):
    return subprocess.run(["ffmpeg", "-v", "error", "-xerror", "-threads", "1", "-f", "h264", "-i", "pipe:0",
                           "-vf", "crop=16:16:(iw-16)/2:(ih-16)/2,scale=1:1", "-fps_mode", "passthrough",
                           "-pix_fmt", "rgb24", "-f", "rawvideo", "pipe:1"],
                          input=encoded, capture_output=True, timeout=40)


def matches_marker(rgb, step):
    target = (32 + step % 3 * 96, 32 + step // 3 % 3 * 96, 88 + step // 9 % 2 * 136)
    if len(rgb) != 3:
        return False
    distance = max(abs(a-b) for a, b in zip(rgb, target))
    if step % 18 == 0:
        # Android's dark launcher includes gray pixels close to the initial blue marker.
        # Require the marker's blue component before using it as proof the scene is visible.
        return distance < 20 and rgb[2] - max(rgb[:2]) > 28
    # The minimum palette spacing is 96. Stay strictly inside its disjoint half-distance
    # even when an Android codec's RGB-to-YUV conversion shifts the reconstructed colour.
    return 2 * distance < 96


def touch_delays(encoded, received, contacts):
    decoding = marker_centres(encoded)
    assert decoding.returncode == 0, decoding.stderr.decode(errors="replace")
    decoded = decoding.stdout
    assert len(decoded) == 3 * len(received), (len(decoded), len(received))
    pixels = [decoded[i:i+3] for i in range(0, len(decoded), 3)]
    assert any(at < contacts[0] and matches_marker(rgb, 0) for rgb, at in zip(pixels, received)), "Missing initial marker"
    delays = []
    for step, start in enumerate(contacts, 1):
        # Search within the existing single-contact deadline. Searching until EOF could
        # credit the same colour 18 touches later and falsely blame a different contact.
        first = bisect.bisect_left(received, start)
        end = bisect.bisect_left(received, start + 1_000_000_000, first)
        # A frozen scene already showing the expected colour is not a response.
        previous_match = first > 0 and matches_marker(pixels[first - 1], step)
        arrival = None
        for i in range(first, end):
            current_match = matches_marker(pixels[i], step)
            if current_match and not previous_match:
                arrival = received[i]
                break
            previous_match = current_match
        delays.append(None if arrival is None else round((arrival-start)/1e6, 1))
    return delays


def touch_latency(adb, receiver, evidence=None, honda_channel=None, fixed_720=False, contacts_to_send=12):
    """One host clock from a wire DOWN to the received AVC picture containing its unique marker."""
    receiver.send(wire.HU_VERSION, f1=2, f2=0)
    receiver.message(wire.VERSION_MATCH)
    if honda_channel:
        receiver.send(wire.HU_INFO, f1="Linux")
        receiver.send(0x18027, f2="1.1.0", f3=1, f4=honda_channel)
        receiver.message(0x10051)
        keys = ("BLUETOOTH_AUTO_PAIR", "BLUETOOTH_INTERNAL_UI", "FOCUS_UI", "VOICE_WAKEUP")
        features = wire.protobuf(f1=len(keys)) + b"".join(wire.protobuf(f2=wire.protobuf(f1=k, f2=0)) for k in keys)
        receiver.connection.sendall(wire.Packet(wire.CMD, 0x18052, features).encode())
    offered = (1024, 768) if honda_channel == "20832131" else (1280, 720)
    receiver.send(wire.VIDEO_INIT, f1=offered[0], f2=offered[1], f3=30)
    reply = wire.fields(receiver.message(wire.VIDEO_INIT_DONE).payload)
    negotiated = [reply.get(i) for i in (1, 2, 3)]
    expected_size = (1280, 720) if fixed_720 else offered
    assert negotiated == [*expected_size, 30], (negotiated, expected_size)
    start_ns = time.monotonic_ns()
    receiver.send(wire.VIDEO_START)
    encoded = bytearray()
    received = []
    contacts = []
    stop = threading.Event()

    def collect():
        while not stop.is_set():
            packet = receiver.next(lambda p: p.message == wire.VIDEO_DATA, timeout=40)
            at = time.monotonic_ns()
            encoded.extend(packet.payload)
            if any(unit and 1 <= unit[0] & 31 <= 5 for unit in re.split(b"\x00\x00\x01", packet.payload)[1:]):
                received.append(at)

    port = receiver.connection.getsockname()[1]
    with ThreadPoolExecutor(max_workers=1) as worker:
        reading = worker.submit(collect)
        try:
            deadline = time.monotonic() + 30
            while time.monotonic() < deadline:
                logs = subprocess.check_output([*adb, "logcat", "-d", "-s", "HsdTouchProbe:I"], text=True, timeout=5)
                if f"ready {port}" in logs:
                    break
                time.sleep(.2)
            else:
                raise TimeoutError("Marker window never drew on the projected display")
            # Window creation and projection startup can finish at different times.
            # Confirm the neutral marker on the receiver before starting the clock.
            deadline = time.monotonic() + 8
            last_pixel = b""
            while time.monotonic() < deadline:
                if received:
                    preview = marker_centres(bytes(encoded))
                    last_pixel = preview.stdout[-3:]
                    if preview.returncode == 0 and matches_marker(last_pixel, 0):
                        break
                time.sleep(.25)
            else:
                raise AssertionError(f"Initial marker never reached receiver: frames={len(received)} RGB={list(last_pixel)}")
            for i in range(contacts_to_send):
                contacts.append(time.monotonic_ns())
                receiver.touch(0, 420)
                time.sleep(.025)
                receiver.touch(1, 420)
                time.sleep(.48 + (i % 4) * .067)
            time.sleep(1)
        finally:
            stop.set()
            # Wake a startup reader even if the sender has produced no further picture.
            try:
                receiver.connection.shutdown(socket.SHUT_RD)
            except OSError:
                pass
            try:
                reading.result(timeout=5)
            except (EOFError, OSError):
                pass
            if evidence:
                evidence.mkdir(parents=True, exist_ok=True)
                (evidence / "received.h264").write_bytes(encoded)
                (evidence / "timing.json").write_text(json.dumps({"video_start_ns": start_ns, "received_ns": received, "down_ns": contacts}))

    # Decode afterwards so decoder scheduling cannot perturb wire arrival timestamps.
    delays = touch_delays(encoded, received, contacts)
    # Missed deadlines are right-censored. Keep them in the denominator and record
    # a quantile only when its observation exists within the measurement window.
    missing = [index for index, delay in enumerate(delays, 1) if delay is None]
    observed = sorted(delay for delay in delays if delay is not None)
    ordered = observed + [None] * len(missing)
    middle = ordered[(len(ordered) - 1) // 2:len(ordered) // 2 + 1]
    median = round(statistics.mean(middle), 1) if None not in middle else None
    p95 = ordered[math.ceil(.95 * len(ordered)) - 1]
    print(json.dumps({"bulk_bytes_per_second": 2600000, "channel": honda_channel, "offered": offered, "negotiated": negotiated,
                      "frames": len(received), "contacts_ms": delays,
                      "median_ms": median, "p95_ms": p95, "max_ms": ordered[-1], "missing_contacts": missing}), flush=True)
    assert not missing, f"No changed picture within 1000 ms for contacts {missing} ({len(observed)}/{len(contacts)} met the deadline)"
    assert p95 < 400, f"Touch-to-changed-picture p95={p95} ms"
    assert max(delays) < 1000, f"A single contact took {max(delays)} ms to reach the receiver"


def check_secure_surface(receiver):
    receiver.send(wire.HU_VERSION, f1=2, f2=0)
    receiver.message(wire.VERSION_MATCH)
    receiver.send(wire.VIDEO_INIT, f1=1280, f2=720, f3=30)
    receiver.message(wire.VIDEO_INIT_DONE)
    receiver.send(wire.VIDEO_START)
    encoded = bytearray()
    results = []
    for phase, expected in (("ordinary", (0, 255, 0, 0, 0, 255)),
                            ("protected", (255, 0, 0, 0, 0, 0)),
                            ("ordinary-again", (0, 255, 0, 0, 0, 255))):
        if results:
            receiver.touch(0, 420)
            receiver.touch(1, 420)
        deadline = time.monotonic() + 20
        next_check = time.monotonic()
        pixels = b""
        while time.monotonic() < deadline:
            encoded.extend(receiver.message(wire.VIDEO_DATA).payload)
            if time.monotonic() < next_check:
                continue
            decoded = subprocess.run(["ffmpeg", "-v", "error", "-threads", "1", "-f", "h264", "-i", "pipe:0",
                                      "-vf", "format=rgb24,scale=2:1:flags=neighbor", "-fps_mode", "passthrough",
                                      "-pix_fmt", "rgb24", "-f", "rawvideo", "pipe:1"], input=encoded,
                                     capture_output=True, timeout=10)
            pixels = decoded.stdout[-6:]
            if decoded.returncode == 0 and len(pixels) == 6 and max(abs(a-b) for a, b in zip(pixels, expected)) < 35:
                break
            next_check = time.monotonic() + .35
        else:
            raise AssertionError(f"{phase}: receiver RGB {list(pixels)}, expected {expected}")
        results.append({"phase": phase, "left_and_right_rgb": list(pixels)})
    print(json.dumps(results), flush=True)


def check_clear_video(receiver):
    receiver.send(wire.HU_VERSION, f1=2, f2=0)
    receiver.message(wire.VERSION_MATCH)
    receiver.send(wire.VIDEO_INIT, f1=1280, f2=720, f3=30)
    receiver.message(wire.VIDEO_INIT_DONE)
    receiver.send(wire.VIDEO_START)
    receiver.touch(0, 420)
    receiver.touch(1, 420)
    encoded = bytearray()
    end = time.monotonic() + 15
    while time.monotonic() < end:
        encoded.extend(receiver.message(wire.VIDEO_DATA).payload)
    print(json.dumps(decoded_clip_summary(encoded)), flush=True)


def decoded_clip_summary(encoded):
    pixels = subprocess.run(["ffmpeg", "-v", "error", "-xerror", "-threads", "1", "-f", "h264", "-i", "pipe:0",
                             "-vf", "format=rgb24,scale=2:1:flags=neighbor", "-fps_mode", "passthrough",
                             "-pix_fmt", "rgb24", "-f", "rawvideo", "pipe:1"], input=encoded, capture_output=True,
                            check=True, timeout=30).stdout
    sequence = []
    for offset in range(0, len(pixels), 6):
        reference, video = pixels[offset:offset+3], pixels[offset+3:offset+6]
        if len(video) != 3 or reference[1] < 200 or max(reference[0], reference[2]) > 40:
            continue
        channel = video.index(max(video))
        if video[channel] > 200 and sum(video) - video[channel] < 70:
            colour = "RGB"[channel]
            if not sequence or sequence[-1] != colour:
                sequence.append(colour)
    order = "".join(sequence)
    assert "RGBRGB" in order, f"MP4 colours missing on the receiver: {order!r}"
    return {"decoded_hsd_frames": len(pixels)//6, "mp4_colours_seen_on_receiver": order}


def check_sleep_during_video(adb, receiver, phone_ui=False):
    from phone_sleep_report import capture
    def shell(*parts):
        return subprocess.check_output([*adb, "shell", *parts], text=True, timeout=12).strip()

    assert shell("getprop", "ro.kernel.qemu") == "1", "This test changes settings on a disposable emulator"
    settings = (("global", "stay_on_while_plugged_in"), ("system", "screen_off_timeout"),
                ("secure", "lock_screen_lock_after_timeout"))
    previous = [shell("settings", "get", *item) for item in settings]
    receiver.send(wire.HU_VERSION, f1=2, f2=0)
    receiver.message(wire.VERSION_MATCH)
    receiver.send(wire.VIDEO_INIT, f1=1280, f2=720, f3=30)
    receiver.message(wire.VIDEO_INIT_DONE)
    receiver.send(wire.VIDEO_START)
    stop = threading.Event()
    frames = []
    contacts = 0

    def drain():
        nonlocal contacts
        next_touch = 0
        while not stop.is_set():
            payload = receiver.message(wire.VIDEO_DATA).payload
            now = time.monotonic()
            frames.append((now, payload))
            if now >= next_touch and (not phone_ui or contacts == 0):
                receiver.touch(0, 400)
                receiver.touch(1, 400)
                contacts += 1
                next_touch = now + .7

    pin = False
    with ThreadPoolExecutor(max_workers=1) as worker:
        stream = worker.submit(drain)
        try:
            plugged = int(capture(shell)["plug_type"])
            assert plugged > 0
            shell("settings", "put", *settings[0], str(plugged))
            shell("settings", "put", *settings[1], "10000")
            shell("settings", "put", *settings[2], "3000")
            if phone_ui:
                # The fresh install offers onboarding from its launcher. Open the same HSD
                # phone settings page its permissions shortcut opens for a configured user.
                shell("am", "start", "-W", "--display", "0", "-a", "android.intent.action.MAIN",
                      "-c", "android.intent.category.LAUNCHER", "-n", PACKAGE + "/.dex.DexSetupActivity",
                      "--ez", PACKAGE + ".SHOW_PERMISSIONS", "true")
            else:
                shell("am", "start", "-W", "--display", "0", "-a", "android.intent.action.MAIN",
                      "-c", "android.intent.category.HOME")
            pin = "Pin set" in shell("locksettings", "set-pin", "1946")
            assert pin
            shell("input", "-d", "0", "tap", "12", "400")
            time.sleep(14)
            control = capture(shell)
            print(json.dumps({"charging_override_on": control}), flush=True)
            assert control["effective_stay_on"] == "true" and control["phone_display"] == "ON"
            assert control["user_locked"] is False
            if phone_ui:
                activities = shell("dumpsys", "activity", "activities")
                phone = re.search(r"(?ms)^Display #0 \(activities from top to bottom\):(.+?)(?:^Display #|\Z)", activities)
                assert phone and re.search(r"topResumedActivity=[^\n]*\.dex.DexSetupActivity", phone[1]), "HSD not resumed on display 0"
            # A tap on the display edge may be consumed without resetting this display's idle clock.
            # Establish a measured, accepted last interaction before removing the charging override.
            sizes = re.findall(r"(\d+)x(\d+)", shell("wm", "size"))
            width, height = map(int, sizes[-1])
            sdk = int(shell("getprop", "ro.build.version.sdk"))
            old_activity = control["activity_by_group"].get("0", {}).get("lastUserActivityTime")
            assert old_activity is not None or sdk < 37, "Phone display activity clock missing"
            for x, y in ((width//2, height//3), (width//3, height//2), (width//2, height//2)):
                start = time.monotonic()
                shell("input", "-d", "0", "tap", str(x), str(y))
                seed = capture(shell)["activity_by_group"].get("0", {}).get("lastUserActivityTime")
                if old_activity is None or seed is not None and int(seed) > int(old_activity):
                    print(json.dumps({"last_phone_tap": {"from": old_activity, "to": seed}}), flush=True)
                    break
            else:
                raise AssertionError("The guest did not register a last user interaction on display 0")
            shell("settings", "put", *settings[0], "0")
            first_off = None
            while time.monotonic() - start < 25:
                state = capture(shell)
                if first_off is None and state["phone_display"] == "OFF":
                    first_off = time.monotonic() - start
                if first_off is not None and state["phone_display"] == "OFF" and state["user_locked"] is True:
                    break
                time.sleep(.55)
            else:
                raise AssertionError(f"Physical screen or keyguard did not time out: {state}")
            locked = time.monotonic()
            print(json.dumps({"charging_override_off": state, "seconds_to_off": round(first_off, 2),
                              "seconds_to_locked": round(locked - start, 2)}), flush=True)
            assert state["effective_stay_on"] == "false" and first_off >= 7, (first_off, state)
            if seed is not None:
                assert state["activity_by_group"]["0"]["lastUserActivityTime"] == seed, "Phone idle clock advanced during remote input"
            if sdk >= 37:
                assert state["screen_locks"] is not None
                assert not any(lock["tag"].startswith("HSD-desk:display:") for lock in state["screen_locks"])
            time.sleep(9)
            final = capture(shell)
            assert final["phone_display"] == "OFF" and final["user_locked"] is True
        finally:
            stop.set()
            try:
                stream.result(timeout=12)
            finally:
                if pin:
                    assert "cleared" in shell("locksettings", "clear", "--old", "1946").lower()
                for item, value in zip(settings, previous):
                    shell("settings", "delete", *item) if value == "null" else shell("settings", "put", *item, value)
                shell("input", "keyevent", "KEYCODE_WAKEUP")
                shell("wm", "dismiss-keyguard")

    gate = ReferenceStartGate()
    clip = bytearray()
    for when, payload in frames:
        if when >= locked:
            chunk = payload if clip else gate.feed(payload)
            if chunk:
                clip.extend(chunk)
    if phone_ui:
        assert contacts == 1
    else:
        assert contacts > 25
    print(json.dumps({"video_after_phone_lock": decoded_clip_summary(clip), "remote_taps": contacts,
                      "hsd_phone_window": phone_ui}), flush=True)


def check_sleep_after_disconnect(adb):
    from phone_sleep_report import capture

    def shell(*args):
        return subprocess.check_output([*adb, "shell", *args], text=True, timeout=12).strip()

    assert shell("getprop", "ro.kernel.qemu") == "1", "Disposable emulator required"
    settings = (("system", "screen_off_timeout"), ("global", "stay_on_while_plugged_in"),
                ("secure", "lock_screen_lock_after_timeout"))
    previous = [shell("settings", "get", *item) for item in settings]
    pin = False
    try:
        shell("input", "keyevent", "KEYCODE_WAKEUP")
        shell("wm", "dismiss-keyguard")
        shell("dumpsys", "battery", "unplug")
        for item, value in zip(settings, ("10000", "0", "3000")):
            shell("settings", "put", *item, value)
        pin = "Pin set" in shell("locksettings", "set-pin", "1946")
        assert pin
        launch = shell("am", "start", "-W", "--display", "0", "-n", PACKAGE + "/.dex.DexSetupActivity",
                       "--ez", PACKAGE + ".SHOW_PERMISSIONS", "true")
        assert "Status: ok" in launch, launch
        pid = shell("pidof", "-s", PACKAGE)
        assert pid.isdigit()
        shell("input", "keyevent", "KEYCODE_HOME")
        active = shell("dumpsys", "activity", "activities")
        resumed = re.search(r"topResumedActivity=[^\n]*? u0 ([^/ ]+)/", active)
        assert resumed and resumed[1] != PACKAGE, "HSD remained in the foreground"
        start = time.monotonic()
        screen_off = None
        while time.monotonic() - start < 45:
            state = capture(shell)
            if state["phone_display"] == "OFF" and screen_off is None:
                screen_off = time.monotonic() - start
            if (state["phone_display"] == "OFF" and state["user_locked"] is True and
                    state["wakefulness"] == "Asleep" and state["display_suspend_blocker"] == "false"):
                break
            time.sleep(.5)
        else:
            raise AssertionError(f"Handset did not sleep and lock naturally: {state}")
        assert shell("pidof", "-s", PACKAGE) == pid and state["hsd_wake_locks"] == [], state
        print(json.dumps({"background_pid": pid, "foreground": resumed[1], "seconds_to_off": round(screen_off, 2),
                          "seconds_to_sleep_and_pin": round(time.monotonic() - start, 2), "state": state}), flush=True)
        shell("dumpsys", "deviceidle", "force-idle", "deep")
        deep = shell("dumpsys", "deviceidle", "get", "deep")
        assert deep == "IDLE" and capture(shell)["hsd_wake_locks"] == []
        print(json.dumps({"system_requested_deep_idle": deep, "background_pid_after_idle": shell("pidof", "-s", PACKAGE)}), flush=True)
    finally:
        shell("dumpsys", "deviceidle", "unforce")
        shell("dumpsys", "battery", "reset")
        if pin:
            assert "cleared" in shell("locksettings", "clear", "--old", "1946").lower()
        for item, value in zip(settings, previous):
            if value == "null":
                shell("settings", "delete", *item)
            else:
                shell("settings", "put", *item, value)
        shell("input", "keyevent", "KEYCODE_WAKEUP")
        shell("wm", "dismiss-keyguard")


def run(adb, protocol, check, arguments=()):
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        listener.listen(1)
        listener.settimeout(20)
        port = str(listener.getsockname()[1])
        address = "tcp:" + port
        subprocess.run([*adb, "reverse", address, address], check=True, capture_output=True)
        try:
            command = [*adb, "shell", "am", "instrument", "--no-hidden-api-checks", "-w", "-r", "-e", "protocol", protocol,
                       "-e", "fixturePort", port, *arguments, PACKAGE + ".test/" + PACKAGE + ".DriveDeckAapProbe"]
            with subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True) as proc:
                try:
                    connection, _ = listener.accept()
                    requires_verdict = "touchLatency" in arguments
                    def send_verdict(passed):
                        if requires_verdict:
                            peer, _ = listener.accept()
                            with peer:
                                peer.sendall(bytes([int(passed)]))

                    try:
                        with connection:
                            check(Receiver(connection))
                    except Exception as error:
                        try:
                            send_verdict(False)
                        except OSError:
                            pass  # The sender may already have failed and exited.
                        try:
                            output = proc.communicate(timeout=20)[0]
                        except subprocess.TimeoutExpired:
                            output = "Instrumentation did not finish after receiver failure"
                        raise RuntimeError(f"{error}\n{output}") from error
                    send_verdict(True)
                    output = proc.communicate(timeout=20)[0]
                    if proc.returncode or "INSTRUMENTATION_RESULT: result=PASS" not in output:
                        raise RuntimeError(output)
                    for line in output.splitlines():
                        if line.startswith("INSTRUMENTATION_RESULT: gesture="):
                            print("Android sender: " + line.split("gesture=", 1)[1], flush=True)
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
    mode.add_argument("--touch-latency", action="store_true", help="Measure touch-to-picture changes during panning on the DriveDeck display")
    mode.add_argument("--secure-surface", action="store_true", help="Decode ordinary and secure SurfaceView controls on the CarLife receiver")
    mode.add_argument("--clear-video", action="store_true", help="Decode a known ordinary MP4 on the phone and check its moving picture on the receiver")
    mode.add_argument("--video-phone-sleep", action="store_true", help="Compare the charging stay-on setting and natural PIN lock during remote video and input")
    mode.add_argument("--video-phone-idle-ui", action="store_true", help="Leave HSD open on display 0 while the remote movie plays without further input")
    mode.add_argument("--after-disconnect", action="store_true", help="Close a real desktop session and observe its default display and remaining wake locks")
    parser.add_argument("--touch-contacts", type=int, default=12, help="Number of touch-to-picture measurements (1 to 72, default 12)")
    parser.add_argument("--touch-evidence", type=Path, help="Save received AVC and timestamps for independent verification")
    parser.add_argument("--private-display", action="store_true", help="Use a private marker display for the touch measurement")
    mode.add_argument("--private-audio", action="store_true", help="Measure numbered synthetic PCM on the bounded bulk link without starting a desktop or player")
    parser.add_argument("--honda-channel", choices=("20772124", "20832131"), help="Replay feature negotiation and video geometry from the Honda trace")
    parser.add_argument("--fixed-720", action="store_true", help="Select HSD’s explicit 1280x720 desktop as in the evening field session")
    parser.add_argument("--no-av-sync", action="store_true", help="Exercise continuous PCM with additional A/V alignment disabled")
    args = parser.parse_args()
    if not 1 <= args.touch_contacts <= 72:
        parser.error("--touch-contacts must be between 1 and 72 to fit the Android fixture's session timeout")
    if args.private_display and (not args.touch_latency or args.fixed_720):
        parser.error("--private-display requires --touch-latency with receiver-negotiated geometry")
    target = [args.adb, "-s", args.serial]
    if args.private_audio:
        run(target, "carlife-gesture-audio", gesture_audio)
    elif args.touch_latency:
        options = ["-e", "touchLatency", "true", "-e", "avSync", "true"]
        if args.fixed_720:
            options += ["-e", "fixedCarLife720", "true"]
        protocol = "carlife-private-touch" if args.private_display else "carlife-usb"
        run(target, protocol, lambda r: touch_latency(target, r, args.touch_evidence, args.honda_channel, args.fixed_720, args.touch_contacts), options)
    elif args.video_phone_sleep or args.video_phone_idle_ui:
        run(target, "carlife-usb", lambda r: check_sleep_during_video(target, r, args.video_phone_idle_ui), ["-e", "clearVideo", "true"])
    elif args.clear_video:
        run(target, "carlife-usb", check_clear_video, ["-e", "clearVideo", "true"])
    elif args.secure_surface or args.after_disconnect:
        run(target, "carlife-usb", check_secure_surface, ["-e", "secureSurface", "true"])
        if args.after_disconnect:
            check_sleep_after_disconnect(target)
    elif args.screen_off or args.decoder_restart:
        check_screen_off(target, args.decoder_restart)
    else:
        run(target, "carlife-gesture-audio", gesture_audio, ["-e", "avSync", "false"] if args.no_av_sync else ())
        run(target, "carlife-hud", camera_hud)

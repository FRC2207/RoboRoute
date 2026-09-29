import json
import os
import site
import sys
import time

import usb.backend.libusb1
import usb.core
import usb.util

if sys.platform == "win32":
    from libusb._platform.windows import DLL_PATH
else:
    DLL_PATH = None

from .aoa import (
    ACCESSORY_PIDS,
    ACCESSORY_VID,
    find_accessory,
    find_device,
    is_accessory_id,
    toggle_accessory_mode,
)

# USB device/interface classes that an Android Accessory target would never
# expose, used to rule out webcams, Bluetooth chips, hubs, and other noise.
NON_ANDROID_CLASSES = {
    0x01,  # Audio
    0x02,  # Communications/CDC
    0x03,  # HID
    0x05,  # Physical
    0x06,  # Image
    0x07,  # Printer
    0x08,  # Mass Storage
    0x09,  # Hub
    0x0A,  # CDC-Data
    0x0B,  # Smart Card
    0x0D,  # Content Security
    0x0E,  # Video
    0x0F,  # Personal Healthcare
    0x10,  # Audio/Video
    0x11,  # Billboard
    0x12,  # USB Type-C Bridge
    0xDC,  # Diagnostic
    0xE0,  # Wireless Controller
}

MANUFACTURER = "NTOverAOA"
MODEL = "Adapter"
DESCRIPTION = "Sends NetworkTables Data to a Android Device With AOA"
VERSION = "1.2"
URI = "https://github.com/FRC2207/RoboRoute"
SERIAL = "NTOverAOA"

WRITE_TIMEOUT = 3000


class USBHandler:
    def __init__(
        self,
        manufacturer=MANUFACTURER,
        model=MODEL,
        description=DESCRIPTION,
        version=VERSION,
        uri=URI,
        serial=SERIAL,
    ):
        self.manufacturer = manufacturer
        self.model = model
        self.description = description
        self.version = version
        self.uri = uri
        self.serial = serial

        self.device = None
        self._ep_in = None
        self._ep_out = None
        self._recv_buf = bytearray()
        self._usb_backend = None
        self._dll_directory_handle = None

    def find_libusb_dll(self):
        if sys.platform != "win32":
            return None

        path = str(DLL_PATH)

        if os.path.isfile(path):
            return path

        roots = []

        bundled_root = getattr(sys, "_MEIPASS", None)
        if bundled_root:
            roots.append(bundled_root)
            for architecture in ("x86_64", "x86", "arm64"):
                roots.append(
                    os.path.join(
                        bundled_root,
                        "libusb",
                        "_platform",
                        "windows",
                        architecture,
                    )
                )

        roots.append(sys.prefix)

        roots += [path for path in site.getsitepackages() if os.path.isdir(path)]

        for root in roots:
            for dirpath, _dirs, files in os.walk(root):
                if "libusb-1.0.dll" in files:
                    return os.path.join(dirpath, "libusb-1.0.dll")

        return None

    def init_backend(self):
        dll = self.find_libusb_dll()

        if dll:
            dll_dir = os.path.dirname(dll)

            os.environ["PATH"] = dll_dir + os.pathsep + os.environ.get("PATH", "")

            add_dll_directory = getattr(os, "add_dll_directory", None)

            if add_dll_directory is not None:
                self._dll_directory_handle = add_dll_directory(dll_dir)

            self._usb_backend = usb.backend.libusb1.get_backend(
                find_library=lambda _candidate: dll
            )

        try:
            usb.core.find(find_all=True, backend=self._usb_backend)
        except usb.core.NoBackendError:
            raise RuntimeError("libusb DLL not found")

    def device_name(self, dev):
        manufacturer = ""
        product = ""

        manufacturer = dev.manufacturer or ""

        product = dev.product or ""

        name = (manufacturer + " " + product).strip()

        return f"{dev.idVendor:04x}:{dev.idProduct:04x} {name}".strip()

    def _exposes_class(self, dev, classes):
        if dev.bDeviceClass in classes:
            return True

        try:
            for config in dev.configurations():
                for interface in config.interfaces():
                    if interface.bInterfaceClass in classes:
                        return True
        except Exception:  # noqa: BLE001 - device inspection can fail per backend
            return False

        return False

    def is_android_device(self, dev):
        if is_accessory_id(dev.idVendor, dev.idProduct):
            # AOA IDs bypass the class filter on purpose: 2D04/2D05 legitimately
            # add an Audio interface while still being valid targets.
            return True

        if not self._exposes_class(dev, {0xFF}):
            return False

        return not self._exposes_class(dev, NON_ANDROID_CLASSES)

    def find_options(self):
        self.init_backend()

        known = []

        try:
            for dev in usb.core.find(find_all=True, backend=self._usb_backend):
                try:
                    name = self.device_name(dev)
                except Exception:  # noqa: BLE001 - one device must not abort scanning
                    name = ""

                if name:
                    display_name = name
                else:
                    display_name = f"{dev.idVendor:04x}:{dev.idProduct:04x}"

                entry = (
                    dev.idVendor,
                    dev.idProduct,
                    display_name,
                    self._serial_number(dev),
                )

                if self.is_android_device(dev):
                    known.append(entry)

        except Exception as e:  # noqa: BLE001 - normalize backend errors for callers
            raise RuntimeError(f"USB enumeration failed: {e}")

        return known

    def _serial_number(self, dev):
        try:
            return dev.serial_number or ""
        except Exception:  # noqa: BLE001 - serial access is optional
            return ""

    def connect(self, vidpid, max_wait=5.0):
        self.init_backend()

        dev = find_accessory()

        if dev is None:
            if vidpid:
                dev = find_device([vidpid])
            else:
                dev = None

            if dev is not None:
                toggle_accessory_mode(
                    dev,
                    self.manufacturer,
                    self.model,
                    self.description,
                    self.version,
                    self.uri,
                    self.serial,
                )

                deadline = time.monotonic() + max_wait
                dev = None

                while time.monotonic() < deadline:
                    dev = find_accessory()

                    if dev is not None:
                        break

                    time.sleep(0.1)

        if dev is None:
            raise RuntimeError(
                "Could not enter Android accessory mode. "
                "Check that the device is plugged in, unlocked, and was selected"
            )

        self.device = dev
        self._ep_in, self._ep_out = self.bulk_endpoints(dev)
        self._recv_buf.clear()

        return dev

    def is_connected(self):
        return self.device is not None

    def disconnect(self):
        if self.device is not None:
            usb.util.dispose_resources(self.device)

        self.device = None
        self._ep_in = None
        self._ep_out = None
        self._recv_buf.clear()

    def bulk_endpoints(self, dev):
        accessory_intf = None

        for interface in dev.get_active_configuration().interfaces():
            if interface.bInterfaceClass == 0xFF:
                accessory_intf = interface
                break

        if accessory_intf is None:
            raise RuntimeError(
                "No vendor-specific (accessory) interface found on device"
            )

        ep_in = None
        ep_out = None

        for endpoint in accessory_intf.endpoints():
            if endpoint.bmAttributes != usb.util.ENDPOINT_TYPE_BULK:
                continue

            address = endpoint.bEndpointAddress

            if usb.util.endpoint_direction(address) == usb.util.ENDPOINT_IN:
                ep_in = endpoint
            else:
                ep_out = endpoint

        if ep_in is None or ep_out is None:
            raise RuntimeError("Accessory bulk endpoints not found")

        return ep_in, ep_out

    def send_frame(self, payload):
        if self._ep_out is None:
            raise RuntimeError("Not connected")

        self._ep_out.write(
            bytes(payload),
            timeout=WRITE_TIMEOUT,
        )

    def send_messages(self, msgs):
        if not msgs:
            return

        buf = b"".join(message for message in msgs if message)

        if buf:
            if self._ep_out is None:
                raise RuntimeError("Not connected")

            self._ep_out.write(
                buf,
                timeout=WRITE_TIMEOUT,
            )

    def parse_line(self, line):
        data = json.loads(line)

        if not isinstance(data, dict):
            raise TypeError("USB message must be a JSON object")

        action = data.get("action")
        if action == "subscribe":
            keys = data.get("keys")
            if not isinstance(keys, list):
                raise ValueError("subscribe action requires a keys list")
            return {"action": "subscribe", "keys": keys}

        if action == "put":
            if "key" not in data or "value" not in data:
                raise ValueError("put action requires key and value")
            message = {
                "action": "put",
                "key": data["key"],
                "value": data["value"],
            }
            if "schema" in data:
                message["schema"] = data["schema"]
            return message

        if "subscribe" in data:
            keys = data.get("subscribe")
            if not isinstance(keys, list):
                raise ValueError("subscribe requires a list")
            return {"action": "subscribe", "keys": keys}

        key = data.get("key")
        if key is not None and "value" in data:
            message = {"action": "put", "key": key, "value": data["value"]}
            if "schema" in data:
                message["schema"] = data["schema"]
            return message

        raise ValueError("USB message has no recognized action")

    def receive_message(self, timeout=0.2, max_read=65536):
        raw = self.receive_line(timeout=timeout, max_read=max_read)
        if raw is None:
            return None
        return self.parse_line(raw.decode("utf-8", "replace").strip())

    def receive_line(self, timeout=0.2, max_read=65536):
        idx = self._recv_buf.find(b"\n")

        if idx != -1:
            line = bytes(self._recv_buf[:idx])
            del self._recv_buf[: idx + 1]

            return line

        try:
            if self._ep_in is None:
                raise RuntimeError("Not connected")

            chunk = self._ep_in.read(
                max_read,
                int(timeout * 1000),
            )

            self._recv_buf += bytes(chunk)

        except usb.core.USBTimeoutError:
            return None

        idx = self._recv_buf.find(b"\n")

        if idx != -1:
            line = bytes(self._recv_buf[:idx])
            del self._recv_buf[: idx + 1]

            return line

        return None

    def error_hint(self, e):
        message = str(e)
        low = message.lower()

        keywords = (
            "not supported",
            "unimplemented",
            "access denied",
            "insufficient permission",
            "pipe error",
        )

        if any(word in low for word in keywords):
            accessory = ", ".join(
                f"{ACCESSORY_VID:04x}:{pid:04x}" for pid in ACCESSORY_PIDS
            )
            return (
                message
                + " - WinUSB driver not bound to accessory-mode "
                + f"({accessory}). Open the Setup tab, pick this device under"
                + " Install USB Driver, and click Install Driver."
            )

        return message

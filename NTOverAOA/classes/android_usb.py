"""Detect whether a connected USB device is an Android device.

Rather than hardcoding the identity of one tablet, this asks Windows what the
device actually is. Windows publishes a set of class identifiers for every USB
interface a device exposes, and Android's modes each have a well-known one:

    Class_FF&SubClass_42&Prot_01   ADB and Android Open Accessory
    Class_FF&SubClass_42&Prot_03   fastboot
    Class_08&SubClass_01&Prot_50   MTP, the state a modern tablet idles in
    Class_6&SubClass_1&Prot_1      PTP, older Android and digital cameras
    Class_2D                      AOA audio

SetupAPI exposes those through the device's compatible and hardware identifier
lists, so one call answers the question for any Android device regardless of
brand, model, or which mode it is in.

libusb is not used for this on purpose: it cannot read descriptors of devices
bound to a Windows kernel driver such as usbccgp.sys, which is exactly what an
MTP-mode tablet is bound to.

Every failure path reports UNKNOWN rather than raising. UNKNOWN devices are kept
out of the default selector, and the user can always reveal them through the
"Show all USB devices" control.
"""

import ctypes
import re
import sys

UNKNOWN = "unknown"
ANDROID = "android"
NOT_ANDROID = "not_android"

# (class, subclass, protocol) with None meaning "any value at this level".
ANDROID_INTERFACES = {
    (0xFF, 0x42, 0x01),
    (0xFF, 0x42, 0x03),
    (0x08, 0x01, 0x50),
    (0x06, 0x01, 0x01),
    (0x2D, None, None),
    (0xE0, None, None),
}

_CLASS_RE = re.compile(
    r"Class_([0-9A-Fa-f]+)(?:&SubClass_([0-9A-Fa-f]+))?(?:&Prot_([0-9A-Fa-f]+))?",
    re.IGNORECASE,
)


class SP_DEVINFO_DATA(ctypes.Structure):
    _fields_ = [
        ("cbSize", ctypes.c_uint32),
        ("ClassGuid", ctypes.c_uint8 * 16),
        ("DevInst", ctypes.c_uint32),
        ("Reserved", ctypes.POINTER(ctypes.c_ulonglong)),
    ]


_SPDRP_HARDWAREID = 0x00000001
_SPDRP_COMPATIBLEID = 0x00000002
_DIGCF_PRESENT = 0x00000002
_DIGCF_ALL_CLASSES = 0x00010000


def classify_identifier(identifier):
    """Return ANDROID, NOT_ANDROID or UNKNOWN for one PnP identifier string."""
    if not identifier:
        return UNKNOWN

    match = _CLASS_RE.search(identifier)

    if match is None:
        return UNKNOWN

    interface_class = int(match.group(1), 16)

    subclass = int(match.group(2), 16) if match.group(2) else None
    protocol = int(match.group(3), 16) if match.group(3) else None

    for known_class, known_subclass, known_protocol in ANDROID_INTERFACES:
        if interface_class != known_class:
            continue

        # Windows also publishes shorter, less specific forms of the same
        # identifier, so an absent component matches anything at that level.
        if (
            subclass is not None
            and known_subclass is not None
            and subclass != known_subclass
        ):
            continue

        if (
            protocol is not None
            and known_protocol is not None
            and protocol != known_protocol
        ):
            continue

        return ANDROID

    return NOT_ANDROID


def classify_identifiers(identifiers):
    """Combine a device's identifier lists into one verdict.

    UNKNOWN wins over NOT_ANDROID so a device we failed to identify is never
    silently treated as a confirmed non-Android device.
    """
    if not identifiers:
        return UNKNOWN

    saw_known = False

    for identifier in identifiers:
        verdict = classify_identifier(identifier)

        if verdict == ANDROID:
            return ANDROID

        if verdict == NOT_ANDROID:
            saw_known = True

    return NOT_ANDROID if saw_known else UNKNOWN


def device_android_kind(instance_id):
    """Classify a USB device by its Windows PnP instance id."""
    if sys.platform != "win32" or not instance_id:
        return UNKNOWN

    try:
        return _query_android_kind(instance_id)
    except Exception:  # noqa: BLE001 - classification must never break enumeration
        return UNKNOWN


def _query_android_kind(instance_id):
    load_library = getattr(ctypes, "WinDLL", None)

    if load_library is None:
        return UNKNOWN

    setupapi = load_library("setupapi", use_last_error=True)

    setupapi.SetupDiGetClassDevsW.restype = ctypes.c_void_p
    invalid = ctypes.c_void_p(-1).value

    info_set = setupapi.SetupDiGetClassDevsW(
        None, instance_id, None, _DIGCF_PRESENT | _DIGCF_ALL_CLASSES
    )

    if not info_set or info_set == invalid:
        return UNKNOWN

    try:
        data = SP_DEVINFO_DATA()
        data.cbSize = ctypes.sizeof(SP_DEVINFO_DATA)

        if not setupapi.SetupDiEnumDeviceInfo(info_set, 0, ctypes.byref(data)):
            return UNKNOWN

        return classify_identifiers(
            _identifiers(setupapi, info_set, data, _SPDRP_HARDWAREID)
            + _identifiers(setupapi, info_set, data, _SPDRP_COMPATIBLEID)
        )

    finally:
        setupapi.SetupDiDestroyDeviceInfoList(info_set)


def _identifiers(setupapi, info_set, data, property):
    setupapi.SetupDiGetDeviceRegistryPropertyW.argtypes = [
        ctypes.c_void_p,
        ctypes.POINTER(SP_DEVINFO_DATA),
        ctypes.c_uint32,
        ctypes.POINTER(ctypes.c_uint32),
        ctypes.c_void_p,
        ctypes.c_uint32,
        ctypes.POINTER(ctypes.c_uint32),
    ]
    setupapi.SetupDiGetDeviceRegistryPropertyW.restype = ctypes.c_int

    prop_type = ctypes.c_uint32()
    needed = ctypes.c_uint32()

    setupapi.SetupDiGetDeviceRegistryPropertyW(
        info_set,
        ctypes.byref(data),
        property,
        ctypes.byref(prop_type),
        None,
        0,
        ctypes.byref(needed),
        0,
    )

    if not needed.value:
        return []

    buffer = ctypes.create_unicode_buffer(
        needed.value // ctypes.sizeof(ctypes.c_wchar) + 1
    )

    if not setupapi.SetupDiGetDeviceRegistryPropertyW(
        info_set,
        ctypes.byref(data),
        property,
        ctypes.byref(prop_type),
        buffer,
        ctypes.sizeof(buffer),
        ctypes.byref(needed),
        0,
    ):
        return []

    return [part for part in buffer.value.split("\0") if part]

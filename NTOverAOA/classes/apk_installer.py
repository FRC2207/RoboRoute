import os
import shlex
import threading


def _adb_signer():
    from adb_shell.auth.keygen import keygen
    from adb_shell.auth.sign_pythonrsa import PythonRSASigner

    key_path = os.environ.get("NTOVERAOA_ADB_KEY")

    if not key_path:
        key_dir = os.environ.get("ANDROID_USER_HOME")

        if not key_dir:
            sdk_home = os.environ.get("ANDROID_SDK_HOME")
            key_dir = os.path.join(sdk_home, ".android") if sdk_home else None

        if not key_dir:
            key_dir = os.path.join(os.path.expanduser("~"), ".android")

        key_path = os.path.join(key_dir, "adbkey")

    key_dir = os.path.dirname(key_path)

    os.makedirs(key_dir, exist_ok=True)

    private_exists = os.path.isfile(key_path)
    public_exists = os.path.isfile(key_path + ".pub")

    if not private_exists and not public_exists:
        keygen(key_path)
    elif not (private_exists and public_exists):
        raise RuntimeError(
            f"ADB key pair is incomplete at {key_path}; "
            "restore both adbkey and adbkey.pub instead of generating a new key"
        )

    return PythonRSASigner.FromRSAKeyPath(key_path)


def list_adb_targets():
    """List USB devices exposing an ADB interface.

    Enumerates with adb_shell's own libusb1 context and interface matcher, which
    only reads descriptors and never claims the interface, so refreshing this
    list cannot lock a device out of a later install.
    """
    from adb_shell.transport.usb_transport import (
        CLASS,
        PROTOCOL,
        SUBCLASS,
        UsbTransport,
        interface_matcher,
    )

    matcher = interface_matcher(CLASS, SUBCLASS, PROTOCOL)
    targets = []

    for device in UsbTransport.USB1_CTX.getDeviceIterator(skip_on_error=True):
        try:
            if matcher(device) is None:
                continue

            serial = device.getSerialNumber()

            if not serial:
                continue

            name = " ".join(
                part for part in (device.getManufacturer(), device.getProduct()) if part
            )

            vid = device.getVendorID()
            pid = device.getProductID()

            if name:
                label = f"{serial} {name}"
            else:
                label = f"{serial} {vid:04x}:{pid:04x}"

        except Exception:  # noqa: BLE001, S112 - one device must not abort the scan
            continue

        targets.append((serial, label, vid, pid))

    targets.sort(key=lambda target: target[1].lower())

    return targets


def install_apk(apk_path, serial):
    from adb_shell.adb_device import AdbDeviceUsb

    device = None
    remote_path = f"/data/local/tmp/ntoveraoa-{threading.get_ident()}.apk"

    try:
        device = AdbDeviceUsb(serial=serial)
        device.connect(rsa_keys=[_adb_signer()], auth_timeout_s=30)
        device.push(apk_path, remote_path)

        result = device.shell(
            f"pm install -r {shlex.quote(remote_path)}",
            timeout_s=120,
        )
        if isinstance(result, bytes):
            result = result.decode().strip()
        else:
            result = result.strip()

        if "Success" not in result:
            raise RuntimeError(result or "Package manager returned no result")

        return result
    finally:
        if device is not None:
            try:
                device.shell(f"rm -f {shlex.quote(remote_path)}")
            except Exception:  # noqa: BLE001, S110 - cleanup must not mask install result
                pass

            device.close()

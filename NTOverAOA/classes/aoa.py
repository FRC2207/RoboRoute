import usb.core
import usb.util

ACCESSORY_VID = 0x18D1

# Android Open Accessory v2 devices may expose one of these PIDs depending on
# whether ADB and/or audio are enabled. These are the modes that include the
# bulk accessory interface; 2D02 and 2D03 are audio-only.
ACCESSORY_PIDS = (0x2D00, 0x2D01, 0x2D04, 0x2D05)

ACCESSORY_IDS = [(ACCESSORY_VID, product_id) for product_id in ACCESSORY_PIDS]


def is_accessory_id(vid, pid):
    return vid == ACCESSORY_VID and pid in ACCESSORY_PIDS


def find_device(known_devices):
    ids = set(known_devices)
    return usb.core.find(custom_match=lambda d: (d.idVendor, d.idProduct) in ids)


def find_accessory():
    return usb.core.find(
        custom_match=lambda d: (d.idVendor, d.idProduct) in ACCESSORY_IDS
    )


def toggle_accessory_mode(
    device,
    manufacturer,
    model,
    description,
    version,
    uri,
    sn,
):
    try:
        protocol = device.ctrl_transfer(
            usb.util.CTRL_TYPE_VENDOR | usb.util.CTRL_IN,
            51,
            0,
            0,
            2,
        )
    except Exception as e:
        raise RuntimeError(f"AOA GET_PROTOCOL failed: {e}") from e

    if len(protocol) < 2:
        raise RuntimeError(f"AOA GET_PROTOCOL returned only {len(protocol)} byte(s)")

    protocol_version = int(protocol[0]) | (int(protocol[1]) << 8)

    if protocol_version < 1:
        raise RuntimeError(
            "Connected USB device does not support Android Open Accessory mode"
        )

    strings = (
        (0, manufacturer),
        (1, model),
        (2, description),
        (3, version),
        (4, uri),
        (5, sn),
    )

    for index, value in strings:
        try:
            device.ctrl_transfer(
                usb.util.CTRL_TYPE_VENDOR | usb.util.CTRL_OUT,
                52,
                0,
                index,
                value,
            )
        except Exception as e:
            raise RuntimeError(
                f"AOA SET_STRING index={index} value={value!r} failed: {e}"
            ) from e

    try:
        device.ctrl_transfer(
            usb.util.CTRL_TYPE_VENDOR | usb.util.CTRL_OUT,
            53,
            0,
            0,
            None,
        )
    except Exception as e:
        raise RuntimeError(f"AOA START_ACCESSORY failed: {e}") from e

    # The device should now disconnect/re-enumerate.
    # Do not continue using the old PyUSB device object.
    try:
        usb.util.dispose_resources(device)
    except Exception:  # noqa: BLE001, S110 - cleanup must not mask the AOA result
        pass

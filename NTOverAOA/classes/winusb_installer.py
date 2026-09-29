import ctypes
import json
import os
import sys
import tempfile
import time

from .aoa import is_accessory_id


class _WdiDeviceInfo(ctypes.Structure):
    pass


_WdiDeviceInfo._fields_ = [
    ("next", ctypes.POINTER(_WdiDeviceInfo)),
    ("vid", ctypes.c_ushort),
    ("pid", ctypes.c_ushort),
    ("is_composite", ctypes.c_int),
    ("mi", ctypes.c_ubyte),
    ("desc", ctypes.c_char_p),
    ("driver", ctypes.c_char_p),
    ("device_id", ctypes.c_char_p),
    ("hardware_id", ctypes.c_char_p),
    ("compatible_id", ctypes.c_char_p),
    ("upper_filter", ctypes.c_char_p),
    ("driver_version", ctypes.c_uint64),
]


class _WdiCreateListOptions(ctypes.Structure):
    _fields_ = [
        ("list_all", ctypes.c_int),
        ("list_hubs", ctypes.c_int),
        ("trim_whitespaces", ctypes.c_int),
    ]


class _WdiPrepareOptions(ctypes.Structure):
    _fields_ = [
        ("driver_type", ctypes.c_int),
        ("vendor_name", ctypes.c_char_p),
        ("device_guid", ctypes.c_char_p),
        ("disable_cat", ctypes.c_int),
        ("disable_signing", ctypes.c_int),
        ("cert_subject", ctypes.c_char_p),
        ("use_wcid_driver", ctypes.c_int),
        ("external_inf", ctypes.c_int),
    ]


class _WdiInstallOptions(ctypes.Structure):
    _fields_ = [
        ("hWnd", ctypes.c_void_p),
        ("install_filter_driver", ctypes.c_int),
        ("pending_install_timeout", ctypes.c_uint32),
    ]


def _dll_path():
    if sys.platform != "win32":
        raise RuntimeError("WinUSB driver installation is only available on Windows")

    candidates = []
    bundled_root = getattr(sys, "_MEIPASS", None)

    if bundled_root:
        candidates.append(os.path.join(bundled_root, "libwdi", "libwdi.dll"))

    project_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    candidates.append(
        os.path.join(
            project_root, "third_party", "libwdi", "x64", "Release", "dll", "libwdi.dll"
        )
    )
    candidates.append(os.path.join(project_root, "libwdi.dll"))

    for candidate in candidates:
        if os.path.isfile(candidate):
            return candidate

    raise RuntimeError(
        "libwdi.dll was not found. Rebuild the Windows application or place "
        "libwdi.dll next to it."
    )


def _load_library():
    path = _dll_path()
    dll_dir = os.path.dirname(path)
    dll_handle = None

    add_dll_directory = getattr(os, "add_dll_directory", None)

    if add_dll_directory is not None:
        dll_handle = add_dll_directory(dll_dir)

    win_dll = getattr(ctypes, "WinDLL", ctypes.CDLL)
    library = win_dll(path)
    library.wdi_create_list.argtypes = [
        ctypes.POINTER(ctypes.POINTER(_WdiDeviceInfo)),
        ctypes.POINTER(_WdiCreateListOptions),
    ]
    library.wdi_create_list.restype = ctypes.c_int
    library.wdi_destroy_list.argtypes = [ctypes.POINTER(_WdiDeviceInfo)]
    library.wdi_destroy_list.restype = ctypes.c_int
    library.wdi_prepare_driver.argtypes = [
        ctypes.POINTER(_WdiDeviceInfo),
        ctypes.c_char_p,
        ctypes.c_char_p,
        ctypes.POINTER(_WdiPrepareOptions),
    ]
    library.wdi_prepare_driver.restype = ctypes.c_int
    library.wdi_install_driver.argtypes = [
        ctypes.POINTER(_WdiDeviceInfo),
        ctypes.c_char_p,
        ctypes.c_char_p,
        ctypes.POINTER(_WdiInstallOptions),
    ]
    library.wdi_install_driver.restype = ctypes.c_int
    library.wdi_strerror.argtypes = [ctypes.c_int]
    library.wdi_strerror.restype = ctypes.c_char_p

    return library, dll_handle


def _error_message(library, result):
    message = library.wdi_strerror(result)
    return message.decode("utf-8", errors="replace") if message else f"error {result}"


def _device_description(device):
    if not device.desc:
        return ""
    return device.desc.decode("utf-8", errors="replace")


def _device_driver(device):
    if not device.driver:
        return ""
    return device.driver.decode("utf-8", errors="replace")


def _device_id(device):
    if not device.device_id:
        return ""
    return device.device_id.decode("utf-8", errors="replace")


def _create_device_list(library):
    device_list = ctypes.POINTER(_WdiDeviceInfo)()
    list_options = _WdiCreateListOptions(1, 0, 1)

    result = library.wdi_create_list(
        ctypes.byref(device_list), ctypes.byref(list_options)
    )

    if result != 0:
        raise RuntimeError(
            f"Could not enumerate USB devices: {_error_message(library, result)}"
        )

    return device_list


def _target_label(device):
    parts = [f"{device.vid:04x}:{device.pid:04x}"]

    description = _device_description(device)

    if description:
        parts.append(description)

    if is_accessory_id(device.vid, device.pid):
        parts.append("(accessory mode)")

    return " ".join(parts)


def list_driver_targets():
    library, dll_handle = _load_library()
    device_list = _create_device_list(library)

    try:
        targets = []
        current = device_list

        while current:
            device = current.contents
            current = device.next

            device_id = _device_id(device)

            if not device_id:
                continue

            targets.append(
                (
                    device_id,
                    _target_label(device),
                    device.vid,
                    device.pid,
                    is_accessory_id(device.vid, device.pid),
                )
            )

    finally:
        library.wdi_destroy_list(device_list)
        del dll_handle

    # Accessory-mode devices first: they are the ones RoboRoute actually needs,
    # and a tablet that just switched into AOA should not be buried in the list.
    targets.sort(key=lambda target: (not target[4], target[2], target[3], target[1]))

    return targets


def install_winusb_driver(device_id):
    library, _dll_handle = _load_library()
    device_list = _create_device_list(library)

    try:
        selected = None
        current = device_list

        while current:
            device = current.contents
            if _device_id(device) == device_id:
                selected = current
                break
            current = device.next

        if selected is None:
            raise RuntimeError(
                "The selected device is no longer connected. "
                "Refresh the device list and try again."
            )

        if "winusb" in _device_driver(selected.contents).lower():
            return "already-installed"

        with tempfile.TemporaryDirectory(prefix="ntoveraoa-libwdi-") as driver_dir:
            inf_name = b"ntoveraoa-winusb.inf"
            prepare_options = _WdiPrepareOptions(
                0,
                b"NTOverAOA",
                None,
                0,
                0,
                None,
                0,
                0,
            )
            result = library.wdi_prepare_driver(
                selected,
                os.fsencode(driver_dir),
                inf_name,
                ctypes.byref(prepare_options),
            )
            if result != 0:
                raise RuntimeError(
                    f"Could not prepare WinUSB driver: {_error_message(library, result)}"
                )

            install_options = _WdiInstallOptions(None, 0, 120000)
            result = library.wdi_install_driver(
                selected,
                os.fsencode(driver_dir),
                inf_name,
                ctypes.byref(install_options),
            )
            if result != 0:
                raise RuntimeError(
                    f"Could not install WinUSB driver: {_error_message(library, result)}"
                )
    finally:
        library.wdi_destroy_list(device_list)

    return "installed"


def _run_elevated_winusb_install(device_id):
    if sys.platform != "win32":
        raise RuntimeError("WinUSB driver installation is only available on Windows")

    request_fd, request_path = tempfile.mkstemp(
        prefix="ntoveraoa-driver-request-", suffix=".json"
    )
    result_fd, result_path = tempfile.mkstemp(
        prefix="ntoveraoa-driver-result-", suffix=".json"
    )
    os.close(request_fd)
    os.close(result_fd)

    try:
        with open(request_path, "w", encoding="utf-8") as request_file:
            json.dump({"device_id": device_id}, request_file)

        if getattr(sys, "frozen", False):
            executable = sys.executable
            parameters = f'--install-winusb-elevated "{request_path}" "{result_path}"'
        else:
            executable = sys.executable
            script_path = os.path.join(
                os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                "run.py",
            )
            parameters = f'"{script_path}" --install-winusb-elevated "{request_path}" "{result_path}"'

        result = ctypes.windll.shell32.ShellExecuteW(
            None,
            "runas",
            executable,
            parameters,
            None,
            0,
        )
        if result <= 32:
            raise RuntimeError(f"Windows elevation failed with code {result}")

        deadline = time.monotonic() + 180
        while time.monotonic() < deadline:
            if os.path.getsize(result_path) > 0:
                with open(result_path, "r", encoding="utf-8") as result_file:
                    response = json.load(result_file)
                if response.get("ok"):
                    return response.get("status", "installed")
                raise RuntimeError(response.get("error", "WinUSB installation failed"))
            time.sleep(0.1)

        raise RuntimeError("Timed out waiting for the elevated WinUSB installer")
    finally:
        for path in (request_path, result_path):
            try:
                os.remove(path)
            except FileNotFoundError:
                pass


def _handle_elevated_winusb_install(request_path, result_path):
    try:
        with open(request_path, "r", encoding="utf-8") as request_file:
            request = json.load(request_file)

        status = install_winusb_driver(request["device_id"])
        response = {"ok": True, "status": status}
    except Exception as error:  # noqa: BLE001 - serialize installer errors for the caller
        response = {"ok": False, "error": str(error)}

    with open(result_path, "w", encoding="utf-8") as result_file:
        json.dump(response, result_file)

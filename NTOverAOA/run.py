import os
import sys
import threading
import time
import tkinter as tk
from enum import Enum
from tkinter import filedialog, messagebox, ttk

try:
    import crossfiledialog
except Exception:  # noqa: BLE001 - import raises NoImplementationFoundException
    crossfiledialog = None
import usb.core

from classes.android_usb import ANDROID, UNKNOWN
from classes.apk_installer import install_apk, list_adb_targets
from classes.bridge import NTOverUSBBridge
from classes.robot_ip import DriverStationInterop


class IPSource(Enum):
    AUTO = "Auto (Driver Station)"
    MANUAL = "Manual"
    SIMULATION = "Simulation"


DEFAULT_SERVER_IP = "10.22.7.2"

if sys.platform == "win32":
    from classes.winusb_installer import (
        _handle_elevated_winusb_install,
        _run_elevated_winusb_install,
        list_driver_targets,
    )
else:

    def _run_elevated_winusb_install(device_id):
        raise RuntimeError("WinUSB driver installation is only available on Windows")

    def _handle_elevated_winusb_install(request_path, result_path):
        raise RuntimeError("WinUSB driver installation is only available on Windows")

    def list_driver_targets():
        return []


def _resource_path(*parts):
    base = getattr(sys, "_MEIPASS", None)

    if base is None:
        base = os.path.abspath(os.path.join(os.path.dirname(__file__), os.pardir))

    return os.path.join(base, *parts)


class ConnectionState(Enum):
    DISCONNECTED = "disconnected"
    CONNECTING = "connecting"
    CONNECTED = "connected"


class TKApp:
    def _apply_window_icon(self):
        # The logo is a wide lockup, so the active icon is the square, transparency-padded
        # variant: feeding the raw PNG into Tk/Windows used to squash it in the title bar
        # and leave it oddly offset in the taskbar. Prefer a multi-size .ico on Windows
        # (crisp at 16/32/48px); everything else falls back to the square PNG.
        ico = _resource_path("assets", "logo.ico")
        if sys.platform == "win32" and os.path.exists(ico):
            try:
                self.root.iconbitmap(default=ico)
                return
            except tk.TclError:
                pass

        icon_file = _resource_path("assets", "logo_icon.png")
        if not os.path.exists(icon_file):
            icon_file = _resource_path("assets", "logo.png")
        app_icon = tk.PhotoImage(file=icon_file)
        self.root.iconphoto(True, app_icon)
        self._app_icon = app_icon  # keep the image referenced for the life of the app

    def __init__(self, root):
        self.root = root
        self.root.title("NTOverAOA")
        self.root.geometry("560x480")
        self.root.resizable(True, True)
        self.root.protocol("WM_DELETE_WINDOW", self._on_close)

        self._apply_window_icon()

        self.style = ttk.Style()
        self.style.theme_use("clam")

        self.ip_mode_var = tk.StringVar(
            value=(
                IPSource.AUTO.value
                if sys.platform == "win32"
                else IPSource.MANUAL.value
            )
        )
        self.ip_var = tk.StringVar(value=DEFAULT_SERVER_IP)
        self.ds = DriverStationInterop(on_update=self._on_ds_ip)
        self.usb_var = tk.StringVar()
        self.apk_var = tk.StringVar()
        self.apk_usb_var = tk.StringVar()
        self.driver_usb_var = tk.StringVar()
        self.connected = False
        self._connecting = False
        self.usb_state = ConnectionState.DISCONNECTED
        self.nt_state = ConnectionState.DISCONNECTED

        self._install_thread = None
        self._driver_install_thread = None
        self.apk_usb_combo = None
        self.driver_usb_combo = None
        self.driver_install_btn = None
        self.show_all_usb_var = None
        self.show_all_usb_check = None
        self.driver_hint = None
        self._install_lock = threading.Lock()

        self.bridge = NTOverUSBBridge(
            on_log=self._log,
            on_state=self._bridge_state,
            on_subscription=self._on_subscription,
        )
        self.usb = self.bridge.usb

        self._candidates = []
        self._apk_targets = []
        self._driver_targets = []
        self._sub_info = {}

        self._make_ui()
        if sys.platform == "win32":
            self.ds.start()
        self._rescan_for_usb_devices()
        self._rescan_for_apk_targets()
        self._rescan_for_driver_targets()

    def _make_ui(self):
        main = ttk.Frame(self.root, padding="8")
        main.pack(fill=tk.BOTH, expand=True)

        self.notebook = ttk.Notebook(main)
        self.notebook.pack(fill=tk.BOTH, expand=True)

        conn_tab = ttk.Frame(self.notebook, padding="8")
        self.notebook.add(conn_tab, text="Connection")

        conn = ttk.LabelFrame(conn_tab, text="Connection", padding="8")
        conn.pack(fill=tk.X, pady=(0, 8))

        row = ttk.Frame(conn)
        row.pack(fill=tk.X, pady=2)

        ttk.Label(row, text="Robot IP Source:", width=16).pack(side=tk.LEFT)

        self.ip_mode_combo = ttk.Combobox(
            row,
            textvariable=self.ip_mode_var,
            values=[source.value for source in self._available_ip_sources()],
            width=18,
            state="readonly",
        )
        self.ip_mode_combo.pack(side=tk.LEFT)

        self.ip_entry = ttk.Entry(
            row,
            textvariable=self.ip_var,
            width=22,
        )
        self.ip_entry.pack(side=tk.LEFT, padx=(6, 0))
        self.ip_mode_combo.bind("<<ComboboxSelected>>", self._on_ip_mode_selected)
        self._fix_combobox_highlight(self.ip_mode_combo)

        self._update_ip_field()

        row = ttk.Frame(conn)
        row.pack(fill=tk.X, pady=2)

        ttk.Label(row, text="USB Device:", width=12).pack(side=tk.LEFT)

        self.usb_combo = ttk.Combobox(
            row,
            textvariable=self.usb_var,
            width=28,
            state="readonly",
        )
        self.usb_combo.pack(side=tk.LEFT)
        self._fix_combobox_highlight(self.usb_combo)

        ttk.Button(
            row,
            text="Refresh",
            command=self._rescan_for_usb_devices,
            width=8,
        ).pack(side=tk.LEFT, padx=(6, 0))

        status_rows = ttk.Frame(conn)
        status_rows.pack(fill=tk.X, pady=(6, 0))

        self._connection_indicators = {}

        for name in ("USB", "NT"):
            status_row = ttk.Frame(status_rows)
            status_row.pack(anchor=tk.W, pady=1)

            indicator = tk.Canvas(
                status_row,
                width=14,
                height=14,
                highlightthickness=0,
                bg=self.style.lookup("TFrame", "background")
                or self.root.cget("background"),
            )
            indicator.pack(side=tk.LEFT, padx=(2, 6))
            dot = indicator.create_oval(
                2,
                2,
                12,
                12,
                fill="#d32f2f",
                outline="",
            )

            ttk.Label(status_row, text=f"{name} connection").pack(side=tk.LEFT)
            self._connection_indicators[name.lower()] = (indicator, dot)

        ctrl = ttk.Frame(conn_tab)
        ctrl.pack(fill=tk.X, pady=(0, 8))

        self.connect_btn = ttk.Button(
            ctrl,
            text="Connect",
            command=self._toggle,
        )
        self.connect_btn.pack(side=tk.LEFT)

        log_frame = ttk.LabelFrame(conn_tab, text="Log", padding="4")
        log_frame.pack(fill=tk.BOTH, expand=True)

        log_header = ttk.Frame(log_frame)
        log_header.pack(fill=tk.X, pady=(0, 2))

        self.copy_status = ttk.Label(log_header, text="", anchor=tk.W)
        self.copy_status.pack(side=tk.LEFT)

        self.log_text = tk.Text(
            log_frame,
            height=10,
            state=tk.DISABLED,
            wrap=tk.WORD,
            font=("Consolas", 9),
        )
        self.log_text.bind("<Control-KeyPress-c>", self._copy_selection)
        self.log_text.bind("<Control-KeyPress-C>", self._copy_selection)
        sb = ttk.Scrollbar(
            log_frame,
            orient=tk.VERTICAL,
            command=self.log_text.yview,
        )
        sb.pack(side=tk.RIGHT, fill=tk.Y)

        self.log_text.pack(fill=tk.BOTH, expand=True)

        self.log_text.configure(yscrollcommand=sb.set)

        setup_tab = ttk.Frame(self.notebook, padding="8")
        self.notebook.add(setup_tab, text="Setup")
        self.setup_tab = setup_tab

        apk_frame = ttk.LabelFrame(setup_tab, text="Install APK", padding="8")
        apk_frame.pack(fill=tk.X, pady=(0, 8))

        row = ttk.Frame(apk_frame)
        row.pack(fill=tk.X, pady=2)

        ttk.Label(row, text="APK file:", width=12).pack(side=tk.LEFT)

        ttk.Entry(
            row,
            textvariable=self.apk_var,
            width=35,
            state="readonly",
        ).pack(side=tk.LEFT, fill=tk.X, expand=True)

        ttk.Button(
            row,
            text="Browse...",
            command=self._choose_apk,
        ).pack(side=tk.LEFT, padx=(6, 0))

        row = ttk.Frame(apk_frame)
        row.pack(fill=tk.X, pady=(8, 0))

        ttk.Label(row, text="ADB Device:", width=12).pack(side=tk.LEFT)

        self.apk_usb_combo = ttk.Combobox(
            row,
            textvariable=self.apk_usb_var,
            width=32,
            state="readonly",
        )
        self.apk_usb_combo.pack(side=tk.LEFT)
        self._fix_combobox_highlight(self.apk_usb_combo)
        self.apk_usb_combo.bind(
            "<<ComboboxSelected>>", lambda _event: self._sync_apk_install_button()
        )

        ttk.Button(
            row,
            text="Refresh",
            command=self._rescan_for_apk_targets,
            width=8,
        ).pack(side=tk.LEFT, padx=(6, 0))

        self.apk_install_btn = ttk.Button(
            apk_frame,
            text="Install",
            command=self._install_apk,
            state=tk.DISABLED,
        )
        self.apk_install_btn.pack(anchor=tk.W, pady=(8, 0))

        if sys.platform == "win32":
            driver_frame = ttk.LabelFrame(
                setup_tab,
                text="Install USB Driver",
                padding="8",
            )
            driver_frame.pack(fill=tk.X, pady=(0, 8))

            ttk.Label(
                driver_frame,
                text="Replace the selected device driver with WinUSB.",
            ).pack(anchor=tk.W)

            row = ttk.Frame(driver_frame)
            row.pack(fill=tk.X, pady=(8, 0))

            ttk.Label(row, text="USB Device:", width=12).pack(side=tk.LEFT)

            self.driver_usb_combo = ttk.Combobox(
                row,
                textvariable=self.driver_usb_var,
                width=32,
                state="readonly",
            )
            self.driver_usb_combo.pack(side=tk.LEFT)
            self._fix_combobox_highlight(self.driver_usb_combo)
            self.driver_usb_combo.bind(
                "<<ComboboxSelected>>", self._sync_driver_install_button
            )

            ttk.Button(
                row,
                text="Refresh",
                command=self._rescan_for_driver_targets,
                width=8,
            ).pack(side=tk.LEFT, padx=(6, 0))

            self.driver_install_btn = ttk.Button(
                driver_frame,
                text="Install Driver",
                command=self._install_winusb,
                state=tk.DISABLED,
            )
            self.driver_install_btn.pack(anchor=tk.W, pady=(8, 0))

            advanced = ttk.Labelframe(driver_frame, text="Advanced", padding=(6, 4))
            advanced.pack(fill=tk.X, pady=(10, 0))

            self.show_all_usb_var = tk.BooleanVar(value=False)
            self.show_all_usb_check = ttk.Checkbutton(
                advanced,
                text="Show all USB devices (not just this tablet)",
                variable=self.show_all_usb_var,
                command=self._rescan_for_driver_targets,
            )
            self.show_all_usb_check.pack(anchor=tk.W)

            self.driver_hint = ttk.Label(
                advanced,
                text="",
                foreground="#a33",
                wraplength=380,
            )
            self.driver_hint.pack(anchor=tk.W, pady=(2, 0))

        subs_tab = ttk.Frame(self.notebook, padding="8")
        self.notebook.add(subs_tab, text="Subscriptions")

        subs_head = ttk.Frame(subs_tab)
        subs_head.pack(fill=tk.X, pady=(0, 6))

        self.sub_count_label = ttk.Label(subs_head, text="0 subscribed")
        self.sub_count_label.pack(side=tk.LEFT)

        sub_list = ttk.Frame(subs_tab)
        sub_list.pack(fill=tk.BOTH, expand=True)

        self.sub_listbox = tk.Listbox(
            sub_list,
            height=12,
            font=("Consolas", 10),
            activestyle="none",
        )
        self.sub_listbox.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        self.sub_listbox.bind("<Control-KeyPress-c>", self._copy_selection)
        self.sub_listbox.bind("<Control-KeyPress-C>", self._copy_selection)

        sub_sb = ttk.Scrollbar(
            sub_list,
            orient=tk.VERTICAL,
            command=self.sub_listbox.yview,
        )
        sub_sb.pack(side=tk.RIGHT, fill=tk.Y)

        self.sub_listbox.configure(yscrollcommand=sub_sb.set)

        self.notebook.bind("<<NotebookTabChanged>>", self._on_tab_changed)

    def _rescan_for_usb_devices(self):
        try:
            candidates = self.bridge.find_options()
        except (OSError, RuntimeError, usb.core.USBError) as e:
            self._log(f"Device scan failed: {e}")
            return

        self._candidates = candidates
        self.usb_combo["values"] = [candidate[2] for candidate in candidates]

        if candidates:
            self.usb_var.set(candidates[0][2])
        else:
            self.usb_var.set("")

    @staticmethod
    def _selected_key(targets, label):
        for target in targets:
            if target[1] == label:
                return target[0]

        return None

    def _rescan_for_apk_targets(self):
        if self.apk_usb_combo is None:
            return

        try:
            targets = list_adb_targets()
        except Exception as e:  # noqa: BLE001 - device scan must not kill the UI
            self._log(f"ADB device scan failed: {e}")
            return

        previous = self._selected_key(self._apk_targets, self.apk_usb_var.get())

        self._apk_targets = targets
        self.apk_usb_combo["values"] = [target[1] for target in targets]

        kept = next((target for target in targets if target[0] == previous), None)

        self.apk_usb_var.set(kept[1] if kept else "")
        self._sync_apk_install_button()

    def _visible_driver_targets(self, targets):
        if self.show_all_usb_var is not None and self.show_all_usb_var.get():
            return targets

        return [target for target in targets if target[5] == ANDROID]

    def _rescan_for_driver_targets(self):
        if self.driver_usb_combo is None:
            return

        try:
            targets = list_driver_targets()
        except Exception as e:  # noqa: BLE001 - device scan must not kill the UI
            self._log(f"USB device scan failed: {e}")
            return

        previous = self._selected_key(self._driver_targets, self.driver_usb_var.get())

        self._driver_targets = targets

        visible = self._visible_driver_targets(targets)
        self.driver_usb_combo["values"] = [target[1] for target in visible]

        kept = next((target for target in visible if target[0] == previous), None)

        self.driver_usb_var.set(kept[1] if kept else "")

        if self.driver_hint is not None:
            if visible:
                self.driver_hint.config(text="")
            elif not targets:
                self.driver_hint.config(
                    text="No USB devices found. Try another cable or port."
                )
            elif any(target[5] == UNKNOWN for target in targets):
                self.driver_hint.config(
                    text=(
                        "Could not identify the connected devices. Use "
                        "'Show all USB devices' below to see every device."
                    )
                )
            else:
                self.driver_hint.config(
                    text=(
                        "No Android device detected. Make sure the tablet is "
                        "plugged in and unlocked, then refresh."
                    )
                )

        self._sync_driver_install_button()

    def _sync_apk_install_button(self):
        if self.apk_install_btn is None or self._install_lock.locked():
            return

        has_apk = self.apk_var.get().strip().lower().endswith(".apk")
        has_device = (
            self._selected_key(self._apk_targets, self.apk_usb_var.get()) is not None
        )

        self.apk_install_btn.config(
            state=tk.NORMAL if has_apk and has_device else tk.DISABLED
        )

    def _sync_driver_install_button(self):
        if self.driver_install_btn is None or self._install_lock.locked():
            return

        has_device = (
            self._selected_key(self._driver_targets, self.driver_usb_var.get())
            is not None
        )

        self.driver_install_btn.config(state=tk.NORMAL if has_device else tk.DISABLED)

    def _on_tab_changed(self, _event=None):
        if self.notebook.select() == str(self.setup_tab):
            self._rescan_for_driver_targets()

    def _fix_combobox_highlight(self, combo: ttk.Combobox) -> None:
        # On the clam theme the readonly combobox keeps showing the chosen text as
        # selected (and the popdown keeps its highlight) long after the dropdown
        # closes. Disable exportselection, drop the popdown's hover "active" styling,
        # and actively clear the selection whenever the user picks an item or leaves.
        combo.configure(exportselection=False)

        try:
            popdown_path = combo.tk.call("ttk::combobox::PopdownWindow", combo)
            popdown = self.root.nametowidget(popdown_path)
            for child in popdown.winfo_children():
                if isinstance(child, tk.Listbox):
                    child.configure(activestyle="none")
        except (tk.TclError, KeyError):
            pass

        combo.bind("<<ComboboxSelected>>", lambda _e: combo.selection_clear(), add="+")
        combo.bind("<FocusOut>", lambda _e: combo.selection_clear())

    def _available_ip_sources(self):
        if sys.platform == "win32":
            return list(IPSource)
        return [IPSource.MANUAL, IPSource.SIMULATION]

    def _on_ip_mode_selected(self, _event=None):
        self._update_ip_field()

    def _on_ds_ip(self, _ip):
        self.root.after(0, self._update_ip_field)

    def _update_ip_field(self):
        mode = IPSource(self.ip_mode_var.get())

        if mode is IPSource.MANUAL:
            self.ip_entry.config(state=tk.NORMAL)
            return

        if mode is IPSource.SIMULATION:
            self.ip_var.set("127.0.0.1")
        elif self.ds.last_ip:
            self.ip_var.set(self.ds.last_ip)

        self.ip_entry.config(state="readonly")

    def _resolve_server_ip(self):
        mode = IPSource(self.ip_mode_var.get())

        if mode is IPSource.MANUAL:
            return self.ip_var.get().strip()

        if mode is IPSource.SIMULATION:
            return "127.0.0.1"

        if self.ds.last_ip:
            return self.ds.last_ip

        ip = self.ds.fetch_once(timeout=2.0)
        if ip:
            self.root.after(0, self._update_ip_field)
            return ip

        return None

    def _choose_apk(self):
        if crossfiledialog is not None:
            path = crossfiledialog.open_file(
                title="Select APK", start_dir=os.getcwd(), filter="*.apk"
            )
        else:
            path = filedialog.askopenfilename(
                title="Select APK",
                initialdir=os.getcwd(),
                filetypes=[("APK files", "*.apk")],
            )

        if path:
            self.apk_var.set(path)
            self._sync_apk_install_button()

    def _install_apk(self):
        if not self._install_lock.acquire(blocking=False):
            return

        apk_path = self.apk_var.get().strip()
        label = self.apk_usb_var.get()

        if not apk_path.lower().endswith(".apk"):
            self._install_lock.release()
            messagebox.showwarning("Invalid file", "Select an APK file.")
            return

        match = next(
            (target for target in self._apk_targets if target[1] == label),
            None,
        )

        if match is None:
            self._install_lock.release()
            self.apk_usb_var.set("")
            self._sync_apk_install_button()
            messagebox.showwarning(
                "Missing",
                "Select a connected ADB device and refresh the device list if needed.",
            )
            return

        self.apk_install_btn.config(state=tk.DISABLED, text="Installing...")

        self._install_thread = threading.Thread(
            target=self._install_apk_worker,
            args=(apk_path, match[0], label),
            daemon=True,
        )
        self._install_thread.start()

    def _install_apk_worker(self, apk_path, serial, label):
        try:
            self._log(f"Installing {os.path.basename(apk_path)} on {serial}")
            install_apk(apk_path, serial)

        except Exception as e:  # noqa: BLE001 - worker boundary must report install failures
            self.root.after(
                0,
                lambda error=e: messagebox.showwarning(
                    None,
                    f"APK install failed: {error}",
                ),
            )

        else:
            self.root.after(
                0,
                lambda: messagebox.showinfo(
                    "Success",
                    f"Install of {os.path.basename(apk_path)} on {label} was successful",
                ),
            )

        finally:
            self._install_lock.release()
            self.root.after(0, self._reset_apk_install_button)

    def _reset_apk_install_button(self):
        if self.apk_install_btn is not None:
            self.apk_install_btn.config(text="Install")

        self._sync_apk_install_button()

    def _install_winusb(self):
        if not self._install_lock.acquire(blocking=False):
            return

        label = self.driver_usb_var.get()
        match = next(
            (target for target in self._driver_targets if target[1] == label),
            None,
        )

        if match is None:
            self._install_lock.release()
            self.driver_usb_var.set("")
            self._sync_driver_install_button()
            messagebox.showwarning(
                "Missing",
                "Select a connected USB device and refresh the device list if needed.",
            )
            return

        device_id, _, vid, pid, is_accessory, _is_google = match

        if not messagebox.askyesno(
            "Install WinUSB driver",
            f"This replaces the driver on {label} with WinUSB and may require "
            "administrator approval.\n\n"
            f"Device: {vid:04x}:{pid:04x}"
            + (" (accessory mode)" if is_accessory else " (not in accessory mode yet)")
            + "\n\nOther connected devices are not affected. Continue?",
        ):
            self._install_lock.release()
            return

        if self.connected:
            self._disconnect()

        if self.driver_install_btn is None:
            self._install_lock.release()
            return

        self.driver_install_btn.config(state=tk.DISABLED, text="Installing...")

        self._driver_install_thread = threading.Thread(
            target=self._install_winusb_worker,
            args=(device_id, label),
            daemon=True,
        )
        self._driver_install_thread.start()

    def _install_winusb_worker(self, device_id, label):
        try:
            self.usb.disconnect()
            self._log(f"Installing WinUSB on {label}")
            status = _run_elevated_winusb_install(device_id)
            self.root.after(0, self._rescan_for_driver_targets)

        except Exception as e:  # noqa: BLE001 - worker boundary must report install failures
            self.root.after(
                0,
                lambda error=e: messagebox.showwarning(
                    None, f"USB driver installation failed: {error}"
                ),
            )

        else:
            message = (
                f"{label} was already on WinUSB"
                if status == "already-installed"
                else f"Successfully installed WinUSB on {label}"
            )
            self.root.after(0, lambda: messagebox.showinfo("Success", message))

        finally:
            self._install_lock.release()
            self.root.after(0, self._reset_driver_install_button)

    def _reset_driver_install_button(self):
        if self.driver_install_btn is not None:
            self.driver_install_btn.config(text="Install Driver")

        self._sync_driver_install_button()

    def _log(self, msg):
        if threading.current_thread() is not threading.main_thread():
            self.root.after(0, self._log, msg)
            return

        self._append_log(msg)

    def _append_log(self, msg):
        self.log_text.configure(state=tk.NORMAL)
        self.log_text.insert(tk.END, str(msg) + "\n")
        self.log_text.see(tk.END)
        self.log_text.configure(state=tk.DISABLED)

    def _copy_selection(self, event=None):
        selection = None

        if event is not None:
            try:
                selection = event.widget.selection_get()
            except tk.TclError:
                selection = None

        if selection:
            self.root.clipboard_clear()
            self.root.clipboard_append(selection)
            self._flash_copy_feedback(f"Copied {len(selection)} chars")

        return "break"

    def _flash_copy_feedback(self, message):
        self.copy_status.config(text=message)
        if hasattr(self, "_copy_feedback_job") and self._copy_feedback_job is not None:
            self.root.after_cancel(self._copy_feedback_job)
        self._copy_feedback_job = self.root.after(
            2000,
            lambda: self.copy_status.config(text=""),
        )

    def _set_connection_state(self, name, state):
        if not isinstance(state, ConnectionState):
            raise TypeError(f"Unknown {name} connection state: {state}")

        colors = {
            ConnectionState.DISCONNECTED: "#d32f2f",
            ConnectionState.CONNECTING: "#f9a825",
            ConnectionState.CONNECTED: "#2e7d32",
        }

        if name == "usb":
            self.usb_state = state
        elif name == "nt":
            self.nt_state = state
        else:
            raise ValueError(f"Unknown connection: {name}")

        indicator, dot = self._connection_indicators[name]
        indicator.itemconfigure(dot, fill=colors[state])

    def _toggle(self):
        if self.connected or self._connecting:
            self._disconnect()
        else:
            self._connect()

    def _bridge_state(self, name, state):
        connection_state = ConnectionState(state)
        self.root.after(0, self._set_connection_state, name, connection_state)

        if name == "nt" and connection_state is ConnectionState.CONNECTED:
            self.root.after(0, self._mark_connected)
        elif connection_state is ConnectionState.DISCONNECTED:
            self.root.after(0, self._mark_disconnected)

    def _mark_connected(self):
        self.connected = True
        self._connecting = False
        self.connect_btn.config(text="Disconnect")

    def _mark_disconnected(self):
        self.connected = False
        self._connecting = False
        self.connect_btn.config(text="Connect")

    def _on_subscription(self, key, info):
        self.root.after(0, self._apply_subscription_update, key, info)

    def _apply_subscription_update(self, key, info):
        if key is None:
            self._sub_info.clear()
        else:
            self._sub_info[key] = info

        self._rebuild_subs_tree()

    def _rebuild_subs_tree(self):
        first_visible = self.sub_listbox.nearest(0)
        was_scrolled_to_end = self.sub_listbox.yview()[1] >= 0.999
        items = sorted(self._sub_info.items())
        self.sub_listbox.delete(0, tk.END)

        for key, info in items:
            if info["time"] is not None:
                last = time.strftime("%H:%M:%S", time.localtime(info["time"]))
            else:
                last = "-"

            self.sub_listbox.insert(
                tk.END,
                f"{key} = {info['value'] or '-'} ({last})",
            )

        if items:
            if was_scrolled_to_end:
                self.sub_listbox.see(tk.END)
            else:
                self.sub_listbox.see(first_visible)

        self.sub_count_label.config(text=f"{len(items)} subscribed")

    def _connect(self):
        ip = self._resolve_server_ip()
        label = self.usb_var.get()

        if not ip:
            messagebox.showwarning(
                "Missing",
                "Could not get a robot IP from the Driver Station. "
                "Start the Driver Station with a robot connected, or "
                "switch the Robot IP source to Manual and enter the address.",
            )
            return

        if not label or not self._candidates:
            messagebox.showwarning(
                "Missing",
                "Plug in the device and select a the USB device.",
            )
            return

        match = next(
            (candidate for candidate in self._candidates if candidate[2] == label),
            None,
        )

        if match is None:
            messagebox.showwarning(
                "Missing",
                "Selected USB device must've been unplugged. Refreshing.",
            )
            self._rescan_for_usb_devices()
            return

        self.connected = False
        self._connecting = True

        self.connect_btn.config(text="Cancel")
        self.bridge.start(ip, (match[0], match[1]))

    def _disconnect(self):
        self.bridge.stop()

        self.connected = False
        self._connecting = False

        self.connect_btn.config(text="Connect")
        self._set_connection_state("usb", ConnectionState.DISCONNECTED)
        self._set_connection_state("nt", ConnectionState.DISCONNECTED)

    def _on_close(self):
        self.ds.stop()
        self.bridge.stop()
        self.root.destroy()


if __name__ == "__main__":
    if len(sys.argv) == 4 and sys.argv[1] == "--install-winusb-elevated":
        _handle_elevated_winusb_install(sys.argv[2], sys.argv[3])
        raise SystemExit(0)

    root = tk.Tk()
    app = TKApp(root)
    root.mainloop()

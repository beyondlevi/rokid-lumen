# Evidence: ADB and the glasses' screen through the phone

Captured on 2026-10-12 on Levi's phone (Galaxy Z Fold, cover screen) and Rokid glasses, test
builds 0.2.0-beta.22.5 to .8. The sandbox reached both devices only through Tailscale and this
feature. Levi's Mac was not in the path.

| Image | What it shows |
|---|---|
| `ajustes-depuracao-na-mesma-rede.png` | Settings › Developer with wireless debugging on: where the glasses are and `adb connect` on the same network; Tailscale off. |
| `ajustes-tambem-pelo-tailscale.png` | Also through Tailscale on: `adb connect <phone's Tailscale address>:5556`, forwarding over Tailscale only. |
| `autorizar-computador-codigo.png` | Authorize a computer: the glasses' pairing window is open; code and command blurred. |
| `autorizar-computador-pareamento-fechou.png` | The same sheet after the window closed. |
| `desligar-sozinho.png` | Turn off by itself: 1 hour, 4 hours (default), Never. |
| `tela-dos-oculos-apagada.png` | Glasses screen with the glasses' display off: the Wake button. |
| `tela-dos-oculos-ao-vivo.png` | Glasses screen live (Lumen's grid on the glasses). |
| `tela-dos-oculos-como-nos-oculos.png` | As on the glasses: the picture in green. |
| `tela-dos-oculos-fluida.png` | Smooth quality, live. |

Checked on the devices:

**Forwarding**
- With the companion's forward on and the manual relay gone, `adb connect` through the phone's
  Tailscale address gave full adb to the glasses (shell, screencap, install).
- After reinstalling the companion, the forward came back by itself (the switch is kept).

**Pairing**
- A brand-new adb key, in a second adb server in the sandbox, ran `adb pair` through Tailscale
  (port 5557) with the code the sheet showed: "Successfully paired".
- The same key then connected on 5556 as `shell`.

**When the pairing window ends**
- The pairing port and Wireless Debugging's own port closed.
- `adb_wifi_enabled` went back to 0.
- The paired key stays accepted on 5555.

**Glasses screen**
- The companion's own key paired with the glasses by itself.
- The glasses' display showed live in Light and Smooth, and "Save image" wrote a PNG in
  Pictures/Lumen.
- There is always exactly one `screenrecord` on the glasses: Pause ends it, Resume and Smooth
  start a new one, and leaving the page ends it.

**Not tested on the devices**
- Tailscale off on the phone: turning it off would cut the test path.
- The glasses away from a saved Wi-Fi, on the phone's hotspot.
- The 4-hour switch-off.
- The mirror's delay.

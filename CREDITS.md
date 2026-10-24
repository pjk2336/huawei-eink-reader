# Credits

InkReader is an application-level integration built on public research from the
e-ink/NFC community. The protocol discoveries are not claimed as original work
of this project.

## 氧化钙硼 / kur0neko

**Role:** Reverse engineering of the Huawei e-paper PN532/NFC protocol.

The public work includes D3 image transfer, D4 refresh, factory scan layout,
controller image transformations, and related protocol observations. This
project's panel transport and image packing would not exist without that work.

- Gitee: <https://gitee.com/kur0neko>
- Repository: <https://gitee.com/kur0neko/huawei-epd-pn532>
- Required Notice: `Copyright (c) 2026 kur0neko`
- License: PolyForm Noncommercial 1.0.0

The complete upstream license and notice are distributed in
`assets/licenses/`.

## 海猫混合物 / solar2ain

**Role:** Public documentation of D8/APDU definitions and LZO1X framing used by
the ink-screen protocol.

- GitHub: <https://github.com/solar2ain>
- Repository: <https://github.com/solar2ain/ink-nfc-fmsc-by-pn532>

No source files or native binaries from this repository are bundled in the APK.
Its published protocol information was used as research material.

## Other projects

- [Shizuku](https://shizuku.rikka.app/) and
  [Shizuku API](https://github.com/RikkaApps/Shizuku-API): MIT-licensed
  privileged Binder bridge.
- [lzokay-rs](https://github.com/encounter/lzokay-rs) and
  [lzokay](https://github.com/jackoalan/lzokay): MIT-licensed LZO implementation
  used as a format reference and independent test decoder.

These acknowledgements do not imply that the original authors endorse or
maintain InkReader.

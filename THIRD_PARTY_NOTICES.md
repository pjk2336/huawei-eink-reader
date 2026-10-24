# References and notices

The factory image scan layout and D3/D4 protocol implementation were adapted with reference to the public reverse-engineering work of **氧化钙硼**:

- 氧化钙硼 (Gitee: kur0neko), https://gitee.com/kur0neko/huawei-epd-pn532, commit `5d3acf730a988815b637262fd4862c1794ab079e`.
- Required Notice: Copyright (c) 2026 kur0neko
- PolyForm Noncommercial License 1.0.0; the license is retained at repository root and in APK assets, with the upstream notice preserved in `NOTICE`.

Protocol research also used the public work of **海猫混合物** (GitHub: solar2ain), repository solar2ain/ink-nfc-fmsc-by-pn532, commit `0f88cb315eab60e538c102e5883c5f957d9c1be0` (GPL-3.0-or-later), including the published D8/APDU definitions and LZO1X framing. No Python modules or native binaries from that project are embedded. The Android encoder was independently implemented and is tested against lzokay.

The v2.10-test1 panel selector and Java layout implementation also use the published Huawei A0 even/odd controller-row mapping and A1 linear layout as protocol references from that checkout (`images.py`, `catalog.py`, `_assets/drivers.json`). Full-frame scan-01 output was compared byte-for-byte against its `pack_indices` function. Its DB/DA driver recipes are not embedded or written by this app; the board's stored driver is retained.

The Android port adds Shizuku Binder Q-Poll discovery, persistent ISO-DEP transport, D3 frame-buffer preload followed by D4-only turns, target/profile gates, immutable image snapshots, cancellation, and stricter refresh-state reporting. It does not ship PN532 serial transport, replacement display drivers, or vendor test images.

The runtime greedy LZO1X encoder is a Java adaptation of the compression layout
used by `encounter/lzokay-rs`, a pure-Rust port of `jackoalan/lzokay`.
Both are MIT licensed. Copyright 2021 Luke Street. The complete MIT text is
included at `assets/licenses/lzokay-LICENSE.txt` and in the APK assets.


Shizuku API 13.1.5 (api, provider, aidl, shared), copyright Rikka and contributors, MIT License. Source: https://github.com/RikkaApps/Shizuku-API . Full license: assets/licenses/Shizuku-LICENSE.txt.

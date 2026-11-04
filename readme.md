<div align="center">

# oweng

successor to OLCNG, based on owenclave.

<a href="https://count.owenewans.org/openlibrecommunity/olcng?theme=moebooru-h&notitle"><img src="https://count.owenewans.org/openlibrecommunity/olcng?theme=moebooru-h&notitle" alt="repository views"></a>

`kotlin` `go` `android`

</div>

## features

- one built-in server list from 18 subscription sources
- cross-source deduplication by connection settings, independent of profile names
- last-good source snapshots and a bundled offline list
- subscription refresh at startup, after network changes and after VPN connection
- native batch connection tests with cancellation and batched progress updates
- personal profiles, subscriptions, routing and proxy chains
- VLESS, VMess, Shadowsocks, Trojan, Hysteria 2, TUIC, NaiveProxy and other protocols supported by owenclave
- Material 3 Expressive interface

## install

Signed migration candidates are available in [GitHub Actions](https://github.com/openlibrecommunity/olcng/actions/workflows/debug.yml).
Published builds are listed in [releases](https://github.com/openlibrecommunity/olcng/releases).

Install the APK for your device over the corresponding OLCNG installation.
Do not uninstall OLCNG first: its files are used for migration.

- regular APK: `xyz.zarazaex.olc`
- F-Droid APK: `xyz.zarazaex.olc.fdroid`

Both keep the existing signing key. The displayed name changes to oweng;
application IDs stay unchanged so Android can install it as an update.
Old MMKV files are retained during migration.

To build, install JDK 25, Go 1.27.3+, gomobile and gobind, Android SDK
Platform 37.2, Build-Tools 37.0.0 and NDK r29:

```sh
git clone https://github.com/openlibrecommunity/olcng
cd olcng
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/29.0.14206865"
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
./run lib core
./bin/lib/naive/download.sh
./gradlew :app:downloadAssets
./gradlew :app:assembleOssRelease :app:assembleFdroidRelease
```

Local signing uses `KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and
`KEY_PASSWORD`. CI uses the existing `APP_KEYSTORE_*` secrets.

## usage

Open the shared list, run a connection test, select a working profile and connect.
The group screen also supports personal subscriptions and profiles.

The first launch explains the change from OLCNG in Russian. Subscription
updates retain cached results when a source is unavailable.

## license

[GPL-3.0](LICENSE)

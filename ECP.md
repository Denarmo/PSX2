# PSX2 ECP: the Emulator Control Protocol in PSX2

This fork of [PSX2](https://github.com/izzy2lost/PSX2) implements the
[Emulator Control Protocol](https://github.com/Denarmo/emulator-control-protocol) (ECP) at
level 2, so a frontend such as EmuDEN can start a PS2 game in it and control the session: pause,
the frontend's own menu over the game, save states with thumbnails, reset, fast forward and slow
motion, settings, volume, screenshots, the performance overlay and performance figures.

It is meant as a worked example for other emulator developers. Read it beside the protocol's
`SPEC.md` and `GUIDE.md`. PSX2 and this fork are GPL-3.0; the ECP specification and SDK are MIT.

| | |
|---|---|
| Package | `com.izzy2lost.psx2.ecp`, app name "PSX2 ECP". It installs beside PSX2 from the Play Store and keeps its own data. |
| ECP activity | `com.izzy2lost.psx2.EcpPlayActivity` |
| Descriptor | `app/src/main/res/raw/ecp.json` |
| Branch | `ecp` |

## What changed

The fork keeps PSX2's own app as it is. Launched from the app drawer, PSX2 ECP is the normal PSX2
with its library, settings and menus. The ECP work is in these places:

| File | Change |
|---|---|
| `AndroidManifest.xml` | `EcpPlayActivity` with the `io.github.ecp.action.PLAY` filter and the `io.github.ecp.descriptor` meta-data. It runs in its own task (`taskAffinity`), `singleTask`, removed from recents when it ends. |
| `res/raw/ecp.json` | The descriptor. `id` and `version` are replaced at run time by the package name and `versionName`, so they cannot drift. |
| `EcpPlayActivity.java` | A subclass of `MainActivity`, the normal game screen, that owns one session. Without the ECP extras (level 0) it boots the intent's data with PSX2's own menus. |
| `EcpSession.java` | The session on top of the SDK's `EcpEngine`: launch, lifecycle events, every level 2 request, the menu chord and the menu hand-over. |
| `EcpSettings.java` | The settings schema: the standard keys PSX2 has and its own under `psx2.`. |
| `ecp-sdk/` | The ECP Android SDK (MIT), vendored unchanged from the protocol repository (commit `6a3a1d3`, build file included) as the Gradle module `:ecp`. It does the channel (loopback TCP, framing, auth), hello, request routing, the `paused`/`resumed` bookkeeping, `stopped` and `MENU_INTENT`. Requests run one at a time on the SDK's own worker thread, so handlers block on the core where PSX2 does (a state save waits up to 5 s); `stop` and a closed channel come on its separate control thread. Anything that touches the activity goes through `runOnUiThread`. |
| `MainActivity.java` | Protected hooks with no-op defaults, so the base activity behaves as upstream: `isLaunchedForGame`, `isFrontendMenuAttached`, `onFrontendMenuRequested`, `isFrontendPauseRequested`, `applySessionSettings`, `onEmulationThreadEnded`, `onSessionInputEvent`, `startSessionGame`, `refreshPauseState`. |
| `NativeApp.java`, `cpp/native-lib.cpp` | New JNI functions prefixed `ecp` (below). Two changes to the VM loop that also apply to PSX2's own UI: while paused it now serves queued CPU-thread work (it used to sleep 250 ms and leave the queue alone, so a speed change made while paused waited for the game to resume), and it carries out a requested reset (`VMState::Resetting` used to leave the loop idling for ever). |
| `app/build.gradle.kts` | `applicationId`, `versionName` `1.3.2-ecp1`, APK name, and signing from a properties file outside the tree (`-Ppsx2.keystoreProperties=<path>` or `PSX2_KEYSTORE_PROPERTIES`). |

Build: `gradlew assembleRelease -Ppsx2.keystoreProperties=<path>` (or `build-release.bat` for
the AAB). Without signing properties the release falls back to the debug key, as upstream.

## Starting a game

The frontend starts `EcpPlayActivity` with the extras of SPEC section 3. The activity is PSX2's
game screen, so its surface and input are the ones PSX2 already tested; it only skips the setup
wizard, the game library, the BIOS prompt and the RetroAchievements prompt.

1. `EcpSession.start()` connects to `127.0.0.1:PORT` on a worker thread, sends `auth`, then
   `hello` with the descriptor. Without the frontend's hello within 10 s, or without `launch`
   within 60 s, the activity finishes.
2. The frontend's hello fixes the capabilities: those the frontend listed, out of PSX2's.
   With `menu`, the settings drawer is locked closed and PSX2's own menus stay shut.
3. `launch`:
   - `content: "intent"` takes the intent's data URI (or `io.github.ecp.extra.PATH`); any other
     string is taken as a URI or path. A `content://` URI is passed to the core as it is, the way
     PSX2's library passes the documents it scanned: the core opens it through
     `NativeApp.openContentUri` (a `ContentResolver` file descriptor), so nothing is copied. The
     session first opens it once to answer `content_missing` early.
   - `.acgame` arcade manifests are refused (`content_invalid`): they name other files beside
     them, which needs PSX2's folder grant.
   - No verified retail BIOS gives `bios_missing` with a message saying where to add one (PSX2
     ECP's menu, Import / Manage BIOS). No BIOS is bundled.
   - Another game running in PSX2 ECP's own screen gives `busy`.
   - Otherwise the result is ok, `loading` follows (with the title), and the game boots through
     the same `restartEmuThread` path PSX2's library uses.
4. `first_frame` and `running` are sent once the GS has drawn into two frames since `launch`
   (`ecpGetPerf`: PSX2's drawn-frame counter for hardware renderers, the frame number for the
   software renderer). The frame counter alone counts the black vsyncs of the boot, and a state
   saved then had no thumbnail.
5. If the VM thread fails to start, `stopped` carries `reason: "error"` and the core's own
   message, with `bios_missing` when it is about the BIOS and `content_invalid` otherwise.

## Requests and the PSX2 code behind them

| Request | Capability | PSX2 code |
|---|---|---|
| `pause`, `resume` | `pause` | A frontend pause owner beside PSX2's own (dialogs, drawers, activity in background): `isFrontendPauseRequested` feeds `applyRequestedPauseState`, which calls `NativeApp.pause()/resume()` (`VMManager::SetPaused`). The reply waits until the VM state matches. `resume` also clears the menu, brings the activity back if it can, and ignores held buttons (below). |
| `paused`, `resumed` events | `pause` | A 50 ms watcher compares `NativeApp.isPaused()` with what the frontend was told. The reason is `menu` during the hand-over, `frontend` for its pause, `focus` for anything else (the activity going to the background, a system dialog). |
| menu hand-over | `menu` | The chord from `launch`, Back, PSX2's pause button, its menu button and Select+Start all call `EcpSession.requestMenu`: pause owner, wait for the pause, `paused` (`menu`), `menu_request` with the frame (`ecpCaptureFrame`), then `MENU_INTENT`. The frame is captured at internal resolution with the game's aspect ratio and scaled to 1280 wide. |
| `save_state` | `states`, `state_thumbnails` | `NativeApp.saveStateToSlot` (`VMManager::SaveStateToSlot`, synchronous). The state file's time checks that it was written. The thumbnail is the `Screenshot.png` PCSX2 stores in the state (`getImageSlot`, 640x480), or a fresh capture if the state has none. `saveStateToSlot` pauses the VM to save; the session puts the pause state back afterwards and does not report that pause as `paused`/`resumed`. The same holds for `load_state`. |
| `load_state` | `states` | `NativeApp.loadStateFromSlot`. A missing file is `slot_empty`; a failed load is `state_incompatible`. |
| `list_states` | `states` | Every slot 0-99 whose file exists (`getGamePathSlot`), with its time and thumbnail. |
| `delete_state` | `states` | `ecpDeleteStateSlot`: the slot's file and its `.backup`. |
| `reset` | `reset` | `ecpReset`: `VMManager::Reset()` on the CPU thread. `hard` makes no difference: a PS2 reset is a full system reset. |
| `set_speed` | `fast_forward`, `slow_motion` | `ecpSetLimiterMode` (`VMManager::SetLimiterMode`: nominal, turbo, slow motion, unlimited). `factor` above 1 sets `Framerate/TurboScalar`, below 1 `Framerate/SlomoScalar`. |
| `screenshot` | `screenshot` | `ecpCaptureFrame(0, 0)`: `MTGS::SaveMemorySnapshot` at the internal resolution with the aspect ratio applied, scaled down to 2560 wide at most. On the CPU thread while running, directly while paused (as `saveStateToSlot` does). |
| `set_volume` | `volume` | `ecpSetVolume`: `SPU2/Output` `StandardVolume`, `FastForwardVolume` and `OutputMuted`. |
| `hud_level` | `hud` | `ecpSetHudLevel`: PCSX2's OSD. 1 shows FPS and speed, 2 adds CPU, GPU and frame times, 3 everything PSX2's own HUD switch shows. |
| `notify` | `notify` | `ecpNotify`: `Host::AddOSDMessage`. |
| `perf` event | `perf` | Once a second while not paused, from `ecpGetPerf`: `fps` (`PerformanceMetrics::GetFPS`, vertical syncs), `target_fps` (`VMManager::GetFrameRate`), `speed`, `frame_ms`, `cpu_ms`, `gpu_ms`, plus `internal_fps` (the game's own frame rate where PCSX2 can tell). |
| `get_schema`, `get_settings`, `set_setting` | `settings` | `EcpSettings` (below). |
| `stop` | | `NativeApp.shutdown()` (the core writes the memory cards as it shuts down), wait for the VM thread, answer, `stopped` (`frontend`), finish. It runs on the SDK's control thread, so a slow request cannot hold it up. |
| channel closed | | The same shutdown without events, then the activity finishes; `onDestroy` ends the process as PSX2's own exit does. |

Slots: ECP slot 0, the frontend's resume slot, is PCSX2's resume state (`<serial> (<crc>).resume.p2s`).
Slots 1-99 are PCSX2's numbered slots, so slots 1-10 are the same states PSX2's own Save States
screen shows. States are kept by disc serial and CRC, not by the frontend's `game_id`: the same
disc has the same states however it is started.

### Settings

Settings from ECP are applied for the session only (SPEC 6.4). They go to the core's in-memory
settings layer through the native setters PSX2's own menus use, and nothing is written to
SharedPreferences; the next session starts from PSX2's own settings again, plus whatever the
frontend sends in `launch`. Launch settings are applied after PSX2's stored settings and before
the VM starts. PSX2 then applies its per-game overrides while booting, so the session applies the
launch settings again once the first frame is out, where they differ.

| Key | Type | PSX2 setting |
|---|---|---|
| `video.internal_scale` | range 1-8 | `EmuCore/GS upscale_multiplier` |
| `video.aspect` | `auto` `4:3` `16:9` `stretch` | `EmuCore/GS AspectRatio` (auto is PCSX2's "Auto 4:3/3:2") |
| `video.widescreen_patches` | toggle | `EmuCore EnableWideScreenPatches` |
| `video.renderer` | `auto` `vulkan` `opengl` `software` | `EmuCore/GS Renderer` |
| `video.texture_filter` | `nearest` `linear` `ps2` `linear_no_sprites` | `EmuCore/GS filter` |
| `video.vsync` | toggle | `EmuCore/GS VsyncEnable` |
| `audio.volume` | range 0-100 | `SPU2/Output StandardVolume` |
| `speed.limit` | toggle | limiter mode nominal or unlimited |
| `speed.fast_forward_factor` | range 2-10 | `Framerate TurboScalar` |
| `psx2.blending_accuracy` | choice 0-5 | `EmuCore/GS accurate_blending_unit` |
| `psx2.anisotropic_filtering` | choice 0, 2, 4, 8, 16 | `EmuCore/GS MaxAnisotropy` |
| `psx2.no_interlacing_patches` | toggle | `EmuCore EnableNoInterlacingPatches` |
| `psx2.fxaa` | toggle | `EmuCore/GS fxaa` |
| `psx2.edge_crop` | range 0-16 step 4 | `EmuCore/GS CropLeft`, `CropRight` |
| `psx2.texture_replacements` | toggle | `EmuCore/GS LoadTextureReplacements` |
| `psx2.shade_boost` | toggle | `EmuCore/GS ShadeBoost` |

All of them apply `now`. Values are read back from the core (`ecpGetIniValue`), so `get_settings`
shows what is in effect, per-game overrides included.

### Input

- The menu chord's RetroPad names are matched against Android key codes: `b` is
  `KEYCODE_BUTTON_A` (bottom), `a` is `BUTTON_B`, `y` is `BUTTON_X`, `x` is `BUTTON_Y`,
  `l`/`r` are L1/R1, `l3`/`r3` the stick clicks. The chord is matched on the physical keys before
  PSX2's own remapping, and the key that completes it does not reach the game.
- Buttons held when the menu opens or the game resumes are ignored until released (SPEC 6.1):
  every key event whose `getDownTime()` is older than that moment is dropped, and the core's pad
  state is cleared (`resetKeyStatus`). Comparing the down time avoids keeping a list of held
  keys, which goes stale while the frontend has the input.

## Not supported, and why

| | |
|---|---|
| `rewind` | PCSX2 has no rewind. |
| `cheats` | PSX2's cheats are `.pnach` files per game; ECP's `set_cheats` sends raw codes. Mapping them needs a code format ECP does not define yet. |
| `discs` | PSX2 has no disc swap from its UI, and ECP gives the engine one content URI. |
| `applets` | PS2 games ask for nothing outside the game. |
| `oneshot` | Android has no one-shot commands (SPEC 7). |
| `fullscreen: false` | PSX2's game screen is always immersive. |
| `.acgame` arcade sets | They reference other files next to the manifest, which a single content URI does not grant. |
| L2/R2 in the chord on analog triggers | A trigger that only reports an axis (no key event) cannot complete the chord, and is not held back after resume. |

No window before `launch` (SPEC 9) is not possible on Android: the activity is on screen from the
moment the frontend starts it. It shows black until the game boots.

## Testing

- Build: `gradlew assembleRelease` on Windows (AGP 9.2, NDK 30, compileSdk 37).
- Conformance: `ecp-probe` from the protocol repository drives the app on a device over adb
  (`adb reverse` for the port, `am start` with the session extras):

  ```
  adb push game.iso /sdcard/Android/data/com.izzy2lost.psx2.ecp/files/game.iso
  python conformance/ecp-probe.py --adb com.izzy2lost.psx2.ecp/com.izzy2lost.psx2.EcpPlayActivity \
      --content file:///sdcard/Android/data/com.izzy2lost.psx2.ecp/files/game.iso --menu-timeout 40
  ```

  The app reads its own external files directory without any permission, so a `file://` data URI
  there works; a frontend passes a `content://` URI instead. During `--menu-timeout`, press Back or
  the menu chord on the device to start the menu hand-over. adb cannot pass `MENU_INTENT`, so the
  frontend's menu does not appear; `menu_request` and `paused` still arrive.

## Verified

The figures below are from the build before the SDK update to commit `6a3a1d3` (SDK worker
threads, Java names, `stopped` detail). That build has not been re-run on the device yet.

On a Retroid Pocket Duo (Android 16), release build signed with the PSX2 ECP key, Colin McRae
Rally 3 (PAL), `ecp-probe --adb` with `--menu-timeout` (Back pressed on the device): 46 passed,
0 failed, 1 warning, 5 skipped (rewind, cheats, discs, and the desktop-only screenshot to a file).
The warning: a screenshot taken right after `load_state` while paused still shows the frame from
before the load, because the GS presents the loaded frame only when the game runs again.

A scripted session also checked: launch settings in effect (`video.internal_scale` 2,
`video.aspect` 16:9, `audio.volume` 80 read back by `get_settings`), save to slots 3 and 0 with
640x480 thumbnails, `list_states`, the menu hand-over from Back (219-297 ms to `menu_request`,
`paused` reason `menu`) and from the L3+R3 chord (`adb shell input keycombination`), load, resume,
perf at 50 fps on a PAL game, and stop in under 0.5 s. Without a BIOS, `launch` fails with
`bios_missing`.

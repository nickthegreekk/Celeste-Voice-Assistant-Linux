# Desktops and devices

What this application expects of the machine it runs on, and what has to be configured *outside* it.
Two things live here: the hands-free hotkey (PLAN.md §5, seam 12) and the audio prerequisites.

## The hotkey

Android had no hotkey — the phone offered a proximity gesture, "wave to interrupt". On a desktop the
replacement is your window manager's: **bind a key that runs the application with `--listen`**, which
starts it with the microphone armed. There is no in-process key grabber and none is planned; a
compositor's shortcut mechanism is the one place that knows your keyboard layout, your modifier
choices and which application has focus.

```sh
celeste-linux --listen
```

The window opens with listening already on — one process holds both, so the hotkey shows you the
transcript as it happens rather than listening invisibly. (Before it is packaged, the same flag runs
from a source checkout with `./gradlew :desktop:run --args="--listen"`; that is fine for trying it and
too slow for a hotkey, so build the app image with `./gradlew :desktop:packageDistribution` first and
bind the binary it produces.)

**Pressing it while the application is already running does nothing but explain itself.** A second
copy would be a second microphone session — both would transcribe you and both would answer — so the
second launch finds the instance lock taken, names the pid that holds it and exits non-zero. To arm
listening in a running window, use the microphone button in it. "Arm it from outside" belongs to the
tray, which is M4's, because doing it from here would mean a second process telling the first what to
do, and that is the IPC design this project does not have (PLAN.md §3/D1).

### Binding it

| Desktop | Where |
|---|---|
| KDE Plasma | System Settings → Keyboard → Shortcuts → **Add** → *Command or Script*: `celeste-linux --listen` |
| GNOME | Settings → Keyboard → **Custom Shortcuts** → `+` (name it, put the command in *Command*, then press the keys) |
| Xfce | Settings → Keyboard → **Application Shortcuts** → *Add* |
| sway | `bindsym $mod+Shift+v exec celeste-linux --listen` |
| i3 | `bindsym $mod+Shift+v exec --no-startup-id celeste-linux --listen` |
| Hyprland | `bind = SUPER SHIFT, V, exec, celeste-linux --listen` |

Pick a key that is not the compositor's own. `$mod+Shift+V` reads as "voice" and is free on a default
sway/i3/Hyprland configuration; Plasma and GNOME have their own conventions, and the shortcut editor
will tell you when a combination is taken.

A future milestone may add the XDG GlobalShortcuts portal, which would let the application register
its own accelerator and spare you this section. It is marked optional in the plan and is not built.

## Choosing the devices

- **Microphone** — Settings → **Audio**. One choice for the whole application: it is what hands-free listening and
  push-to-talk record from. A change applies the next time listening starts, not to a session already running.
- **Output** — the persona editor's **Output device** row, per persona, because "this conversation goes to my
  headphones" is a property of the conversation. *System default* is the first entry and means exactly that.

Both lists are read from the sound server when the screen opens, so a device that is plugged in while a picker is
already open will appear the next time it is opened. A device that is chosen and later disappears is not an error:
the application falls back to the system default and logs which device it was, because a persona whose headphones are
unplugged should still be able to speak.

## Audio

- **Headphones, or push-to-talk.** There is no acoustic echo cancellation, by decision (README.md, and
  PLAN.md §3/D8): the phone got it from the platform and there is no counterpart here. Without
  headphones the microphone hears the reply and the recogniser transcribes it — the failure looks like
  the assistant interrupting itself, not like an error. A PipeWire `module-echo-cancel` sink selected
  as the input device is the system-level answer if you want speaker playback anyway.
- **A default source, on a machine that has not chosen one.** PipeWire's session manager decides which
  device a bare `pw-record` opens, and a fresh machine often has no input marked as default — WirePlumber
  then answers "no target node available". The application falls back to the first input it enumerates
  and **logs which one it chose**, which is a guess you should replace with a decision:

  ```sh
  wpctl status                          # find the input's serial or name
  wpctl set-default <id>
  ```

  Setting it makes the application's behaviour the same on every launch instead of dependent on
  enumeration order. `--selftest` prints what it resolved, including which device each list considers
  the default.
- **Monitor sources are not listed as inputs.** PulseAudio exposes every output's monitor as a capture
  device; they are real devices and the wrong ones here, because feeding the system's own playback to a
  speech recogniser fails as an incomprehensible transcript rather than as an error.

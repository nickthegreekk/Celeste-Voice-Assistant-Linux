package com.nikosm.voiceassistant

// PARTIALLY PORTED IN M1. One function: the shared leaf `personaBackdropPath`, which
// MainVoiceScreen calls to decide whether the persona's backdrop belongs behind the transcript.
//
// Everything else in the upstream file is the Room feature — `RoomPickerRows` and `RoomSettings`
// (called from SettingsScreens), `RoomChatArea` (called from MainVoiceScreen), the editor, the
// member list and the round-status line, plus the pure room helpers above them
// (`roomSpeakerLabel`, `roomMemberCandidates`, `roomCapSummary`, …). Those are M3's, and the three
// call sites that reach them carry `DEFERRED TO M3` markers rather than this file carrying stubs.
// PLAN.md §7's row for this file says "partially ported in M1", and docs/PROVENANCE.md repeats it,
// because a partially ported file is a state worth stating rather than implying.

/**
 * Which backdrop, if any, the main screen may paint behind itself — `null` for none.
 *
 * The backdrop is a PERSONALITY trait: the photo the user chose for one persona, plus the
 * scrim that keeps text legible over it. Room mode is not that persona's chat — it is a shared
 * conversation with no persona of its own — so a room must never wear it. The screen used to
 * draw the backdrop unconditionally from the currently selected persona, which meant a room
 * inherited whichever persona happened to be selected when the room was opened: someone
 * else's photo (and the darkened scrim) behind the room transcript.
 *
 * Takes the ALREADY-RESOLVED [activeRoom] rather than an id, so this decision cannot drift
 * from the one the render gate itself uses (`rooms.find { it.id == activeRoomId }`) — a stale
 * id naming a room that no longer exists is not room mode, and must keep the backdrop.
 *
 * Blank stays blank here too: a stored-but-empty path is no image, exactly as before.
 */
internal fun personaBackdropPath(activeRoom: Room?, personaBackgroundPath: String?): String? =
    if (activeRoom != null) null else personaBackgroundPath?.takeIf { it.isNotBlank() }

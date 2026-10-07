# Origin

Copied from [air-gestures](https://gitlab.com/896kb/air-gestures) `band-core` at commit
`91e7217` (2026-09-25). Fixes to the protocol core should land there first and be copied
over; keep this note's commit up to date when you do.

`src/pointer.rs` (the air mouse's math) and the session's motion-sample events
(`set_motion_samples`, `Event::Gyro`, `Event::Orientation`) don't come from air-gestures: they
were ported from kinesis' `AirCursor.swift` (callbacked/kinesis 5c78485, MIT) for Lumen.

The session skips a lone invalid orientation sample instead of ending (`BAD_ORIENTATION_LIMIT`
in a row still end it). air-gestures ends the session on the first one; on the glasses that cost
a reconnect, four in half a minute right after the band came back from the phone.

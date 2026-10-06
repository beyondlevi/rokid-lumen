//! The Android app's view of a band connection: bytes in, bytes out, plus
//! action names to run and a status snapshot. Kotlin owns the Bluetooth socket
//! and the clock; this owns the band-core session, the keepalive timing (as the
//! desktop daemon's link loop does) and the gesture controller (copied from
//! air-gestures: held taps, the double-tap wait, the toggle hold, the dial).

use band_core::ceremony::{CeremonyHttpRequest, OwnershipCeremony};
use band_core::events::Event;
use band_core::identity::EnrollmentIdentity;
use band_core::session::BandSession;
use serde::Serialize;

pub mod config;
pub mod controller;
mod jni_api;
pub mod status;

use config::{Config, DIAL_TOGGLE, DialTarget};
use controller::{Command, Controller, POINTER_OFF, PointerTuning};
use status::Status;

fn config_with(mapping: &str) -> Config {
    let mut config = Config::default();
    config.apply_mapping(mapping);
    config
}

/// Battery query period and the quiet time before a stream-state query. A battery reading a
/// minute is plenty (the glasses show it every 30 s); with the motion streams off traffic is
/// sparse, so the quiet query waits longer then (each query wakes the band's radio).
const BATTERY_EVERY: f64 = 60.0;
const QUIET_QUERY: f64 = 2.0;
const QUIET_QUERY_NO_MOTION: f64 = 30.0;

pub struct Connection {
    session: BandSession,
    controller: Controller,
    live: bool,
    last_read: f64,
    last_query: f64,
    next_battery: f64,
    /// Motion streams and gestures wanted (see `set_motion`, `set_gestures`), and what was last
    /// sent.
    motion: bool,
    gestures: bool,
    streams_sent: (bool, bool),
    actions: Vec<String>,
    log: Vec<String>,
    /// The ownership ceremony's events (claim mode), as JSON lines for the app.
    claim: Vec<String>,
    /// The owner key the band is about to commit (after `claim_pair_completed`).
    claim_pending: Option<Vec<u8>>,
    /// The handwriting capture's events, as JSON lines for the app.
    handwriting: Vec<String>,
    /// When the air mouse's motion delay was last logged.
    delay_logged: f64,
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

/// The owner key file's bytes for an identity: the 32-byte scalar, plus the band's
/// 65-byte key when known (97 bytes), as `owner.key` holds them.
fn owner_key(identity: &EnrollmentIdentity) -> Vec<u8> {
    let mut bytes = identity.private_bytes().to_vec();
    if let Some(band) = identity.band_key_x963() {
        bytes.extend(band);
    }
    bytes
}

#[derive(Serialize)]
struct Snapshot<'a> {
    connected: bool,
    #[serde(flatten)]
    status: &'a Status,
}

impl Connection {
    /// `owner_key` is the desktop's `owner.key` (32 or 97 bytes); `scheme_guess`
    /// its `band.json` value (0 for current firmware). `paused` and `dial` carry
    /// the app's settings over from the previous connection.
    pub fn new(
        owner_key: &[u8],
        scheme_guess: usize,
        paused: bool,
        dial: &str,
        mapping: &str,
    ) -> Result<Self, String> {
        let (private, band) = match owner_key.len() {
            32 => (owner_key, None),
            97 => (&owner_key[..32], Some(&owner_key[32..])),
            length => return Err(format!("owner.key must be 32 or 97 bytes, not {length}")),
        };
        let identity = EnrollmentIdentity::from_bytes(private, band).map_err(|e| e.to_string())?;
        Ok(Self::with_session(
            BandSession::new(Some(identity), None, false).with_scheme_guess(scheme_guess),
            paused,
            dial,
            mapping,
        ))
    }

    /// A connection that claims a band in pairing mode (the ownership ceremony, with a fresh
    /// app key) instead of signing in with a stored one. Its HTTP steps and outcome come out of
    /// `take_claim_events`; the app answers with `claim_pair_request_completed` and
    /// `claim_pair_completed`. Once claimed it carries on as a normal connection.
    pub fn new_claim(scheme_guess: usize, paused: bool, dial: &str, mapping: &str) -> Self {
        Self::with_session(
            BandSession::new(None, Some(OwnershipCeremony::default()), false).with_scheme_guess(scheme_guess),
            paused,
            dial,
            mapping,
        )
    }

    fn with_session(session: BandSession, paused: bool, dial: &str, mapping: &str) -> Self {
        let mut controller = Controller::new(config_with(mapping));
        if paused {
            controller.pause();
        }
        // An empty `dial` keeps what the mapping chose.
        if !dial.is_empty() {
            controller.set_dial_target(DialTarget::from_name(dial));
        }
        Self {
            session,
            controller,
            live: false,
            last_read: 0.0,
            last_query: 0.0,
            next_battery: 0.0,
            motion: true,
            gestures: true,
            streams_sent: (true, true),
            actions: Vec::new(),
            log: Vec::new(),
            claim: Vec::new(),
            claim_pending: None,
            handwriting: Vec::new(),
            delay_logged: 0.0,
        }
    }

    /// The first bytes to write once the L2CAP channel is open.
    pub fn request(&mut self) -> Result<Vec<u8>, String> {
        self.session.request().map_err(|e| e.to_string())
    }

    /// Bytes read from the band at `now` (seconds, monotonic); returns bytes to write.
    pub fn feed(&mut self, bytes: &[u8], now: f64) -> Result<Vec<u8>, String> {
        self.last_read = now;
        let result = self.session.feed(bytes, now).map_err(|e| e.to_string())?;
        self.controller.set_writing(self.session.handwriting_active());
        let mut outgoing = result.outgoing;
        for event in &result.events {
            outgoing.extend(self.on_event(event, now)?);
        }
        outgoing.extend(self.pointer_waits(now)?);
        Ok(outgoing)
    }

    /// A switch pinch's wait that came due (checked on every read, about every 15 ms while
    /// motion flows, and every tick); keeps the motion samples in step with the air mouse.
    fn pointer_waits(&mut self, now: f64) -> Result<Vec<u8>, String> {
        let mut outgoing = Vec::new();
        if self.controller.pointer_due().is_some_and(|due| now >= due) {
            let gestures = self.controller.status().gestures;
            for command in self.controller.fire_pointer_wait(now) {
                outgoing.extend(self.apply(command, now)?);
            }
            let status = self.controller.status();
            if status.gestures != gestures
                && let Some(gesture) = &status.last_gesture
            {
                self.log.push(format!("gesture {gesture}"));
            }
        }
        self.session.set_motion_samples(self.controller.pointer_on());
        // Every 10 s while the air mouse runs: how late the band's motion arrives (a congested
        // radio shows here first: kinesis saw samples arrive seconds late).
        if self.controller.pointer_on() && now - self.delay_logged >= 10.0 {
            self.delay_logged = now;
            let (late, samples) = self.controller.take_delay_report();
            self.log.push(format!("air mouse motion: {samples} samples, up to {:.0} ms late", late * 1000.0));
        }
        Ok(outgoing)
    }

    /// Call every ~50 ms (held single taps fire on time); returns bytes to write.
    pub fn tick(&mut self, now: f64) -> Result<Vec<u8>, String> {
        let mut outgoing = Vec::new();
        self.controller.set_writing(self.session.handwriting_active());
        for event in self.session.tick(now) {
            outgoing.extend(self.on_event(&event, now)?);
        }
        if self.controller.held_tap_due().is_some_and(|due| now >= due) {
            for command in self.controller.fire_held_tap(now) {
                outgoing.extend(self.apply(command, now)?);
            }
        }
        outgoing.extend(self.pointer_waits(now)?);
        if self.session.streams_enabled() && now >= self.next_battery {
            self.next_battery = now + BATTERY_EVERY;
            outgoing.extend(
                self.session
                    .query_battery_status(now)
                    .map_err(|e| e.to_string())?,
            );
        }
        if self.session.handwriting_active() {
            let (bytes, events) = self
                .session
                .flush_handwriting(now)
                .map_err(|e| e.to_string())?;
            outgoing.extend(bytes);
            for event in &events {
                outgoing.extend(self.on_event(event, now)?);
            }
        }
        let wanted = (self.gestures, self.motion);
        if self.session.streams_enabled() && wanted != self.streams_sent {
            self.streams_sent = wanted;
            let on_off = |on: bool| if on { "on" } else { "off" };
            self.log.push(format!(
                "gestures {}, motion streams {}",
                on_off(self.gestures),
                on_off(self.motion)
            ));
            outgoing.extend(
                self.session
                    .set_streams(self.gestures, self.motion)
                    .map_err(|e| e.to_string())?,
            );
        }
        let quiet = if self.motion { QUIET_QUERY } else { QUIET_QUERY_NO_MOTION };
        if self.session.streams_enabled()
            && now - self.last_read >= quiet
            && now - self.last_query >= quiet
        {
            self.last_query = now;
            outgoing.extend(
                self.session
                    .query_stream_state()
                    .map_err(|e| e.to_string())?,
            );
        }
        Ok(outgoing)
    }

    /// Switch the band's handwriting model on or off; `hints` are the settings' ids the last
    /// capture found (`{"collection_id","model_id"}` of its state events). Requests go out with
    /// the next ticks; the capture's progress and text come out of `take_handwriting_events`.
    pub fn set_handwriting(
        &mut self,
        enabled: bool,
        hints: Option<(u64, u64)>,
        now: f64,
    ) -> Result<Vec<u8>, String> {
        self.session
            .set_handwriting(enabled, hints, now)
            .map_err(|e| e.to_string())?;
        self.controller.set_writing(self.session.handwriting_active());
        self.flush_handwriting(now)
    }

    /// Put the band's handwriting settings back to normal (a capture that never finished).
    pub fn recover_handwriting(&mut self, hints: Option<(u64, u64)>, now: f64) -> Result<Vec<u8>, String> {
        self.session
            .recover_handwriting(hints, now)
            .map_err(|e| e.to_string())?;
        self.flush_handwriting(now)
    }

    /// Start the written text over from `text`, what the field holds.
    pub fn reset_handwriting_text(&mut self, text: &str) {
        self.session.reset_handwriting_text(text);
    }

    fn flush_handwriting(&mut self, now: f64) -> Result<Vec<u8>, String> {
        let (mut outgoing, events) = self
            .session
            .flush_handwriting(now)
            .map_err(|e| e.to_string())?;
        for event in &events {
            outgoing.extend(self.on_event(event, now)?);
        }
        Ok(outgoing)
    }

    /// The handwriting capture's events since the last call, one JSON object each:
    /// `{"type":"state","phase","message","problem","verified","mutated","collection_id","model_id"}`
    /// and `{"type":"text","text","raw","class"}`.
    pub fn take_handwriting_events(&mut self) -> Vec<String> {
        std::mem::take(&mut self.handwriting)
    }

    /// The ceremony's events since the last call, one JSON object each (claim mode).
    pub fn take_claim_events(&mut self) -> Vec<String> {
        std::mem::take(&mut self.claim)
    }

    /// Resume the claim after `pair_request`: the server's signature and pending receipt.
    pub fn claim_pair_request_completed(&mut self, signature: &[u8], receipt: &str) -> Result<Vec<u8>, String> {
        self.session
            .ceremony_pair_request_completed(signature, receipt)
            .map_err(|e| e.to_string())
    }

    /// Resume the claim after `pair`. Returns the bytes to write; `claim_pending_key` then holds
    /// the owner key the band is about to commit: persist it as pending BEFORE writing (the band
    /// may commit even if its confirmation never arrives).
    pub fn claim_pair_completed(
        &mut self,
        signature: &[u8],
        receipt: &str,
        device_public_key: Option<&[u8]>,
    ) -> Result<Vec<u8>, String> {
        let bytes = self
            .session
            .ceremony_pair_completed(signature, receipt, device_public_key)
            .map_err(|e| e.to_string())?;
        self.claim_pending = Some(
            self.session
                .pending_identity()
                .map(|identity| owner_key(&identity))
                .ok_or("the claim has no identity to commit")?,
        );
        Ok(bytes)
    }

    /// The owner key `claim_pair_completed` prepared; persist it before writing its bytes.
    pub fn claim_pending_key(&self) -> Option<Vec<u8>> {
        self.claim_pending.clone()
    }

    /// Action names to run since the last call ("media.next", "volume.up", …).
    pub fn take_actions(&mut self) -> Vec<String> {
        std::mem::take(&mut self.actions)
    }

    /// Log lines since the last call ("connected", "gesture swipe left", …).
    pub fn take_log(&mut self) -> Vec<String> {
        std::mem::take(&mut self.log)
    }

    /// Motion streams (gyro, orientation) on or off, sent with the next tick. Off saves the
    /// band's power while nothing needs pinch and turn (the glasses' screen is off).
    pub fn set_motion(&mut self, enabled: bool) {
        self.motion = enabled;
    }

    /// The gesture stream on or off, sent with the next tick. Off with the motion streams off is
    /// the band's power saving: it sends nothing and doesn't vibrate, so no gesture can wake the
    /// glasses (their button does).
    pub fn set_gestures(&mut self, enabled: bool) {
        self.gestures = enabled;
    }

    pub fn set_paused(&mut self, paused: bool, now: f64) {
        let pointer = self.controller.pointer_on();
        if paused {
            self.controller.pause();
        } else {
            self.controller.resume(now);
        }
        if pointer && !self.controller.pointer_on() {
            self.actions.push(POINTER_OFF.into());
            self.session.set_motion_samples(false);
        }
    }

    /// The air mouse on or off, with its `tuning` (`steadiness=0.5;boost=1.0`); see
    /// `Controller::set_pointer`. Its movement and buttons come out of `take_pointer`.
    pub fn set_pointer(&mut self, enabled: bool, tuning: &str, now: f64) {
        let was = self.controller.pointer_on();
        for command in self.controller.set_pointer(enabled, PointerTuning::parse(tuning), now) {
            if let Command::Run(action) = command {
                self.actions.push(action);
            }
        }
        let on = self.controller.pointer_on();
        self.session.set_motion_samples(on);
        if on != was {
            self.log.push(format!("air mouse {}", if on { "on" } else { "off" }));
        }
    }

    /// The air mouse's records since the last call (see `controller::RECORD_MOVE`).
    pub fn take_pointer(&mut self, now: f64) -> Vec<f64> {
        self.controller.take_pointer(now)
    }

    /// Replace the gesture and dial mapping (see `Config::apply_mapping`).
    pub fn set_mapping(&mut self, mapping: &str) {
        let paused = self.controller.status().paused;
        for command in self.controller.set_config(config_with(mapping)) {
            if let Command::Run(action) = command {
                self.actions.push(action);
            }
        }
        // A new config resets the controller's pause state; keep the app's.
        if paused {
            self.controller.pause();
        }
        self.session.set_motion_samples(self.controller.pointer_on());
    }

    pub fn set_dial(&mut self, dial: &str) {
        self.controller.set_dial_target(DialTarget::from_name(dial));
    }

    /// `{"connected":…, "paused":…, "battery":…, "charging":…, "hand":…, "dial":…,
    /// "last_gesture":…, "last_action":…, "gestures":…}`.
    pub fn status_json(&self) -> String {
        serde_json::to_string(&Snapshot {
            connected: self.live,
            status: self.controller.status(),
        })
        .expect("status serializes")
    }

    fn on_event(&mut self, event: &Event, now: f64) -> Result<Vec<u8>, String> {
        match event {
            Event::CeremonyStage(stage) => {
                self.log.push(format!("claim: {stage}"));
                self.claim.push(serde_json::json!({"type": "stage", "text": stage}).to_string());
            }
            Event::CeremonyHttp(CeremonyHttpRequest::PairRequest(request)) => {
                self.claim.push(
                    serde_json::json!({
                        "type": "pair_request",
                        "device_cert": hex(&request.identity.device_certificate),
                        "serial": request.identity.serial,
                        "secondary_cert": hex(&request.identity.secondary_certificate),
                        "nonce": hex(&request.nonce),
                        "app_pubkey": hex(&request.app_public_key),
                    })
                    .to_string(),
                );
            }
            Event::CeremonyHttp(CeremonyHttpRequest::Pair(pair)) => {
                self.claim.push(
                    serde_json::json!({
                        "type": "pair",
                        "receipt": pair.receipt,
                        "signature": hex(&pair.signature),
                    })
                    .to_string(),
                );
            }
            Event::CeremonyCompleted(identity) => {
                self.claim.push(
                    serde_json::json!({"type": "completed", "owner_key": hex(&owner_key(identity))})
                        .to_string(),
                );
            }
            Event::Connected => {
                self.live = true;
                self.log.push("connected".into());
            }
            Event::Handedness(hand) => self.log.push(format!("hand {hand:?}").to_lowercase()),
            // While the air mouse runs, each pinch report (no sensor data), to see what the band sends.
            Event::Gesture(message) if self.controller.pointer_on() && message.finger != "thumb" => self.log.push(format!(
                "air mouse pinch {} {}/{}{}",
                message.finger,
                message.action,
                message.derived_action,
                if message.synthetic { " synthetic" } else { "" }
            )),
            Event::HandwritingState(status) => {
                self.log.push(format!(
                    "handwriting {}: {}{}",
                    status.phase.name(),
                    status.message,
                    status.problem.as_deref().map(|p| format!(" ({p})")).unwrap_or_default()
                ));
                let (collection, model) = status.ids.unzip();
                self.handwriting.push(
                    serde_json::json!({
                        "type": "state",
                        "phase": status.phase.name(),
                        "message": status.message,
                        "problem": status.problem,
                        "verified": status.verified,
                        "mutated": status.mutated,
                        "collection_id": collection,
                        "model_id": model,
                    })
                    .to_string(),
                );
            }
            Event::HandwritingText { text, raw, class } => {
                // The text itself never goes to the log.
                self.handwriting.push(
                    serde_json::json!({"type": "text", "text": text, "raw": raw, "class": class})
                        .to_string(),
                );
            }
            _ => {}
        }
        let gestures = self.controller.status().gestures;
        let mut outgoing = Vec::new();
        for command in self.controller.on_event(event, now) {
            outgoing.extend(self.apply(command, now)?);
        }
        let status = self.controller.status();
        if status.gestures != gestures
            && let Some(gesture) = &status.last_gesture
        {
            self.log.push(format!("gesture {gesture}"));
        }
        Ok(outgoing)
    }

    fn apply(&mut self, command: Command, now: f64) -> Result<Vec<u8>, String> {
        match command {
            Command::Run(action) if action == DIAL_TOGGLE => {
                self.controller.cycle_dial();
                self.log
                    .push(format!("dial {}", self.controller.status().dial.name()));
                self.actions.push(format!(
                    "dial.changed.{}",
                    self.controller.status().dial.name()
                ));
            }
            Command::Run(action) => {
                self.log.push(format!("action {action}"));
                self.actions.push(action);
            }
            Command::SetHand(hand) => {
                return self
                    .session
                    .set_handedness(hand, now)
                    .map_err(|e| e.to_string());
            }
        }
        Ok(Vec::new())
    }
}

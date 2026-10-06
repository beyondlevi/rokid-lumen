//! Copied from air-gestures' `air-gesturesd/src/controller.rs` (91e7217); here
//! the commands are app action names instead of shell commands.
//!
//! What to do with each band event — a port of the event handling in kinesis
//! `BandModel.swift` (~L840–990). Pure: no I/O; time is passed in.

use band_core::events::{Event, Hand};
use band_core::gestures::{ActionGate, DialRouter, GestureRouter, Recognized, Swipe, Tap};

use crate::config::{Config, DialTarget, HandSetting, ToggleGesture};
use crate::status::Status;

#[derive(Debug, Clone, PartialEq)]
pub enum Command {
    /// Run this shell command.
    Run(String),
    /// Ask the band to switch hands.
    SetHand(Hand),
}

fn label(gesture: Recognized) -> &'static str {
    match gesture {
        Recognized::Swipe(Swipe::Left) => "swipe left",
        Recognized::Swipe(Swipe::Right) => "swipe right",
        Recognized::Swipe(Swipe::Up) => "swipe up",
        Recognized::Swipe(Swipe::Down) => "swipe down",
        Recognized::Tap(Tap::IndexTap) => "index tap",
        Recognized::Tap(Tap::IndexDoubleTap) => "index double tap",
        Recognized::Tap(Tap::MiddleTap) => "middle tap",
        Recognized::Tap(Tap::MiddleDoubleTap) => "middle double tap",
        Recognized::Tap(Tap::MiddleHold) => "middle hold",
    }
}

/// How long a single tap waits for its double tap. On the band the second tap
/// follows 0.25–0.33 s after the first, and the double tap arrives with it.
pub const DOUBLE_TAP_WAIT: f64 = 0.4;

/// A single tap held back while its finger's double tap may still come.
#[derive(Debug, Clone, Copy)]
struct HeldTap {
    tap: Tap,
    fire_at: f64,
    received_at: f64,
}

/// The most time between the taps of a triple tap (the band's own double tap
/// needs them under ~0.33 s apart; a triple tap is a little slower).
pub const TRIPLE_TAP_GAP: f64 = 0.45;

/// The triple-tap toggle this tap could be part of.
fn triple_of(tap: Tap) -> ToggleGesture {
    match tap {
        Tap::IndexTap | Tap::IndexDoubleTap => ToggleGesture::IndexTriple,
        Tap::MiddleTap | Tap::MiddleDoubleTap => ToggleGesture::MiddleTriple,
        Tap::MiddleHold => ToggleGesture::None,
    }
}

fn double_of(tap: Tap) -> Option<Tap> {
    match tap {
        Tap::IndexTap => Some(Tap::IndexDoubleTap),
        Tap::MiddleTap => Some(Tap::MiddleDoubleTap),
        _ => None,
    }
}

fn hand_name(hand: Hand) -> &'static str {
    match hand {
        Hand::Left => "left",
        Hand::Right => "right",
    }
}

pub struct Controller {
    config: Config,
    dial_target: DialTarget,
    router: GestureRouter,
    gate: ActionGate,
    dial_gate: ActionGate,
    dial_router: DialRouter,
    /// Controls are on (the user hasn't paused).
    enabled: bool,
    /// The band's input subscription is up.
    live: bool,
    hand: Option<Hand>,
    hand_confirmed: bool,
    hand_requested: bool,
    /// Whether the one automatic retry (see `on_event`'s `Handedness` arm) has
    /// already been used for the current request.
    hand_retry_used: bool,
    /// A runtime hand request (e.g. from the control socket) that wins over
    /// the configured hand until the config reloads.
    hand_override: Option<Hand>,
    dial_armed: bool,
    dial_engaged: bool,
    dial_turned: bool,
    dial_ended_at: f64,
    last_dial_action: f64,
    held_tap: Option<HeldTap>,
    /// Recent single taps of the toggle finger, for the triple tap.
    toggle_taps: Vec<f64>,
    /// When a triple tap last toggled (its third tap may also report a double).
    toggled_at: f64,
    /// The band's handwriting runs: only the middle tap counts (see [Controller::set_writing]).
    writing: bool,
    status: Status,
}

impl Controller {
    pub fn new(config: Config) -> Self {
        let enabled = config.band.autostart;
        let status = Status {
            paused: !enabled,
            dial: config.dial.target,
            ..Status::default()
        };
        Self {
            dial_target: config.dial.target,
            config,
            router: GestureRouter::default(),
            gate: ActionGate::default(),
            dial_gate: ActionGate::new(0.0),
            dial_router: DialRouter::default(),
            enabled,
            live: false,
            hand: None,
            hand_confirmed: false,
            hand_requested: false,
            hand_retry_used: false,
            hand_override: None,
            dial_armed: false,
            dial_engaged: false,
            dial_turned: false,
            dial_ended_at: f64::NEG_INFINITY,
            last_dial_action: f64::NEG_INFINITY,
            held_tap: None,
            toggle_taps: Vec::new(),
            toggled_at: f64::NEG_INFINITY,
            writing: false,
            status,
        }
    }

    /// While the band's handwriting runs (and while it's being switched off: the hand is often
    /// still moving), a writing stroke can look like any gesture (the middle hold that pauses
    /// included): only the middle tap counts, at once and even while paused, so it can always
    /// end the writing. The dial is off.
    pub fn set_writing(&mut self, writing: bool) {
        if writing != self.writing {
            self.writing = writing;
            self.held_tap = None;
            self.toggle_taps.clear();
        }
    }

    /// Gesture, dial and control fields of the status (the daemon adds the rest).
    pub fn status(&self) -> &Status {
        &self.status
    }

    /// Apply a reloaded config. A changed dial target in the file wins over a
    /// runtime switch; a changed hand setting is applied if the band is live.
    pub fn set_config(&mut self, config: Config) -> Vec<Command> {
        if config.dial.target != self.config.dial.target {
            self.dial_target = config.dial.target;
            self.status.dial = config.dial.target;
        }
        self.config = config;
        self.hand_override = None;
        self.hand_requested = false;
        self.hand_retry_used = false;
        self.reset_dial();
        self.maybe_request_hand()
    }

    /// Ask the band to switch hands right now (e.g. from the control socket).
    /// This overrides the configured hand until the config reloads.
    pub fn request_hand(&mut self, hand: Hand) -> Vec<Command> {
        self.hand_override = Some(hand);
        self.hand_requested = false;
        self.hand_retry_used = false;
        self.maybe_request_hand()
    }

    pub fn resume(&mut self, now: f64) {
        self.enabled = true;
        self.status.paused = false;
        if self.live {
            self.gate.arm(now);
        }
        self.reset_dial();
    }

    pub fn pause(&mut self) {
        self.enabled = false;
        self.status.paused = true;
        self.suspend();
    }

    pub fn toggle(&mut self, now: f64) {
        if self.enabled {
            self.pause()
        } else {
            self.resume(now)
        }
    }

    pub fn set_dial_target(&mut self, target: DialTarget) {
        self.dial_target = target;
        self.status.dial = target;
        self.reset_dial();
    }

    pub fn cycle_dial(&mut self) {
        self.set_dial_target(self.dial_target.next());
    }

    pub fn on_disconnect(&mut self) {
        self.live = false;
        self.hand = None;
        self.hand_confirmed = false;
        self.hand_requested = false;
        self.hand_retry_used = false;
        self.suspend();
        self.held_tap = None;
        self.toggle_taps.clear();
        self.router.reset();
        self.status.charging = None;
    }

    pub fn on_event(&mut self, event: &Event, now: f64) -> Vec<Command> {
        match event {
            Event::Connected => {
                self.live = true;
                if self.enabled {
                    self.gate.arm(now);
                }
                self.maybe_request_hand()
            }
            Event::Handedness(hand) => {
                self.hand = Some(*hand);
                self.hand_confirmed = true;
                self.status.hand = Some(hand_name(*hand).into());
                // The band still disagrees with an outstanding request: the
                // write may have been lost or refused. Retry once.
                if self.hand_requested
                    && !self.hand_retry_used
                    && self.desired_hand().is_some_and(|wanted| wanted != *hand)
                {
                    self.hand_retry_used = true;
                    self.hand_requested = false;
                }
                self.maybe_request_hand()
            }
            Event::HandednessFailure(_) => {
                self.hand_confirmed = false;
                Vec::new()
            }
            Event::BatteryStatus(Some(status)) => {
                self.status.battery = Some(status.level);
                self.status.charging = status.charging;
                Vec::new()
            }
            Event::BatteryStatus(None) => {
                self.status.charging = None;
                Vec::new()
            }
            Event::Gesture(message) => self.gesture(message, now),
            Event::DialState(engaged) => {
                self.dial_state(*engaged, now);
                Vec::new()
            }
            Event::DialTurn(_) if self.writing => Vec::new(),
            Event::DialTurn(rotation) => self.dial_turn(*rotation, now),
            _ => Vec::new(),
        }
    }

    fn gesture(&mut self, message: &band_core::events::GestureMessage, now: f64) -> Vec<Command> {
        let age = now - message.received_at;
        if !(-0.1..=0.35).contains(&age) {
            return Vec::new();
        }
        let Some(gesture) = self.router.gesture(message, now) else {
            return Vec::new();
        };
        if self.writing {
            if gesture != Recognized::Tap(Tap::MiddleTap) {
                return Vec::new();
            }
            self.status.gestures += 1;
            self.status.last_gesture = Some(label(gesture).into());
            let command = self.config.gestures.command(gesture).to_owned();
            if command.is_empty() || !self.gate.allows(message.received_at, now, self.live, true) {
                return Vec::new();
            }
            self.status.last_action = Some(command.clone());
            return vec![Command::Run(command)];
        }
        // Releasing a wrist turn must not also trigger an index-finger assignment.
        if let Recognized::Tap(tap) = gesture
            && tap.finger() == "index"
            && (now - self.last_dial_action <= 0.6 || now - self.dial_ended_at <= 0.7)
        {
            return Vec::new();
        }
        if gesture == Recognized::Tap(Tap::MiddleHold)
            && self.config.band.toggle == ToggleGesture::MiddleHold
        {
            // One toggle per hold, however often the band repeats it.
            if now - self.toggled_at < 1.0 {
                return Vec::new();
            }
            self.toggled_at = now;
            return self.toggle_by_tap(now);
        }
        if let Recognized::Tap(tap) = gesture
            && self.config.band.toggle != ToggleGesture::None
            && triple_of(tap) == self.config.band.toggle
        {
            if double_of(tap).is_some() {
                if self
                    .toggle_taps
                    .last()
                    .is_some_and(|&last| now - last > TRIPLE_TAP_GAP)
                {
                    self.toggle_taps.clear();
                }
                self.toggle_taps.push(now);
                if self.toggle_taps.len() >= 3 {
                    self.toggle_taps.clear();
                    self.held_tap = None;
                    self.toggled_at = now;
                    return self.toggle_by_tap(now);
                }
            } else if now - self.toggled_at < 0.15 {
                // The third tap's own double tap: part of the triple.
                return Vec::new();
            } else {
                // This finger's double tap may be the start of a triple tap.
                self.held_tap = Some(HeldTap {
                    tap,
                    fire_at: now + TRIPLE_TAP_GAP,
                    received_at: message.received_at,
                });
                return Vec::new();
            }
        }
        if let Recognized::Tap(tap) = gesture {
            // A double tap supersedes the single tap(s) it was made of.
            if self
                .held_tap
                .is_some_and(|held| double_of(held.tap) == Some(tap))
            {
                self.held_tap = None;
            }
            // A single tap whose double has an action waits to see whether it
            // is the first half of that double tap.
            if let Some(double) = double_of(tap)
                && !self
                    .config
                    .gestures
                    .command(Recognized::Tap(tap))
                    .is_empty()
                && !self
                    .config
                    .gestures
                    .command(Recognized::Tap(double))
                    .is_empty()
            {
                match &mut self.held_tap {
                    // The double's second tap: its double tap comes right with it.
                    Some(held) if held.tap == tap => held.fire_at = held.fire_at.max(now + 0.08),
                    _ => {
                        self.held_tap = Some(HeldTap {
                            tap,
                            fire_at: now + DOUBLE_TAP_WAIT,
                            received_at: message.received_at,
                        })
                    }
                }
                return Vec::new();
            }
        }
        self.status.gestures += 1;
        self.status.last_gesture = Some(label(gesture).into());
        let command = self.config.gestures.command(gesture).to_owned();
        if !self.enabled
            || command.is_empty()
            || !self.gate.allows(message.received_at, now, self.live, true)
        {
            return Vec::new();
        }
        self.status.last_action = Some(command.clone());
        vec![Command::Run(command)]
    }

    fn toggle_by_tap(&mut self, now: f64) -> Vec<Command> {
        if !self.live {
            return Vec::new();
        }
        self.toggle(now);
        self.status.last_gesture = Some("triple tap".into());
        let command = &self.config.band.on_toggle;
        if command.is_empty() {
            return Vec::new();
        }
        vec![Command::Run(command.clone())]
    }

    /// When a held single tap is due (see `fire_held_tap`).
    pub fn held_tap_due(&self) -> Option<f64> {
        self.held_tap.map(|held| held.fire_at)
    }

    /// Run a held single tap whose double tap never came.
    pub fn fire_held_tap(&mut self, now: f64) -> Vec<Command> {
        let Some(held) = self.held_tap.filter(|held| now >= held.fire_at) else {
            return Vec::new();
        };
        self.held_tap = None;
        let gesture = Recognized::Tap(held.tap);
        self.status.gestures += 1;
        self.status.last_gesture = Some(label(gesture).into());
        let command = self.config.gestures.command(gesture).to_owned();
        if !self.enabled
            || command.is_empty()
            || !self
                .gate
                .allows_held(held.received_at, true, now, self.live)
        {
            return Vec::new();
        }
        self.status.last_action = Some(command.clone());
        vec![Command::Run(command)]
    }

    fn dial_state(&mut self, engaged: bool, now: f64) {
        if !self.live {
            self.reset_dial();
            return;
        }
        // A pinch that turned has just ended: its release is not a tap.
        if !engaged && self.dial_turned {
            self.dial_ended_at = now;
        }
        self.dial_turned = false;
        self.dial_engaged = engaged;
        self.reset_dial();
        if engaged && self.enabled {
            self.dial_armed = true;
            self.dial_gate.arm(now);
        }
    }

    fn dial_turn(&mut self, rotation: f64, now: f64) -> Vec<Command> {
        if !self.live || !self.hand_confirmed || !self.dial_engaged || !rotation.is_finite() {
            return Vec::new();
        }
        // The same intended turn produces the opposite gyro sign on the left wrist.
        let delta = if self.hand == Some(Hand::Left) {
            -rotation
        } else {
            rotation
        };
        self.dial_turned = true;
        if !self.enabled
            || !self.dial_armed
            || self.config.dial.command(self.dial_target, true).is_none()
        {
            return Vec::new();
        }
        if !self.dial_gate.allows(now, now, self.live, true) {
            return Vec::new();
        }
        let steps = self
            .dial_router
            .turn(delta, self.config.dial.sensitivity, now);
        if steps == 0 {
            return Vec::new();
        }
        self.last_dial_action = now;
        match self.config.dial.command(self.dial_target, steps > 0) {
            Some(command) => {
                self.status.last_gesture = Some(
                    if steps > 0 {
                        "wrist turn +"
                    } else {
                        "wrist turn \u{2212}"
                    }
                    .into(),
                );
                self.status.last_action = Some(command.to_owned());
                vec![Command::Run(command.to_owned())]
            }
            None => Vec::new(),
        }
    }

    fn desired_hand(&self) -> Option<Hand> {
        if let Some(hand) = self.hand_override {
            return Some(hand);
        }
        match self.config.band.hand {
            HandSetting::Band => None,
            HandSetting::Left => Some(Hand::Left),
            HandSetting::Right => Some(Hand::Right),
        }
    }

    fn maybe_request_hand(&mut self) -> Vec<Command> {
        match (self.desired_hand(), self.hand) {
            (Some(wanted), Some(current))
                if self.live
                    && self.hand_confirmed
                    && wanted != current
                    && !self.hand_requested =>
            {
                self.hand_requested = true;
                vec![Command::SetHand(wanted)]
            }
            _ => Vec::new(),
        }
    }

    fn reset_dial(&mut self) {
        self.dial_armed = false;
        self.dial_gate.pause();
        self.dial_router.reset();
    }

    fn suspend(&mut self) {
        self.gate.pause();
        self.reset_dial();
        self.dial_engaged = false;
    }
}

//! What each gesture does on the phone: an action name the app runs (see
//! `Actions.kt`; `""` = unassigned). Adapted from air-gestures' desktop
//! config, where the values are shell commands; the defaults mirror its layout.

use band_core::gestures::{Recognized, Swipe, Tap};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum HandSetting {
    /// Use whatever hand the band reports.
    #[default]
    Band,
    Left,
    Right,
}

/// The gesture that turns the controls off and on (it works while they are off).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum ToggleGesture {
    None,
    /// The band's own middle-finger hold (long press).
    #[default]
    MiddleHold,
    /// Three taps, each within 0.45 s of the last; that finger's double tap
    /// then waits 0.45 s for a third. Fast tapping only.
    IndexTriple,
    MiddleTriple,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum DialTarget {
    #[default]
    Volume,
    Brightness,
    Off,
}

impl DialTarget {
    pub fn next(self) -> Self {
        match self {
            DialTarget::Volume => DialTarget::Brightness,
            DialTarget::Brightness | DialTarget::Off => DialTarget::Volume,
        }
    }

    pub fn name(self) -> &'static str {
        match self {
            DialTarget::Volume => "volume",
            DialTarget::Brightness => "brightness",
            DialTarget::Off => "off",
        }
    }

    pub fn from_name(name: &str) -> Self {
        match name {
            "brightness" => DialTarget::Brightness,
            "off" => DialTarget::Off,
            _ => DialTarget::Volume,
        }
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct BandConfig {
    pub hand: HandSetting,
    /// Controls are on when a connection starts.
    pub autostart: bool,
    /// The gesture that turns the controls off and on.
    pub toggle: ToggleGesture,
    /// Run after that gesture toggled the controls (`""` = nothing).
    pub on_toggle: String,
}

impl Default for BandConfig {
    fn default() -> Self {
        Self {
            hand: HandSetting::Band,
            autostart: true,
            toggle: ToggleGesture::MiddleHold,
            on_toggle: String::new(),
        }
    }
}

/// The action that swaps the dial between volume and brightness. The bridge
/// handles it itself instead of passing it to the app.
pub const DIAL_TOGGLE: &str = "dial.toggle";

#[derive(Debug, Clone, PartialEq)]
pub struct GestureConfig {
    pub swipe_left: String,
    pub swipe_right: String,
    pub swipe_up: String,
    pub swipe_down: String,
    pub index_tap: String,
    pub index_double: String,
    pub middle_tap: String,
    pub middle_double: String,
    pub middle_hold: String,
}

impl Default for GestureConfig {
    fn default() -> Self {
        Self {
            swipe_left: String::new(),
            swipe_right: String::new(),
            swipe_up: "media.previous".into(),
            swipe_down: "media.next".into(),
            index_tap: "media.play_pause".into(),
            index_double: DIAL_TOGGLE.into(),
            middle_tap: String::new(),
            middle_double: "volume.mute".into(),
            middle_hold: String::new(),
        }
    }
}

impl GestureConfig {
    /// Apply `gesture=action` pairs separated by `;` (e.g. `swipe_up=media.next`).
    /// The middle hold stays the controls toggle; unknown gestures are ignored.
    pub fn apply_mapping(&mut self, mapping: &str) {
        for pair in mapping.split(';') {
            let Some((gesture, action)) = pair.split_once('=') else {
                continue;
            };
            let slot = match gesture.trim() {
                "swipe_left" => &mut self.swipe_left,
                "swipe_right" => &mut self.swipe_right,
                "swipe_up" => &mut self.swipe_up,
                "swipe_down" => &mut self.swipe_down,
                "index_tap" => &mut self.index_tap,
                "index_double" => &mut self.index_double,
                "middle_tap" => &mut self.middle_tap,
                "middle_double" => &mut self.middle_double,
                _ => continue,
            };
            *slot = action.trim().to_owned();
        }
    }

    /// The configured action, or `""` when it's unassigned (including whitespace-only).
    pub fn command(&self, gesture: Recognized) -> &str {
        let command = match gesture {
            Recognized::Swipe(Swipe::Left) => &self.swipe_left,
            Recognized::Swipe(Swipe::Right) => &self.swipe_right,
            Recognized::Swipe(Swipe::Up) => &self.swipe_up,
            Recognized::Swipe(Swipe::Down) => &self.swipe_down,
            Recognized::Tap(Tap::IndexTap) => &self.index_tap,
            Recognized::Tap(Tap::IndexDoubleTap) => &self.index_double,
            Recognized::Tap(Tap::MiddleTap) => &self.middle_tap,
            Recognized::Tap(Tap::MiddleDoubleTap) => &self.middle_double,
            Recognized::Tap(Tap::MiddleHold) => &self.middle_hold,
        };
        if command.trim().is_empty() {
            ""
        } else {
            command
        }
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct DialConfig {
    pub target: DialTarget,
    /// 1.0 = one step per 2° of relative wrist rotation; 0.5–4.
    pub sensitivity: f64,
    pub volume_up: String,
    pub volume_down: String,
    pub brightness_up: String,
    pub brightness_down: String,
}

impl Default for DialConfig {
    fn default() -> Self {
        Self {
            target: DialTarget::Volume,
            sensitivity: 1.0,
            volume_up: "volume.up".into(),
            volume_down: "volume.down".into(),
            brightness_up: "brightness.up".into(),
            brightness_down: "brightness.down".into(),
        }
    }
}

impl DialConfig {
    /// The action for one step, or `None` when the target is off or the
    /// action is unassigned (including whitespace-only).
    pub fn command(&self, target: DialTarget, up: bool) -> Option<&str> {
        let command = match (target, up) {
            (DialTarget::Off, _) => return None,
            (DialTarget::Volume, true) => &self.volume_up,
            (DialTarget::Volume, false) => &self.volume_down,
            (DialTarget::Brightness, true) => &self.brightness_up,
            (DialTarget::Brightness, false) => &self.brightness_down,
        };
        (!command.trim().is_empty()).then_some(command.as_str())
    }
}

#[derive(Debug, Clone, PartialEq, Default)]
pub struct Config {
    pub band: BandConfig,
    pub gestures: GestureConfig,
    pub dial: DialConfig,
}

impl Config {
    /// Apply `key=value` pairs separated by `;`: the gesture keys of
    /// [`GestureConfig::apply_mapping`], `dial_up`/`dial_down` for one
    /// pinch-and-turn step each way (both empty: the dial does nothing), and
    /// `hand=left|right|band` (which wrist the band should be set to; `band`
    /// accepts whatever it reports).
    pub fn apply_mapping(&mut self, mapping: &str) {
        self.gestures.apply_mapping(mapping);
        for pair in mapping.split(';') {
            if let Some(("hand", hand)) = pair.split_once('=') {
                self.band.hand = match hand.trim() {
                    "left" => HandSetting::Left,
                    "right" => HandSetting::Right,
                    _ => HandSetting::Band,
                };
            }
        }
        let mut up = None;
        let mut down = None;
        for pair in mapping.split(';') {
            match pair.split_once('=') {
                Some(("dial_up", action)) => up = Some(action.trim().to_owned()),
                Some(("dial_down", action)) => down = Some(action.trim().to_owned()),
                _ => {}
            }
        }
        if up.is_none() && down.is_none() {
            return;
        }
        let (up, down) = (up.unwrap_or_default(), down.unwrap_or_default());
        self.dial.target = if up.is_empty() && down.is_empty() {
            DialTarget::Off
        } else {
            DialTarget::Volume
        };
        // The dial's "volume" slot carries whatever the app mapped it to.
        self.dial.volume_up = up;
        self.dial.volume_down = down;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_dial_can_be_remapped_or_turned_off() {
        let mut config = Config::default();
        config.apply_mapping("dial_up=media.next;dial_down=media.previous");
        assert_eq!(
            config.dial.command(config.dial.target, true),
            Some("media.next")
        );
        assert_eq!(
            config.dial.command(config.dial.target, false),
            Some("media.previous")
        );
        config.apply_mapping("dial_up=;dial_down=");
        assert_eq!(config.dial.target, DialTarget::Off);
        assert_eq!(config.dial.command(config.dial.target, true), None);
    }

    #[test]
    fn the_wrist_comes_from_the_mapping() {
        let mut config = Config::default();
        config.apply_mapping("hand=left");
        assert_eq!(config.band.hand, HandSetting::Left);
        config.apply_mapping("hand=band");
        assert_eq!(config.band.hand, HandSetting::Band);
    }

    #[test]
    fn a_mapping_without_dial_keys_keeps_the_volume_dial() {
        let mut config = Config::default();
        config.apply_mapping("swipe_up=media.next");
        assert_eq!(
            config.dial.command(config.dial.target, true),
            Some("volume.up")
        );
    }
}

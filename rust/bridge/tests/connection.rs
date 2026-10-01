use lumen_band::Connection;
use band_core::identity::EnrollmentIdentity;
use band_core::responder::{Responder, SimMode};
use p256::ecdsa::SigningKey;
use rand_core::OsRng;

/// Band gesture codes: thumb=1 index=2 middle=3; actions tap=3 doubletap=4
/// up=6 down=7 left=8 right=9; derived buttonHold=3.
const THUMB: u64 = 1;
const INDEX: u64 = 2;
const MIDDLE: u64 = 3;

/// Ping-pong until neither side has anything to say.
fn pump(connection: &mut Connection, band: &mut Responder, mut to_band: Vec<u8>, now: f64) {
    for _ in 0..64 {
        if to_band.is_empty() {
            return;
        }
        let reply = band.respond(&to_band).unwrap();
        to_band = connection.feed(&reply, now).unwrap();
    }
}

fn connected(paused: bool) -> (Connection, Responder) {
    connected_with(paused, "")
}

fn connected_with(paused: bool, mapping: &str) -> (Connection, Responder) {
    let identity = EnrollmentIdentity::new(SigningKey::random(&mut OsRng), None);
    let mut connection =
        Connection::new(&identity.private_bytes(), 0, paused, "volume", mapping).unwrap();
    // An enrolled band with no owner recorded adopts the first key that proves trust.
    let mut band = Responder::new(SimMode::Enrolled, SigningKey::random(&mut OsRng));
    let request = connection.request().unwrap();
    pump(&mut connection, &mut band, request, 1.0);
    assert!(connection.take_log().contains(&"connected".to_owned()));
    assert!(connection.status_json().contains(r#""connected":true"#));
    (connection, band)
}

fn gesture(
    connection: &mut Connection,
    band: &mut Responder,
    action: u64,
    finger: u64,
    derived: u64,
    now: f64,
) {
    let bytes = band.gesture(action, finger, derived).unwrap();
    let reply = connection.feed(&bytes, now).unwrap();
    pump(connection, band, reply, now);
}

#[test]
fn a_swipe_down_plays_the_next_track() {
    let (mut connection, mut band) = connected(false);
    gesture(&mut connection, &mut band, 7, THUMB, 0, 3.0);
    assert_eq!(connection.take_actions(), vec!["media.next".to_owned()]);
    assert!(
        connection
            .take_log()
            .contains(&"gesture swipe down".to_owned())
    );
}

#[test]
fn an_index_tap_plays_or_pauses_once_its_double_tap_window_passes() {
    let (mut connection, mut band) = connected(false);
    gesture(&mut connection, &mut band, 3, INDEX, 0, 3.0);
    assert!(
        connection.take_actions().is_empty(),
        "held for a possible double tap"
    );
    connection.tick(3.2).unwrap();
    assert!(connection.take_actions().is_empty());
    connection.tick(3.45).unwrap();
    assert_eq!(
        connection.take_actions(),
        vec!["media.play_pause".to_owned()]
    );
}

#[test]
fn an_index_double_tap_swaps_the_dial_to_brightness() {
    let (mut connection, mut band) = connected(false);
    gesture(&mut connection, &mut band, 4, INDEX, 0, 3.0);
    assert_eq!(
        connection.take_actions(),
        vec!["dial.changed.brightness".to_owned()]
    );
    assert!(connection.status_json().contains(r#""dial":"brightness""#));
}

#[test]
fn a_middle_hold_turns_the_controls_off_and_on() {
    let (mut connection, mut band) = connected(false);
    gesture(&mut connection, &mut band, 0, MIDDLE, 3, 3.0);
    assert!(connection.status_json().contains(r#""paused":true"#));
    // Off: gestures do nothing…
    gesture(&mut connection, &mut band, 7, THUMB, 0, 5.0);
    assert!(connection.take_actions().is_empty());
    // …except the hold, which turns them back on.
    gesture(&mut connection, &mut band, 0, MIDDLE, 3, 7.0);
    assert!(connection.status_json().contains(r#""paused":false"#));
}

#[test]
fn quiet_links_send_keepalive_queries() {
    let (mut connection, _band) = connected(false);
    assert!(!connection.tick(10.0).unwrap().is_empty());
}

#[test]
fn a_malformed_owner_key_is_refused() {
    assert!(Connection::new(&[0u8; 10], 0, false, "volume", "").is_err());
}

#[test]
fn a_remapped_swipe_runs_its_new_action_and_the_hold_stays_the_toggle() {
    let (mut connection, mut band) = connected(false);
    connection.set_mapping("swipe_down=volume.up;swipe_left=media.next;middle_hold=media.next");
    gesture(&mut connection, &mut band, 7, THUMB, 0, 3.0);
    assert_eq!(connection.take_actions(), vec!["volume.up".to_owned()]);
    gesture(&mut connection, &mut band, 8, THUMB, 0, 4.0);
    assert_eq!(connection.take_actions(), vec!["media.next".to_owned()]);
    gesture(&mut connection, &mut band, 0, MIDDLE, 3, 5.0);
    assert!(connection.take_actions().is_empty());
    assert!(connection.status_json().contains(r#""paused":true"#));
}

#[test]
fn an_unmapped_gesture_does_nothing() {
    let (mut connection, mut band) = connected(false);
    connection.set_mapping("swipe_down=");
    gesture(&mut connection, &mut band, 7, THUMB, 0, 3.0);
    assert!(connection.take_actions().is_empty());
}

#[test]
fn a_band_on_the_other_wrist_is_set_to_the_chosen_hand_after_connecting() {
    // The simulated band starts as right-handed.
    let (mut connection, mut band) = connected_with(false, "hand=left");
    for step in 0..20 {
        let out = connection.tick(2.0 + f64::from(step) * 0.05).unwrap();
        pump(
            &mut connection,
            &mut band,
            out,
            2.0 + f64::from(step) * 0.05,
        );
    }
    assert_eq!(band.hand, 1, "the band stores left");
    assert!(
        connection.status_json().contains(r#""hand":"left""#),
        "{}",
        connection.status_json()
    );
}

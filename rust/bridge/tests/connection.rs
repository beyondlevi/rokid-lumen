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

fn claim_events(connection: &mut Connection) -> Vec<serde_json::Value> {
    connection
        .take_claim_events()
        .iter()
        .map(|line| serde_json::from_str(line).unwrap())
        .collect()
}

fn unhex(text: &str) -> Vec<u8> {
    (0..text.len()).step_by(2).map(|i| u8::from_str_radix(&text[i..i + 2], 16).unwrap()).collect()
}

#[test]
fn a_claim_connection_runs_the_ceremony_through_its_events_and_ends_connected() {
    let band_key = SigningKey::random(&mut OsRng);
    let mut band = Responder::new(SimMode::Ceremony, band_key.clone());
    let mut connection = Connection::new_claim(0, false, "volume", "");
    let request = connection.request().unwrap();
    pump(&mut connection, &mut band, request, 1.0);
    // Identity read and skip challenge done: the app is asked for pair_request.
    let events = claim_events(&mut connection);
    let request = events.iter().find(|e| e["type"] == "pair_request").expect("pair_request");
    assert_eq!(unhex(request["nonce"].as_str().unwrap()), (0..16).collect::<Vec<u8>>());
    assert_eq!(unhex(request["app_pubkey"].as_str().unwrap()).len(), 64);
    assert!(events.iter().any(|e| e["type"] == "stage"));
    assert!(connection.claim_pending_key().is_none());
    // The server's pending receipt: StartChangeOwner, then the band's receipt asks for pair.
    let signature = [vec![0x30, 0x45, 0x02, 0x20], vec![3; 67]].concat();
    let out = connection.claim_pair_request_completed(&signature, r#"{"receipt_type":"pending"}"#).unwrap();
    pump(&mut connection, &mut band, out, 2.0);
    let pair = claim_events(&mut connection).into_iter().find(|e| e["type"] == "pair").expect("pair");
    assert!(!pair["receipt"].as_str().unwrap().is_empty());
    // The final receipt: the pending key exists before the bytes go out, and the band commits it.
    let band_point = band_core::identity::point64(band_key.verifying_key());
    let out = connection
        .claim_pair_completed(&[vec![0x30, 0x45, 0x02, 0x21], vec![5; 67]].concat(), r#"{"receipt_type":"final"}"#, Some(&band_point))
        .unwrap();
    let pending = connection.claim_pending_key().expect("pending key before writing");
    assert_eq!(pending.len(), 97);
    pump(&mut connection, &mut band, out, 3.0);
    let completed = claim_events(&mut connection).into_iter().find(|e| e["type"] == "completed").expect("completed");
    assert_eq!(unhex(completed["owner_key"].as_str().unwrap()), pending);
    // The new identity signs in and the connection carries on as usual.
    assert!(connection.take_log().contains(&"connected".to_owned()));
    // The committed key opens a normal connection to the same band.
    assert!(Connection::new(&pending, 0, false, "volume", "").is_ok());
}

//! The band's handwriting model from the bridge: switched on by name-found settings, text out,
//! the middle tap still mapped, and the band always put back.

use band_core::identity::EnrollmentIdentity;
use band_core::responder::{Responder, SimMode};
use lumen_band::Connection;
use p256::ecdsa::SigningKey;
use rand_core::OsRng;
use serde_json::Value;

const MIDDLE: u64 = 3;

fn pump(connection: &mut Connection, band: &mut Responder, mut to_band: Vec<u8>, now: f64) {
    for _ in 0..256 {
        if to_band.is_empty() {
            return;
        }
        let reply = band.respond(&to_band).unwrap();
        to_band = connection.feed(&reply, now).unwrap();
    }
    panic!("the exchange never settled");
}

fn connected(mapping: &str) -> (Connection, Responder) {
    let identity = EnrollmentIdentity::new(SigningKey::random(&mut OsRng), None);
    let mut connection = Connection::new(&identity.private_bytes(), 0, false, "volume", mapping).unwrap();
    let mut band = Responder::new(SimMode::Enrolled, SigningKey::random(&mut OsRng));
    let request = connection.request().unwrap();
    pump(&mut connection, &mut band, request, 1.0);
    assert!(connection.status_json().contains(r#""connected":true"#));
    connection.take_log();
    (connection, band)
}

/// Ticks until the band has nothing more to answer.
fn settle(connection: &mut Connection, band: &mut Responder, now: f64) {
    for _ in 0..8 {
        let bytes = connection.tick(now).unwrap();
        if bytes.is_empty() {
            return;
        }
        pump(connection, band, bytes, now);
    }
}

fn events(connection: &mut Connection) -> Vec<Value> {
    connection
        .take_handwriting_events()
        .iter()
        .map(|line| serde_json::from_str(line).unwrap())
        .collect()
}

fn phases(events: &[Value]) -> Vec<String> {
    events
        .iter()
        .filter(|event| event["type"] == "state")
        .map(|event| event["phase"].as_str().unwrap().to_owned())
        .collect()
}

/// Switch on and let samples arrive until the capture says ready.
fn start(connection: &mut Connection, band: &mut Responder, hints: Option<(u64, u64)>) -> Vec<Value> {
    let bytes = connection.set_handwriting(true, hints, 2.0).unwrap();
    pump(connection, band, bytes, 2.0);
    settle(connection, band, 2.0);
    for step in 0..6 {
        let now = 2.1 + f64::from(step) * 0.1;
        let bytes = band.handwriting(99).unwrap();
        let reply = connection.feed(&bytes, now).unwrap();
        pump(connection, band, reply, now);
    }
    settle(connection, band, 2.8);
    events(connection)
}

fn write(connection: &mut Connection, band: &mut Responder, classes: &[usize], at: f64) {
    for (index, &class) in classes.iter().enumerate() {
        let now = at + index as f64 * 0.02;
        let bytes = band.handwriting(class).unwrap();
        let reply = connection.feed(&bytes, now).unwrap();
        pump(connection, band, reply, now);
    }
}

#[test]
fn handwriting_finds_the_settings_by_name_writes_text_and_restores_the_band() {
    let (mut connection, mut band) = connected("middle_tap=nav.back;middle_double=screen.toggle;swipe_right=nav.right;index_tap=nav.activate");
    let started = start(&mut connection, &mut band, None);
    assert_eq!(phases(&started), vec!["preparing", "ready"]);
    let ready = started.iter().find(|e| e["phase"] == "ready").unwrap();
    // Found by name on this firmware: 28 and 29, not kinesis' 27 and 28.
    assert_eq!(ready["collection_id"], 28);
    assert_eq!(ready["model_id"], 29);
    assert_eq!(band.setting("data-collection"), 1);
    assert_eq!(band.setting("data-collection-model"), 5);
    assert!(band.model_streams());
    // Only the two settings were ever written.
    assert!(band.writes.iter().all(|(id, _)| *id == 28 || *id == 29));

    // h i (blank between) then shift + "o": "hiO"
    write(&mut connection, &mut band, &[7, 7, 99, 8, 99, 97, 99, 14], 3.0);
    let texts: Vec<Value> = events(&mut connection).into_iter().filter(|e| e["type"] == "text").collect();
    assert_eq!(texts.last().unwrap()["text"], "hiO");

    // While writing, a stroke read as a swipe or as the pausing middle hold does nothing...
    for (action, finger, derived) in [(9, 1, 0), (0, MIDDLE, 3), (3, 2, 0)] {
        let bytes = band.gesture(action, finger, derived).unwrap();
        let reply = connection.feed(&bytes, 3.3).unwrap();
        pump(&mut connection, &mut band, reply, 3.3);
    }
    connection.tick(3.3).unwrap();
    assert!(connection.take_actions().is_empty());
    assert!(connection.status_json().contains(r#""paused":false"#));
    // ...and the middle tap goes through at once, without waiting for a double tap.
    let tap = band.gesture(3, MIDDLE, 0).unwrap();
    let reply = connection.feed(&tap, 3.8).unwrap();
    pump(&mut connection, &mut band, reply, 3.8);
    assert_eq!(connection.take_actions(), vec!["nav.back".to_owned()]);

    let bytes = connection.set_handwriting(false, None, 4.0).unwrap();
    pump(&mut connection, &mut band, bytes, 4.0);
    settle(&mut connection, &mut band, 4.0);
    let ended = events(&mut connection);
    assert_eq!(phases(&ended), vec!["restoring", "finished"]);
    let finished = ended.last().unwrap();
    assert_eq!(finished["verified"], true);
    assert_eq!(band.setting("data-collection"), 0);
    assert_eq!(band.setting("data-collection-model"), 2);
    assert!(!band.model_streams());
    // Text never reaches the log.
    assert!(connection.take_log().iter().all(|line| !line.contains("hiO")));
}

#[test]
fn hints_shorten_the_lookup_and_a_wrong_hint_falls_back_to_the_names() {
    let (mut connection, mut band) = connected("");
    // Wrong hints (27 is stream_in_standby): never written, the lookup goes on by name.
    let started = start(&mut connection, &mut band, Some((27, 28)));
    let ready = started.iter().find(|e| e["phase"] == "ready").unwrap();
    assert_eq!(ready["collection_id"], 28);
    assert_eq!(ready["model_id"], 29);
    assert!(band.writes.iter().all(|(id, _)| *id == 28 || *id == 29));
}

#[test]
fn a_lookup_that_never_answers_is_skipped() {
    let (mut connection, mut band) = connected("");
    band.silent_lookups = vec![9];
    let bytes = connection.set_handwriting(true, None, 2.0).unwrap();
    pump(&mut connection, &mut band, bytes, 2.0);
    // Lookup 9 gets no answer: after 1.5 s the next id is asked.
    for step in 0..40 {
        let now = 2.0 + f64::from(step) * 0.5;
        let bytes = connection.tick(now).unwrap();
        pump(&mut connection, &mut band, bytes, now);
    }
    assert_eq!(band.setting("data-collection-model"), 5);
}

#[test]
fn recovery_puts_back_a_band_left_in_the_model() {
    // The app went away mid-capture: the band is still in the model on the next connection.
    let (mut fresh, mut band) = connected("");
    band.settings[27] = 1;
    band.settings[28] = 5;
    let bytes = fresh.recover_handwriting(None, 5.0).unwrap();
    pump(&mut fresh, &mut band, bytes, 5.0);
    settle(&mut fresh, &mut band, 5.0);
    let ended = events(&mut fresh);
    assert_eq!(phases(&ended), vec!["restoring", "finished"]);
    assert_eq!(ended.last().unwrap()["verified"], true);
    assert_eq!(band.setting("data-collection"), 0);
    assert_eq!(band.setting("data-collection-model"), 2);
}

#[test]
fn a_silent_model_ends_the_capture_and_restores() {
    let (mut connection, mut band) = connected("");
    let _ = start(&mut connection, &mut band, None);
    // No samples for more than 3 s while ready: fail, then restore.
    for step in 0..10 {
        let now = 7.0 + f64::from(step) * 0.5;
        let bytes = connection.tick(now).unwrap();
        pump(&mut connection, &mut band, bytes, now);
    }
    let ended = events(&mut connection);
    let finished = ended.iter().find(|e| e["phase"] == "finished").unwrap();
    assert_eq!(finished["verified"], true);
    assert!(finished["problem"].as_str().unwrap().contains("stopped sending"));
    assert_eq!(band.setting("data-collection-model"), 2);
}

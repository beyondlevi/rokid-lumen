//! JNI entry points for `dev.lumen.band.Bridge` (static natives). A connection
//! is a boxed `Connection` behind a `long` handle; errors become IOException.

use jni::JNIEnv;
use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jboolean, jbyteArray, jdouble, jint, jlong, jstring};

use crate::Connection;

fn throw(env: &mut JNIEnv, message: &str) {
    let _ = env.throw_new("java/io/IOException", message);
}

/// # Safety
/// `handle` must come from `open` and not have been passed to `close`.
unsafe fn connection<'a>(handle: jlong) -> &'a mut Connection {
    unsafe { &mut *(handle as *mut Connection) }
}

fn bytes_out(env: &mut JNIEnv, result: Result<Vec<u8>, String>) -> jbyteArray {
    match result.and_then(|bytes| env.byte_array_from_slice(&bytes).map_err(|e| e.to_string())) {
        Ok(array) => array.into_raw(),
        Err(message) => {
            throw(env, &message);
            std::ptr::null_mut()
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_lumen_band_Bridge_open<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    owner_key: JByteArray<'local>,
    scheme_guess: jint,
    paused: jboolean,
    dial: JString<'local>,
    mapping: JString<'local>,
) -> jlong {
    let strings = env.get_string(&dial).map(String::from).and_then(|dial| {
        env.get_string(&mapping)
            .map(|mapping| (dial, String::from(mapping)))
    });
    let (dial, mapping) = match strings {
        Ok(strings) => strings,
        Err(error) => {
            throw(&mut env, &error.to_string());
            return 0;
        }
    };
    let opened = env
        .convert_byte_array(&owner_key)
        .map_err(|e| e.to_string())
        .and_then(|key| {
            Connection::new(
                &key,
                scheme_guess.max(0) as usize,
                paused != 0,
                &dial,
                &mapping,
            )
        });
    match opened {
        Ok(connection) => Box::into_raw(Box::new(connection)) as jlong,
        Err(message) => {
            throw(&mut env, &message);
            0
        }
    }
}

/// A connection that claims a band in pairing mode ([Connection::new_claim]).
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_lumen_band_Bridge_openClaim<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    scheme_guess: jint,
    paused: jboolean,
    dial: JString<'local>,
    mapping: JString<'local>,
) -> jlong {
    let strings = env.get_string(&dial).map(String::from).and_then(|dial| {
        env.get_string(&mapping)
            .map(|mapping| (dial, String::from(mapping)))
    });
    match strings {
        Ok((dial, mapping)) => {
            let connection = Connection::new_claim(scheme_guess.max(0) as usize, paused != 0, &dial, &mapping);
            Box::into_raw(Box::new(connection)) as jlong
        }
        Err(error) => {
            throw(&mut env, &error.to_string());
            0
        }
    }
}

/// The ceremony's events since the last call: one JSON object per line.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_lumen_band_Bridge_claimEvents<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jstring {
    let lines = unsafe { connection(handle) }.take_claim_events().join("\n");
    string_out(&mut env, lines)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_lumen_band_Bridge_claimPairRequestCompleted<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    signature: JByteArray<'local>,
    receipt: JString<'local>,
) -> jbyteArray {
    let result = env
        .convert_byte_array(&signature)
        .map_err(|e| e.to_string())
        .and_then(|signature| {
            env.get_string(&receipt)
                .map(String::from)
                .map_err(|e| e.to_string())
                .map(|receipt| (signature, receipt))
        })
        .and_then(|(signature, receipt)| unsafe { connection(handle) }.claim_pair_request_completed(&signature, &receipt));
    bytes_out(&mut env, result)
}

/// `device_key` may be null (the server didn't return the band's key).
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_lumen_band_Bridge_claimPairCompleted<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    signature: JByteArray<'local>,
    receipt: JString<'local>,
    device_key: JByteArray<'local>,
) -> jbyteArray {
    let device = if device_key.is_null() {
        Ok(None)
    } else {
        env.convert_byte_array(&device_key).map(Some).map_err(|e| e.to_string())
    };
    let result = env
        .convert_byte_array(&signature)
        .map_err(|e| e.to_string())
        .and_then(|signature| {
            env.get_string(&receipt)
                .map(String::from)
                .map_err(|e| e.to_string())
                .map(|receipt| (signature, receipt))
        })
        .and_then(|(signature, receipt)| device.map(|device| (signature, receipt, device)))
        .and_then(|(signature, receipt, device)| {
            unsafe { connection(handle) }.claim_pair_completed(&signature, &receipt, device.as_deref())
        });
    bytes_out(&mut env, result)
}

/// The owner key the band is about to commit, or null before `claimPairCompleted`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_lumen_band_Bridge_claimPendingKey<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jbyteArray {
    match unsafe { connection(handle) }.claim_pending_key() {
        Some(key) => bytes_out(&mut env, Ok(key)),
        None => std::ptr::null_mut(),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_lumen_band_Bridge_request<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jbyteArray {
    let result = unsafe { connection(handle) }.request();
    bytes_out(&mut env, result)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_lumen_band_Bridge_feed<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    bytes: JByteArray<'local>,
    now: jdouble,
) -> jbyteArray {
    let result = env
        .convert_byte_array(&bytes)
        .map_err(|e| e.to_string())
        .and_then(|bytes| unsafe { connection(handle) }.feed(&bytes, now));
    bytes_out(&mut env, result)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_lumen_band_Bridge_tick<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    now: jdouble,
) -> jbyteArray {
    let result = unsafe { connection(handle) }.tick(now);
    bytes_out(&mut env, result)
}

fn string_out(env: &mut JNIEnv, text: String) -> jstring {
    match env.new_string(text) {
        Ok(string) => string.into_raw(),
        Err(error) => {
            throw(env, &error.to_string());
            std::ptr::null_mut()
        }
    }
}

/// Action names to run since the last call, joined with '\n' (empty: none).
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_lumen_band_Bridge_actions<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jstring {
    let lines = unsafe { connection(handle) }.take_actions().join("\n");
    string_out(&mut env, lines)
}

/// Log lines since the last call, joined with '\n' (empty: none).
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_lumen_band_Bridge_log<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jstring {
    let lines = unsafe { connection(handle) }.take_log().join("\n");
    string_out(&mut env, lines)
}

/// The status snapshot as JSON.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_lumen_band_Bridge_status<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jstring {
    let json = unsafe { connection(handle) }.status_json();
    string_out(&mut env, json)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_lumen_band_Bridge_setPaused<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    paused: jboolean,
    now: jdouble,
) {
    unsafe { connection(handle) }.set_paused(paused != 0, now);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_lumen_band_Bridge_setMotion<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    enabled: jboolean,
) {
    unsafe { connection(handle) }.set_motion(enabled != 0);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_lumen_band_Bridge_setGestures<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    enabled: jboolean,
) {
    unsafe { connection(handle) }.set_gestures(enabled != 0);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_lumen_band_Bridge_setDial<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    dial: JString<'local>,
) {
    match env.get_string(&dial) {
        Ok(dial) => unsafe { connection(handle) }.set_dial(&String::from(dial)),
        Err(error) => throw(&mut env, &error.to_string()),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_lumen_band_Bridge_setMapping<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    mapping: JString<'local>,
) {
    match env.get_string(&mapping) {
        Ok(mapping) => unsafe { connection(handle) }.set_mapping(&String::from(mapping)),
        Err(error) => throw(&mut env, &error.to_string()),
    }
}

/// Hint ids from the app: both above zero, or none.
fn hints(collection: jint, model: jint) -> Option<(u64, u64)> {
    (collection > 0 && model > 0).then(|| (collection as u64, model as u64))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_lumen_band_Bridge_setHandwriting<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    enabled: jboolean,
    collection: jint,
    model: jint,
    now: jdouble,
) -> jbyteArray {
    let result = unsafe { connection(handle) }.set_handwriting(enabled != 0, hints(collection, model), now);
    bytes_out(&mut env, result)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_lumen_band_Bridge_recoverHandwriting<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    collection: jint,
    model: jint,
    now: jdouble,
) -> jbyteArray {
    let result = unsafe { connection(handle) }.recover_handwriting(hints(collection, model), now);
    bytes_out(&mut env, result)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_lumen_band_Bridge_resetHandwritingText<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    text: JString<'local>,
) {
    match env.get_string(&text) {
        Ok(text) => unsafe { connection(handle) }.reset_handwriting_text(&String::from(text)),
        Err(error) => throw(&mut env, &error.to_string()),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_lumen_band_Bridge_handwritingEvents<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jstring {
    let lines = unsafe { connection(handle) }.take_handwriting_events().join("\n");
    string_out(&mut env, lines)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_lumen_band_Bridge_close<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) {
    if handle != 0 {
        drop(unsafe { Box::from_raw(handle as *mut Connection) });
    }
}

#!/system/bin/sh

PKG="${NEURALBAND_PACKAGE:-dev.lumen.glasses}"
BASE="${NEURALBAND_BRIDGE_DIR:-/sdcard/Android/data/$PKG/files/shortcut_bridge}"
REQUEST="$BASE/request"
RESPONSE="$BASE/response"
HEARTBEAT="$BASE/heartbeat"
DOORBELL="$BASE/doorbell"
PIDFILE="${NEURALBAND_PIDFILE:-/data/local/tmp/lumen-shortcut-bridge.pid}"
LOGFILE="${NEURALBAND_LOGFILE:-/data/local/tmp/lumen-shortcut-bridge.log}"
INPUT_DEVICE="${NEURALBAND_INPUT_DEVICE:-/dev/input/event1}"
SETTINGS_SCAN_CODE="${NEURALBAND_SETTINGS_SCAN_CODE:-149}"
POLL_SECONDS="${NEURALBAND_POLL_SECONDS:-0.2}"
IDLE_RECHECK_SECONDS="${NEURALBAND_IDLE_RECHECK_SECONDS:-5}"
WIFI_DISABLE_DELAY_SECONDS="${NEURALBAND_WIFI_DISABLE_DELAY_SECONDS:-12}"
# The request file outlives the bridge: without an age limit, a fresh bridge (every boot) ran
# the self-arm's last "wifi_disable" again and cut the Wi-Fi an app had just joined (measured).
REQUEST_MAX_AGE_SECONDS="${NEURALBAND_REQUEST_MAX_AGE_SECONDS:-30}"

log_msg() {
    echo "$(date +%s) $*" >> "$LOGFILE"
}

is_alive() {
    pid="$1"
    case "$pid" in
        ''|*[!0-9]*) return 1 ;;
    esac
    [ -d "/proc/$pid" ] || return 1
    # PID files survive reboots and the old PID can belong to an unrelated process.
    # Check the helper command before treating it as running (or sending it a signal).
    cmdline="$(tr '\000' ' ' < "/proc/$pid/cmdline" 2>/dev/null)"
    case "$cmdline" in
        *"sh $0 run "*) return 0 ;;
        *) return 1 ;;
    esac
}

read_pid() {
    cat "$PIDFILE" 2>/dev/null
}

trigger_shortcut() {
    sendevent "$INPUT_DEVICE" 1 "$SETTINGS_SCAN_CODE" 1
    sendevent "$INPUT_DEVICE" 0 0 0
    sendevent "$INPUT_DEVICE" 1 "$SETTINGS_SCAN_CODE" 0
    sendevent "$INPUT_DEVICE" 0 0 0
}

set_wifi_enabled() {
    if [ "$1" = "1" ]; then
        for attempt in 1 2 3; do
            svc wifi enable >/dev/null 2>&1 || true
            cmd wifi set-wifi-enabled enabled >/dev/null 2>&1 || true
            sleep 1
            if wifi_is_enabled; then
                return 0
            fi
        done
    else
        for attempt in 1 2 3 4 5; do
            svc wifi disable >/dev/null 2>&1 || true
            cmd wifi set-wifi-enabled disabled >/dev/null 2>&1 || true
            sleep 1
            if ! wifi_is_enabled; then
                return 0
            fi
        done
    fi
    return 1
}

disable_wifi_scan() {
    # Turn off always-on Wi-Fi scanning so the radio stays fully quiet.
    settings put global wifi_scan_always_enabled 0 >/dev/null 2>&1 || true
}

schedule_wifi_disable() {
    request="$1"
    now="$2"
    echo "scheduled wifi_disable $request $now delay=${WIFI_DISABLE_DELAY_SECONDS}s" > "$RESPONSE"
    log_msg "wifi disable scheduled request=$request delay=${WIFI_DISABLE_DELAY_SECONDS}s"
    # Let the wireless ADB client close before Wi-Fi drops; Rokid firmware can
    # wedge system_server if the radio disappears while adbwifi is mid-teardown.
    (
        sleep "$WIFI_DISABLE_DELAY_SECONDS"
        # A newer request (Wi-Fi back on, say) supersedes this one.
        if [ "$(cat "$REQUEST" 2>/dev/null)" != "$request" ]; then
            log_msg "wifi disable superseded request=$request"
            exit 0
        fi
        disable_wifi_scan
        if set_wifi_enabled 0; then
            log_msg "wifi disable ok request=$request"
        else
            log_msg "wifi disable failed request=$request"
        fi
    ) >/dev/null 2>&1 &
}

wifi_is_enabled() {
    if cmd wifi status 2>/dev/null | grep -qi "Wifi is enabled"; then
        return 0
    fi
    [ "$(settings get global wifi_on 2>/dev/null)" = "1" ]
}

handle_request() {
    request="$1"
    now="$2"
    command="${request%%:*}"
    if [ "$command" = "$request" ]; then
        command="shortcut"
    fi
    case "$command" in
        shortcut)
            if trigger_shortcut; then
                echo "ok shortcut $request $now" > "$RESPONSE"
                log_msg "trigger ok request=$request"
            else
                echo "failed shortcut $request $now" > "$RESPONSE"
                log_msg "trigger failed request=$request"
            fi
            ;;
        wifi_enable)
            if set_wifi_enabled 1; then
                echo "ok wifi_enable $request $now" > "$RESPONSE"
                log_msg "wifi enable ok request=$request"
            else
                echo "failed wifi_enable $request $now" > "$RESPONSE"
                log_msg "wifi enable failed request=$request"
            fi
            ;;
        wifi_disable)
            schedule_wifi_disable "$request" "$now"
            ;;
        *)
            echo "ignored $request $now" > "$RESPONSE"
            log_msg "ignored request=$request"
            ;;
    esac
}

# "command:timestamp", the timestamp in seconds (shell) or milliseconds (the app).
is_stale() {
    stamp="${1##*:}"
    case "$stamp" in
        ''|*[!0-9]*) return 1 ;;
    esac
    if [ "${#stamp}" -gt 11 ]; then
        stamp=$((stamp / 1000))
    fi
    [ $(($2 - stamp)) -gt "$REQUEST_MAX_AGE_SECONDS" ]
}

check_and_dispatch() {
    now="$(date +%s)"
    echo "$now" > "$HEARTBEAT"
    current="$(cat "$REQUEST" 2>/dev/null)"
    if [ -n "$current" ] && [ "$current" != "$last" ]; then
        last="$current"
        if is_stale "$current" "$now"; then
            log_msg "stale request ignored request=$current"
            return 0
        fi
        handle_request "$current" "$now"
    fi
}

prepare_doorbell() {
    if [ -e "$DOORBELL" ] && [ ! -p "$DOORBELL" ]; then
        log_msg "doorbell unavailable not_fifo path=$DOORBELL"
        return 1
    fi
    if [ ! -p "$DOORBELL" ]; then
        if ! mkfifo "$DOORBELL" >/dev/null 2>&1; then
            log_msg "doorbell mkfifo failed path=$DOORBELL"
            return 1
        fi
    fi
    chmod 666 "$DOORBELL" >/dev/null 2>&1 || true
    if ! exec 3<> "$DOORBELL"; then
        log_msg "doorbell open failed path=$DOORBELL"
        return 1
    fi
    read -t 0 _ <&3 >/dev/null 2>&1
    read_status="$?"
    # Android mksh reports read -t timeout as 142 (SIGALRM), not 1.
    case "$read_status" in
        0|1|142)
            return 0
            ;;
        *)
            log_msg "doorbell read probe failed status=$read_status"
            exec 3<&-
            return 1
            ;;
    esac
}

run_poll_loop() {
    last="$(cat "$REQUEST" 2>/dev/null)"
    log_msg "poll loop interval=${POLL_SECONDS}s"
    while true; do
        check_and_dispatch
        sleep "$POLL_SECONDS"
    done
}

run_event_loop() {
    last=""
    log_msg "doorbell loop path=$DOORBELL idle_recheck=${IDLE_RECHECK_SECONDS}s"
    check_and_dispatch
    while true; do
        read -t "$IDLE_RECHECK_SECONDS" _ <&3 >/dev/null 2>&1
        read_status="$?"
        # Android mksh reports read -t timeout as 142 (SIGALRM), not 1.
        case "$read_status" in
            0|1|142)
                ;;
            *)
                log_msg "doorbell read failed status=$read_status; falling back to poll"
                exec 3<&-
                run_poll_loop
                ;;
        esac
        check_and_dispatch
    done
}

run_loop() {
    mkdir -p "$BASE"
    echo "$$" > "$PIDFILE"
    log_msg "run pid=$$ base=$BASE input=$INPUT_DEVICE code=$SETTINGS_SCAN_CODE"
    if prepare_doorbell; then
        run_event_loop
    fi
    run_poll_loop
}

start_bridge() {
    mkdir -p "$BASE"
    pid="$(read_pid)"
    if is_alive "$pid"; then
        echo "already running pid=$pid"
        exit 0
    fi
    rm -f "$PIDFILE"
    nohup sh "$0" run >/dev/null 2>&1 &
    sleep 0.3
    pid="$(read_pid)"
    if is_alive "$pid"; then
        echo "started pid=$pid base=$BASE"
    else
        echo "failed to start"
        exit 1
    fi
}

stop_bridge() {
    pid="$(read_pid)"
    if is_alive "$pid"; then
        kill "$pid"
        rm -f "$PIDFILE"
        echo "stopped pid=$pid"
    else
        rm -f "$PIDFILE"
        echo "not running"
    fi
}

status_bridge() {
    pid="$(read_pid)"
    if is_alive "$pid"; then
        echo "running pid=$pid base=$BASE heartbeat=$(cat "$HEARTBEAT" 2>/dev/null)"
    else
        echo "not running base=$BASE"
        exit 1
    fi
}

case "$1" in
    run)
        run_loop
        ;;
    start|"")
        start_bridge
        ;;
    stop)
        stop_bridge
        ;;
    restart)
        stop_bridge >/dev/null 2>&1
        start_bridge
        ;;
    status)
        status_bridge
        ;;
    trigger)
        trigger_shortcut
        ;;
    wifi-enable)
        set_wifi_enabled 1
        ;;
    wifi-disable)
        disable_wifi_scan
        set_wifi_enabled 0
        ;;
    *)
        echo "usage: $0 [start|stop|restart|status|trigger|wifi-enable|wifi-disable]"
        exit 2
        ;;
esac

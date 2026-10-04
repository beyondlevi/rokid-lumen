#!/system/bin/sh

VERSION="2"
PKG="${NEURALBAND_PACKAGE:-dev.lumen.glasses}"
SVC="${NEURALBAND_A11Y_SERVICE:-dev.lumen.glasses/dev.lumen.glasses.BandAccessibilityService}"
SCRIPT_NAME="lumen-a11y-watchdog.sh"
PIDFILE="${NEURALBAND_A11Y_PIDFILE:-/data/local/tmp/lumen-a11y-watchdog.pid}"
VERSIONFILE="${NEURALBAND_A11Y_VERSIONFILE:-/data/local/tmp/lumen-a11y-watchdog.version}"
LOGFILE="${NEURALBAND_A11Y_LOGFILE:-/data/local/tmp/lumen-a11y-watchdog.log}"
HEARTBEAT="${NEURALBAND_A11Y_HEARTBEAT:-/data/local/tmp/lumen-a11y-watchdog.heartbeat}"
# Every POLL_SECONDS the loop only checks that the app's process is still there, with shell
# builtins (no process but sleep). The full check (settings, pidof) runs every FULL_CHECK_EVERY
# ticks, or as soon as the process is gone. Version 1 ran the full check every second: about 18
# processes a second, all day, measured on the glasses (/proc/stat).
POLL_SECONDS="${NEURALBAND_A11Y_POLL_SECONDS:-3}"
FULL_CHECK_EVERY="${NEURALBAND_A11Y_FULL_CHECK_EVERY:-10}"
LOG_HEALTHY_EVERY="${NEURALBAND_A11Y_LOG_HEALTHY_EVERY:-20}"
MAX_LOG_BYTES="${NEURALBAND_A11Y_MAX_LOG_BYTES:-65536}"

log_msg() {
    echo "$(date +%s) $*" >> "$LOGFILE"
}

rotate_log() {
    size="$(wc -c < "$LOGFILE" 2>/dev/null)"
    case "$size" in
        ''|*[!0-9]*) return ;;
    esac
    if [ "$size" -gt "$MAX_LOG_BYTES" ]; then
        tail -c 32768 "$LOGFILE" > "$LOGFILE.tmp" 2>/dev/null && mv "$LOGFILE.tmp" "$LOGFILE"
    fi
}

contains_service() {
    case ":$1:" in
        *":$SVC:"*) return 0 ;;
        *) return 1 ;;
    esac
}

is_alive() {
    pid="$1"
    [ -n "$pid" ] || return 1
    [ -d "/proc/$pid" ] || return 1
    cmdline="$(tr '\000' ' ' < "/proc/$pid/cmdline" 2>/dev/null)"
    case "$cmdline" in
        *"$SCRIPT_NAME"*) return 0 ;;
        *) return 1 ;;
    esac
}

read_pid() {
    cat "$PIDFILE" 2>/dev/null
}

read_version() {
    cat "$VERSIONFILE" 2>/dev/null
}

app_pid() {
    pids="$(pidof "$PKG" 2>/dev/null)"
    echo "${pids%% *}"
}

repair_accessibility() {
    cur="$(settings get secure enabled_accessibility_services 2>/dev/null)"
    [ "$cur" = "null" ] && cur=""
    if [ -z "$cur" ]; then
        new="$SVC"
    elif contains_service "$cur"; then
        new="$cur"
    else
        new="$cur:$SVC"
    fi

    settings put secure enabled_accessibility_services "$new" >/dev/null 2>&1
    settings put secure accessibility_enabled 1 >/dev/null 2>&1

    # Starting the activity clears Android's force-stopped flag; Home keeps the HUD unobtrusive.
    am start -n "$PKG/dev.lumen.glasses.MainActivity" --activity-clear-top >/dev/null 2>&1 || true
    sleep 1
    input keyevent 3 >/dev/null 2>&1 || true

    log_msg "repaired cur='$cur' new='$new' pid=$(app_pid)"
}

state_ok() {
    a11y_enabled="$(settings get secure accessibility_enabled 2>/dev/null)"
    enabled_services="$(settings get secure enabled_accessibility_services 2>/dev/null)"
    pid="$(app_pid)"
    reason=""
    [ "$a11y_enabled" = "1" ] || reason="${reason}a11y=$a11y_enabled "
    contains_service "$enabled_services" || reason="${reason}service_missing "
    [ -n "$pid" ] || reason="${reason}pid_missing "
    [ -z "$reason" ]
}

run_loop() {
    echo "$$" > "$PIDFILE"
    echo "$VERSION" > "$VERSIONFILE"
    log_msg "start pid=$$ version=$VERSION service=$SVC"
    tick=0
    checks=0
    pid=""
    healthy=0
    while true; do
        tick=$((tick + 1))
        # Cheap path: the last full check was fine and the app's process is still there.
        if [ "$healthy" = 1 ] && [ -d "/proc/$pid" ] && [ $((tick % FULL_CHECK_EVERY)) -ne 0 ]; then
            sleep "$POLL_SECONDS"
            continue
        fi
        checks=$((checks + 1))
        echo "$(date +%s)" > "$HEARTBEAT"
        if state_ok; then
            healthy=1
            if [ "$LOG_HEALTHY_EVERY" -gt 0 ] && [ $((checks % LOG_HEALTHY_EVERY)) -eq 0 ]; then
                log_msg "healthy tick=$tick pid=$pid"
                rotate_log
            fi
        else
            healthy=0
            log_msg "repair_needed tick=$tick reason='$reason' a11y='$a11y_enabled' pid='$pid' svcs='$enabled_services'"
            repair_accessibility
            rotate_log
        fi
        sleep "$POLL_SECONDS"
    done
}

start_watchdog() {
    pid="$(read_pid)"
    running_version="$(read_version)"
    if is_alive "$pid" && [ "$running_version" = "$VERSION" ]; then
        echo "already running pid=$pid version=$running_version heartbeat=$(cat "$HEARTBEAT" 2>/dev/null)"
        exit 0
    fi
    if is_alive "$pid"; then
        kill "$pid" >/dev/null 2>&1 || true
        sleep 0.2
    fi
    rm -f "$PIDFILE"
    nohup sh "$0" run >/dev/null 2>&1 &
    sleep 0.5
    pid="$(read_pid)"
    if is_alive "$pid"; then
        echo "started pid=$pid version=$VERSION"
    else
        echo "failed to start"
        exit 1
    fi
}

stop_watchdog() {
    pid="$(read_pid)"
    if is_alive "$pid"; then
        kill "$pid" >/dev/null 2>&1 || true
        rm -f "$PIDFILE"
        log_msg "stopped pid=$pid"
        echo "stopped pid=$pid"
    else
        rm -f "$PIDFILE"
        echo "not running"
    fi
}

status_watchdog() {
    pid="$(read_pid)"
    if is_alive "$pid"; then
        echo "running pid=$pid version=$(read_version) heartbeat=$(cat "$HEARTBEAT" 2>/dev/null)"
    else
        echo "not running"
        exit 1
    fi
}

case "$1" in
    run)
        run_loop
        ;;
    start|"")
        start_watchdog
        ;;
    stop)
        stop_watchdog
        ;;
    restart)
        stop_watchdog >/dev/null 2>&1
        start_watchdog
        ;;
    status)
        status_watchdog
        ;;
    repair)
        repair_accessibility
        ;;
    *)
        echo "usage: $0 [start|stop|restart|status|repair]"
        exit 2
        ;;
esac
